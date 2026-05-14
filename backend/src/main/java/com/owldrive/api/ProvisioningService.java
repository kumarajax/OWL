package com.owldrive.api;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProvisioningService {
    private static final long DEFAULT_USER_QUOTA_BYTES = 2L * 1024 * 1024 * 1024;

    private final ShardJdbcRegistry shardJdbcRegistry;
    private final UserCapacityService userCapacityService;

    public ProvisioningService(ShardJdbcRegistry shardJdbcRegistry, UserCapacityService userCapacityService) {
        this.shardJdbcRegistry = shardJdbcRegistry;
        this.userCapacityService = userCapacityService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserRecord ensureUser(Jwt jwt) {
        LocatedUserRecord located = locateOrCreateUser(jwt);
        requireActive(located.user());
        return located.user();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserRecord currentUser(Jwt jwt) {
        LocatedUserRecord located = locateOrCreateUser(jwt);
        requireActive(located.user());
        return located.user();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserRecord activateUser(Jwt jwt) {
        String keycloakId = jwt.getSubject();
        String username = claim(jwt, "preferred_username", keycloakId);
        String email = requiredEmail(jwt);
        String displayName = resolveDisplayName(jwt, username, email, keycloakId);
        String role = resolveRole(jwt);
        Long quotaBytes = "ADMIN".equals(role) ? null : DEFAULT_USER_QUOTA_BYTES;

        Optional<LocatedUserRecord> existing = shardJdbcRegistry.findUserByKeycloakId(keycloakId);
        if (existing.isEmpty()) {
            existing = findSingleUserByVerifiedEmail(jwt, email);
        }
        if (existing.isEmpty()) {
            return ensureUser(jwt);
        }

        String shardId = existing.get().shardId();
        ShardContext.setCurrentShard(shardId);
        if (existing.get().user().deactivatedAt() != null) {
            userCapacityService.requireAvailableSlot();
        }
        return updateUser(shardId, existing.get().user().id(), keycloakId, email, username, displayName, role, quotaBytes);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FolderRecord ensureRootFolder(UserRecord user) {
        return shardJdbcRegistry.currentOrPrimary().query(
                """
                SELECT id, name, owner_id, parent_id, created_at, updated_at, deleted_at
                FROM folders
                WHERE owner_id = ? AND parent_id IS NULL AND deleted_at IS NULL
                """,
                this::mapFolder,
                user.id()).stream().findFirst().orElseGet(() -> shardJdbcRegistry.currentOrPrimary().queryForObject(
                """
                INSERT INTO folders (name, owner_id, parent_id)
                VALUES ('My Drive', ?, NULL)
                RETURNING id, name, owner_id, parent_id, created_at, updated_at, deleted_at
                """,
                this::mapFolder,
                user.id()));
    }

    private LocatedUserRecord locateOrCreateUser(Jwt jwt) {
        String keycloakId = jwt.getSubject();
        String username = claim(jwt, "preferred_username", keycloakId);
        String email = requiredEmail(jwt);
        String displayName = resolveDisplayName(jwt, username, email, keycloakId);
        String role = resolveRole(jwt);
        Long quotaBytes = "ADMIN".equals(role) ? null : DEFAULT_USER_QUOTA_BYTES;

        Optional<LocatedUserRecord> existing = shardJdbcRegistry.findUserByKeycloakId(keycloakId);
        if (existing.isPresent()) {
            ShardContext.setCurrentShard(existing.get().shardId());
            if (existing.get().user().deactivatedAt() != null) {
                return existing.get();
            }
            return new LocatedUserRecord(existing.get().shardId(),
                    updateUser(existing.get().shardId(), existing.get().user().id(), keycloakId, email, username, displayName, role, quotaBytes));
        }

        Optional<LocatedUserRecord> existingByEmail = findSingleUserByVerifiedEmail(jwt, email);
        if (existingByEmail.isPresent()) {
            ShardContext.setCurrentShard(existingByEmail.get().shardId());
            if (existingByEmail.get().user().deactivatedAt() != null) {
                return existingByEmail.get();
            }
            return new LocatedUserRecord(existingByEmail.get().shardId(),
                    updateUser(existingByEmail.get().shardId(), existingByEmail.get().user().id(), keycloakId, email, username, displayName, role, quotaBytes));
        }

        userCapacityService.requireAvailableSlot();
        String shardId = selectShardForNewUser();
        ShardContext.setCurrentShard(shardId);
        UserRecord user = shardJdbcRegistry.jdbc(shardId).queryForObject(
                """
                INSERT INTO users (keycloak_id, display_name, email, username, role, quota_bytes, used_bytes)
                VALUES (?, ?, ?, ?, ?, ?, 0)
                RETURNING id, keycloak_id, display_name, email, username, role, quota_bytes, used_bytes, created_at, deactivated_at
                """,
                this::mapUser,
                keycloakId,
                displayName,
                email,
                username,
                role,
                quotaBytes);
        ensureRootFolder(user);
        return new LocatedUserRecord(shardId, user);
    }

    private UserRecord updateUser(String shardId, UUID id, String keycloakId, String email, String username, String displayName, String role, Long quotaBytes) {
        return shardJdbcRegistry.jdbc(shardId).queryForObject(
                """
                UPDATE users
                SET keycloak_id = ?, display_name = ?, email = ?, username = ?, role = ?, quota_bytes = ?
                WHERE id = ? AND deactivated_at IS NULL
                RETURNING id, keycloak_id, display_name, email, username, role, quota_bytes, used_bytes, created_at, deactivated_at
                """,
                this::mapUser,
                keycloakId,
                displayName,
                email == null ? null : email.trim(),
                username,
                role,
                quotaBytes,
                id);
    }

    private Optional<LocatedUserRecord> findSingleUserByVerifiedEmail(Jwt jwt, String email) {
        if (!isEmailVerified(jwt)) {
            return Optional.empty();
        }
        Collection<String> shardIds = shardJdbcRegistry.shardIds();
        LocatedUserRecord match = null;
        for (String shardId : shardIds) {
            var matches = shardJdbcRegistry.jdbc(shardId).query(
                    """
                    SELECT id, keycloak_id, display_name, email, username, role, quota_bytes, used_bytes, created_at, deactivated_at
                    FROM users
                    WHERE lower(email) = lower(?)
                    """,
                    this::mapUser,
                    email);
            if (matches.size() == 1) {
                if (match != null) {
                    return Optional.empty();
                }
                match = new LocatedUserRecord(shardId, matches.get(0));
            } else if (matches.size() > 1) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(match);
    }

    private boolean isEmailVerified(Jwt jwt) {
        Boolean emailVerified = jwt.getClaimAsString("email_verified") == null
                ? jwt.getClaim("email_verified")
                : Boolean.valueOf(jwt.getClaimAsString("email_verified"));
        return Boolean.TRUE.equals(emailVerified);
    }

    private String selectShardForNewUser() {
        return shardJdbcRegistry.orderedShardIdsByLoad().stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No postgres shards configured"));
    }

    private void requireActive(UserRecord user) {
        if (user.deactivatedAt() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is deactivated");
        }
    }

    private String claim(Jwt jwt, String name, String fallback) {
        String value = jwt.getClaimAsString(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String requiredEmail(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        return email;
    }

    private String resolveDisplayName(Jwt jwt, String username, String email, String keycloakId) {
        String name = trimToNull(jwt.getClaimAsString("name"));
        if (name != null) {
            return name;
        }
        String givenName = trimToNull(jwt.getClaimAsString("given_name"));
        String familyName = trimToNull(jwt.getClaimAsString("family_name"));
        if (givenName != null || familyName != null) {
            String fullName = ((givenName == null ? "" : givenName) + " " + (familyName == null ? "" : familyName)).trim();
            if (!fullName.isBlank()) {
                return fullName;
            }
        }
        String preferredUsername = trimToNull(jwt.getClaimAsString("preferred_username"));
        if (preferredUsername != null) {
            return preferredUsername;
        }
        if (email != null) {
            int at = email.indexOf('@');
            if (at > 0) {
                return email.substring(0, at);
            }
            if (!email.isBlank()) {
                return email;
            }
        }
        return username == null || username.isBlank() ? keycloakId : username;
    }

    private String resolveRole(Jwt jwt) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> map) {
            Object roles = map.get("roles");
            if (roles instanceof Collection<?> collection) {
                if (collection.contains("ADMIN")) {
                    return "ADMIN";
                }
                if (collection.contains("OPERATIONS")) {
                    return "OPERATIONS";
                }
            }
        }
        Object resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess instanceof Map<?, ?> resources) {
            for (Object value : resources.values()) {
                if (value instanceof Map<?, ?> access) {
                    Object roles = access.get("roles");
                    if (roles instanceof Collection<?> collection) {
                        if (collection.contains("ADMIN")) {
                            return "ADMIN";
                        }
                        if (collection.contains("OPERATIONS")) {
                            return "OPERATIONS";
                        }
                    }
                }
            }
        }
        return "USER";
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private UserRecord mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new UserRecord(
                rs.getObject("id", UUID.class),
                rs.getString("keycloak_id"),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getString("username"),
                rs.getString("role"),
                rs.getObject("quota_bytes", Long.class),
                rs.getLong("used_bytes"),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("deactivated_at", java.time.OffsetDateTime.class));
    }

    private FolderRecord mapFolder(ResultSet rs, int rowNum) throws SQLException {
        return new FolderRecord(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getObject("owner_id", UUID.class),
                rs.getObject("parent_id", UUID.class),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class),
                rs.getObject("deleted_at", java.time.OffsetDateTime.class));
    }
}
