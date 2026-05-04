package com.owldrive.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.core.io.InputStreamResource;
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
    private final ProvisioningService provisioningService;
    private final FolderService folderService;
    private final LocalStorageService localStorageService;

    public FileShareService(
            JdbcTemplate jdbc,
            ProvisioningService provisioningService,
            FolderService folderService,
            LocalStorageService localStorageService) {
        this.jdbc = jdbc;
        this.provisioningService = provisioningService;
        this.folderService = folderService;
        this.localStorageService = localStorageService;
    }

    @Transactional
    public FileShareRecord create(Jwt jwt, UUID fileId, CreateFileShareRequest request, String publicUrlBase) {
        UserRecord user = provisioningService.ensureUser(jwt);
        FileRecord file = requireOwnedActiveFile(user, fileId);
        Integer expiresInDays = request == null ? null : request.expiresInDays();
        if (expiresInDays != null && (expiresInDays < 1 || expiresInDays > 365)) {
            throw badRequest("expiresInDays must be between 1 and 365");
        }

        String token = randomToken();
        String tokenHash = sha256Hex(token);
        OffsetDateTime expiresAt = expiresInDays == null ? null : OffsetDateTime.now().plusDays(expiresInDays);
        return jdbc.queryForObject(
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
        FileRecord file = requireOwnedActiveFile(user, fileId);
        return jdbc.query(
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
        FileRecord file = requireOwnedActiveFile(user, fileId);
        int updated = jdbc.update(
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
        FileRecord file = jdbc.query(
                """
                SELECT f.id, f.owner_id, f.parent_folder_id, f.original_name, f.storage_key, f.content_type,
                       f.size_bytes, f.checksum_sha256, f.created_at, f.updated_at, f.deleted_at
                FROM file_shares s
                JOIN files f ON f.id = s.file_id
                JOIN users u ON u.id = s.owner_id
                WHERE s.token_hash = ?
                  AND s.revoked_at IS NULL
                  AND (s.expires_at IS NULL OR s.expires_at > now())
                  AND f.deleted_at IS NULL
                  AND u.deactivated_at IS NULL
                """,
                this::mapFile,
                sha256Hex(token)).stream().findFirst().orElseThrow(() -> notFound("Share not found"));
        jdbc.update(
                """
                UPDATE file_shares
                SET download_count = download_count + 1,
                    last_downloaded_at = now()
                WHERE token_hash = ?
                """,
                sha256Hex(token));

        Path path = localStorageService.resolveStorageKey(file.storageKey());
        if (!Files.isRegularFile(path)) {
            throw notFound("File bytes not found");
        }
        try {
            return new DownloadableFile(file, new InputStreamResource(Files.newInputStream(path)), Files.size(path));
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to read file", ex);
        }
    }

    private FileRecord requireOwnedActiveFile(UserRecord user, UUID fileId) {
        FileRecord file = jdbc.query(
                """
                SELECT id, owner_id, parent_folder_id, original_name, storage_key, content_type,
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
        folderService.requireOwnedActiveFolder(user, file.parentFolderId());
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
}
