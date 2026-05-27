package com.owldrive.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public class ShardJdbcRegistry {
    private final Map<String, JdbcTemplate> shardsById;

    public ShardJdbcRegistry(Map<String, JdbcTemplate> shardsById) {
        this.shardsById = new LinkedHashMap<>(shardsById);
    }

    public Collection<String> shardIds() {
        return shardsById.keySet();
    }

    public JdbcTemplate jdbc(String shardId) {
        JdbcTemplate template = shardsById.get(shardId);
        if (template == null) {
            throw new IllegalArgumentException("Unknown shard: " + shardId);
        }
        return template;
    }

    public JdbcTemplate currentOrPrimary() {
        String shardId = ShardContext.currentShard();
        if (shardId != null && shardsById.containsKey(shardId)) {
            return shardsById.get(shardId);
        }
        return shardsById.values().stream().findFirst().orElseThrow();
    }

    public Optional<LocatedUserRecord> findUserByKeycloakId(String keycloakId) {
        for (Map.Entry<String, JdbcTemplate> entry : shardsById.entrySet()) {
            List<UserRecord> matches = entry.getValue().query(
                    """
                    SELECT id, keycloak_id, display_name, email, username, role, quota_bytes, used_bytes, created_at, deactivated_at, terms_version, terms_accepted_at
                    FROM users
                    WHERE keycloak_id = ?
                    """,
                    this::mapUser,
                    keycloakId);
            if (!matches.isEmpty()) {
                return Optional.of(new LocatedUserRecord(entry.getKey(), matches.get(0)));
            }
        }
        return Optional.empty();
    }

    public Optional<LocatedUserRecord> findSingleUserByVerifiedEmail(String email) {
        for (Map.Entry<String, JdbcTemplate> entry : shardsById.entrySet()) {
            List<UserRecord> matches = entry.getValue().query(
                    """
                    SELECT id, keycloak_id, display_name, email, username, role, quota_bytes, used_bytes, created_at, deactivated_at, terms_version, terms_accepted_at
                    FROM users
                    WHERE lower(email) = lower(?)
                    """,
                    this::mapUser,
                    email);
            if (matches.size() == 1) {
                return Optional.of(new LocatedUserRecord(entry.getKey(), matches.get(0)));
            }
            if (matches.size() > 1) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public Optional<LocatedFileRecord> findFileByShareToken(String tokenHash) {
        for (Map.Entry<String, JdbcTemplate> entry : shardsById.entrySet()) {
            List<FileRecord> matches = entry.getValue().query(
                    """
                    SELECT f.id, f.owner_id, f.parent_folder_id, f.original_name, f.storage_pool, f.storage_key,
                           f.content_type, f.size_bytes, f.checksum_sha256, f.created_at, f.updated_at, f.deleted_at
                    FROM files f
                    JOIN file_shares s ON s.file_id = f.id
                    JOIN users u ON u.id = s.owner_id
                    WHERE s.token_hash = ?
                      AND s.revoked_at IS NULL
                      AND (s.expires_at IS NULL OR s.expires_at > now())
                      AND f.deleted_at IS NULL
                      AND u.deactivated_at IS NULL
                    """,
                    this::mapFile,
                    tokenHash);
            if (!matches.isEmpty()) {
                return Optional.of(new LocatedFileRecord(entry.getKey(), matches.get(0)));
            }
        }
        return Optional.empty();
    }

    public long countActiveUsers() {
        long total = 0L;
        for (JdbcTemplate jdbc : shardsById.values()) {
            Long count = jdbc.queryForObject(
                    """
                    SELECT count(*)
                    FROM users
                    WHERE deactivated_at IS NULL
                    """,
                    Long.class);
            total += count == null ? 0L : count;
        }
        return total;
    }

    public long countActiveUsers(String shardId) {
        Long count = jdbc(shardId).queryForObject(
                """
                SELECT count(*)
                FROM users
                WHERE deactivated_at IS NULL
                """,
                Long.class);
        return count == null ? 0L : count;
    }

    public List<String> orderedShardIdsByLoad() {
        List<String> ids = new ArrayList<>(shardsById.keySet());
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String shardId : ids) {
            counts.put(shardId, countActiveUsers(shardId));
        }
        ids.sort((a, b) -> Long.compare(counts.getOrDefault(a, 0L), counts.getOrDefault(b, 0L)));
        return ids;
    }

    private UserRecord mapUser(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
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
                rs.getObject("deactivated_at", java.time.OffsetDateTime.class),
                rs.getString("terms_version"),
                rs.getObject("terms_accepted_at", java.time.OffsetDateTime.class));
    }

    private FileRecord mapFile(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new FileRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getObject("parent_folder_id", UUID.class),
                rs.getString("original_name"),
                rs.getString("storage_pool"),
                rs.getString("storage_key"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("checksum_sha256"),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class),
                rs.getObject("deleted_at", java.time.OffsetDateTime.class));
    }
}
