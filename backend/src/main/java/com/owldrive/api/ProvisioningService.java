package com.owldrive.api;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProvisioningService {
    private static final long DEFAULT_USER_QUOTA_BYTES = 2L * 1024 * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final UserCapacityService userCapacityService;

    public ProvisioningService(JdbcTemplate jdbc, UserCapacityService userCapacityService) {
        this.jdbc = jdbc;
        this.userCapacityService = userCapacityService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserRecord ensureUser(Jwt jwt) {
        UserRecord user = currentUser(jwt);
        requireActive(user);
        return user;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserRecord currentUser(Jwt jwt) {
        String keycloakId = jwt.getSubject();
        String username = claim(jwt, "preferred_username", keycloakId);
        String email = requiredEmail(jwt);
        String role = resolveRole(jwt);
        Long quotaBytes = "ADMIN".equals(role) ? null : DEFAULT_USER_QUOTA_BYTES;

        Optional<UserRecord> existing = findUserByKeycloakId(keycloakId);
        if (existing.isPresent()) {
            if (existing.get().deactivatedAt() != null) {
                return existing.get();
            }
            return updateUser(existing.get().id(), keycloakId, email, username, role, quotaBytes);
        }

        Optional<UserRecord> existingByEmail = findSingleUserByVerifiedEmail(jwt, email);
        if (existingByEmail.isPresent()) {
            if (existingByEmail.get().deactivatedAt() != null) {
                return existingByEmail.get();
            }
            return updateUser(existingByEmail.get().id(), keycloakId, email, username, role, quotaBytes);
        }

        userCapacityService.requireAvailableSlot();
        UserRecord user = jdbc.queryForObject(
                """
                INSERT INTO users (keycloak_id, email, username, role, quota_bytes, used_bytes)
                VALUES (?, ?, ?, ?, ?, 0)
                RETURNING id, keycloak_id, email, username, role, quota_bytes, used_bytes, created_at, deactivated_at
                """,
                this::mapUser,
                keycloakId,
                email,
                username,
                role,
                quotaBytes);
        ensureRootFolder(user);
        return user;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserRecord activateUser(Jwt jwt) {
        String keycloakId = jwt.getSubject();
        String username = claim(jwt, "preferred_username", keycloakId);
        String email = requiredEmail(jwt);
        String role = resolveRole(jwt);
        Long quotaBytes = "ADMIN".equals(role) ? null : DEFAULT_USER_QUOTA_BYTES;

        Optional<UserRecord> existing = findUserByKeycloakId(keycloakId);
        if (existing.isEmpty()) {
            existing = findSingleUserByVerifiedEmail(jwt, email);
        }
        if (existing.isEmpty()) {
            return ensureUser(jwt);
        }

        if (existing.get().deactivatedAt() != null) {
            userCapacityService.requireAvailableSlot();
        }
        UserRecord user = jdbc.queryForObject(
                """
                UPDATE users
                SET keycloak_id = ?, email = ?, username = ?, role = ?, quota_bytes = ?, used_bytes = 0, deactivated_at = NULL
                WHERE id = ?
                RETURNING id, keycloak_id, email, username, role, quota_bytes, used_bytes, created_at, deactivated_at
                """,
                this::mapUser,
                keycloakId,
                email,
                username,
                role,
                quotaBytes,
                existing.get().id());
        ensureRootFolder(user);
        return user;
    }

    private UserRecord updateUser(UUID id, String keycloakId, String email, String username, String role, Long quotaBytes) {
        UserRecord user = jdbc.queryForObject(
                """
                UPDATE users
                SET keycloak_id = ?, email = ?, username = ?, role = ?, quota_bytes = ?
                WHERE id = ? AND deactivated_at IS NULL
                RETURNING id, keycloak_id, email, username, role, quota_bytes, used_bytes, created_at, deactivated_at
                """,
                this::mapUser,
                keycloakId,
                email,
                username,
                role,
                quotaBytes,
                id);
        ensureRootFolder(user);
        return user;
    }

    private void requireActive(UserRecord user) {
        if (user.deactivatedAt() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is deactivated");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FolderRecord ensureRootFolder(UserRecord user) {
        Optional<FolderRecord> existing = findRootFolder(user.id());
        if (existing.isPresent()) {
            return existing.get();
        }
        return jdbc.queryForObject(
                """
                INSERT INTO folders (name, owner_id, parent_id)
                VALUES ('My Drive', ?, NULL)
                RETURNING id, name, owner_id, parent_id, created_at, updated_at, deleted_at
                """,
                this::mapFolder,
                user.id());
    }

    private Optional<UserRecord> findUserByKeycloakId(String keycloakId) {
        return jdbc.query(
                """
                SELECT id, keycloak_id, email, username, role, quota_bytes, used_bytes, created_at, deactivated_at
                FROM users
                WHERE keycloak_id = ?
                """,
                this::mapUser,
                keycloakId).stream().findFirst();
    }

    private Optional<UserRecord> findSingleUserByVerifiedEmail(Jwt jwt, String email) {
        Boolean emailVerified = jwt.getClaimAsString("email_verified") == null
                ? jwt.getClaim("email_verified")
                : Boolean.valueOf(jwt.getClaimAsString("email_verified"));
        if (!Boolean.TRUE.equals(emailVerified)) {
            return Optional.empty();
        }
        var matches = jdbc.query(
                """
                SELECT id, keycloak_id, email, username, role, quota_bytes, used_bytes, created_at, deactivated_at
                FROM users
                WHERE lower(email) = lower(?)
                """,
                this::mapUser,
                email);
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private Optional<FolderRecord> findRootFolder(UUID ownerId) {
        return jdbc.query(
                """
                SELECT id, name, owner_id, parent_id, created_at, updated_at, deleted_at
                FROM folders
                WHERE owner_id = ? AND parent_id IS NULL AND deleted_at IS NULL
                """,
                this::mapFolder,
                ownerId).stream().findFirst();
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

    private String resolveRole(Jwt jwt) {
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (realmAccess instanceof Map<?, ?> access) {
            Object roles = access.get("roles");
            if (roles instanceof Collection<?> values) {
                var roleNames = values.stream().map(String::valueOf).toList();
                boolean admin = roleNames.stream().anyMatch(role -> role.equalsIgnoreCase("admin"));
                if (admin) {
                    return "ADMIN";
                }
                boolean operations = roleNames.stream().anyMatch(role -> role.equalsIgnoreCase("operations"));
                if (operations) {
                    return "OPERATIONS";
                }
            }
        }
        return "USER";
    }

    private UserRecord mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new UserRecord(
                rs.getObject("id", UUID.class),
                rs.getString("keycloak_id"),
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
