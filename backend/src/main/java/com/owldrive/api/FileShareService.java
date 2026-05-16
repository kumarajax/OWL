package com.owldrive.api;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FileShareService {
    private static final SecureRandom secureRandom = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final ShardJdbcRegistry shardJdbcRegistry;
    private final ProvisioningService provisioningService;
    private final FolderService folderService;
    private final ObjectStorageService objectStorageService;

    public FileShareService(
            JdbcTemplate jdbc,
            ShardJdbcRegistry shardJdbcRegistry,
            ProvisioningService provisioningService,
            FolderService folderService,
            ObjectStorageService objectStorageService) {
        this.jdbc = jdbc;
        this.shardJdbcRegistry = shardJdbcRegistry;
        this.provisioningService = provisioningService;
        this.folderService = folderService;
        this.objectStorageService = objectStorageService;
    }

    @Transactional
    public FileShareRecord create(Jwt jwt, UUID fileId, CreateFileShareRequest request, String publicUrlBase) {
        UserRecord user = provisioningService.ensureUser(jwt);
        JdbcTemplate shardJdbc = currentJdbc();
        FileRecord file = requireOwnedActiveFile(shardJdbc, user, fileId);
        Integer expiresInDays = request == null ? null : request.expiresInDays();
        if (expiresInDays != null && (expiresInDays < 1 || expiresInDays > 365)) {
            throw badRequest("expiresInDays must be between 1 and 365");
        }

        String token = randomToken();
        String tokenHash = sha256Hex(token);
        OffsetDateTime expiresAt = expiresInDays == null ? null : OffsetDateTime.now().plusDays(expiresInDays);
        return shardJdbc.queryForObject(
                """
                INSERT INTO file_shares (file_id, owner_id, token_hash, expires_at)
                VALUES (?, ?, ?, ?)
                RETURNING id, file_id, owner_id, expires_at, revoked_at, download_count, last_downloaded_at, created_at
                """,
                (rs, rowNum) -> mapShare(rs, publicUrlBase, token),
                file.id(),
                user.id(),
                tokenHash,
                expiresAt);
    }

    @Transactional(readOnly = true)
    public List<FileShareRecord> list(Jwt jwt, UUID fileId) {
        UserRecord user = provisioningService.ensureUser(jwt);
        JdbcTemplate shardJdbc = currentJdbc();
        FileRecord file = requireOwnedActiveFile(shardJdbc, user, fileId);
        return shardJdbc.query(
                """
                SELECT id, file_id, owner_id, expires_at, revoked_at, download_count, last_downloaded_at, created_at
                FROM file_shares
                WHERE file_id = ? AND owner_id = ?
                ORDER BY created_at DESC
                """,
                (rs, rowNum) -> mapShare(rs, null, null),
                file.id(),
                user.id());
    }

    @Transactional
    public void revoke(Jwt jwt, UUID fileId, UUID shareId) {
        UserRecord user = provisioningService.ensureUser(jwt);
        JdbcTemplate shardJdbc = currentJdbc();
        FileRecord file = requireOwnedActiveFile(shardJdbc, user, fileId);
        int updated = shardJdbc.update(
                """
                UPDATE file_shares
                SET revoked_at = COALESCE(revoked_at, now())
                WHERE id = ? AND file_id = ? AND owner_id = ?
                """,
                shareId,
                file.id(),
                user.id());
        if (updated != 1) {
            throw notFound("Share not found");
        }
    }

    @Transactional
    public DownloadableFile publicDownload(String token) {
        if (token == null || token.isBlank()) {
            throw notFound("Share not found");
        }
        LocatedFileRecord located = shardJdbcRegistry.findFileByShareToken(sha256Hex(token))
                .orElseThrow(() -> notFound("Share not found"));
        ShardContext.setCurrentShard(located.shardId());
        FileRecord file = located.file();
        shardJdbcRegistry.jdbc(located.shardId()).update(
                """
                UPDATE file_shares
                SET download_count = download_count + 1,
                    last_downloaded_at = now()
                WHERE token_hash = ?
                """,
                sha256Hex(token));

        try {
            StorageDownload download = objectStorageService.download(file.storagePool(), file.storageKey());
            return new DownloadableFile(file, download.resource(), download.sizeBytes());
        } catch (java.nio.file.NoSuchFileException ex) {
            throw notFound("File bytes not found");
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to read file", ex);
        }
    }

    private FileRecord requireOwnedActiveFile(UserRecord user, UUID fileId) {
        return requireOwnedActiveFile(currentJdbc(), user, fileId);
    }

    private FileRecord requireOwnedActiveFile(JdbcTemplate shardJdbc, UserRecord user, UUID fileId) {
        FileRecord file = shardJdbc.query(
                """
                SELECT id, owner_id, parent_folder_id, original_name, storage_pool, storage_key, content_type,
                       size_bytes, checksum_sha256, created_at, updated_at, deleted_at
                FROM files
                WHERE id = ?
                """,
                this::mapFile,
                fileId).stream().findFirst().orElseThrow(() -> notFound("File not found"));
        if (file.deletedAt() != null) {
            throw notFound("File not found");
        }
        if (!file.ownerId().equals(user.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "File belongs to another user");
        }
        folderService.requireOwnedActiveFolder(shardJdbc, user, file.parentFolderId());
        return file;
    }

    private FileShareRecord mapShare(ResultSet rs, String publicUrlBase, String token) throws SQLException {
        String shareUrl = token == null ? null : publicUrlBase + "/api/public/shares/" + token + "/download";
        return new FileShareRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("file_id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                shareUrl,
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class),
                rs.getLong("download_count"),
                rs.getObject("last_downloaded_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private FileRecord mapFile(ResultSet rs, int rowNum) throws SQLException {
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
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("deleted_at", OffsetDateTime.class));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private JdbcTemplate currentJdbc() {
        return shardJdbcRegistry.currentOrPrimary();
    }
}
