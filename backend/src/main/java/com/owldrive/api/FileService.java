package com.owldrive.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class FileService {
    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final JdbcTemplate jdbc;
    private final ProvisioningService provisioningService;
    private final FolderService folderService;
    private final LocalStorageService localStorageService;
    private final long maxUploadBytes;
    private final boolean rejectEmptyFiles;

    public FileService(
            JdbcTemplate jdbc,
            ProvisioningService provisioningService,
            FolderService folderService,
            LocalStorageService localStorageService,
            @Value("${app.storage.max-upload-bytes:26214400}") long maxUploadBytes,
            @Value("${app.storage.reject-empty-files:true}") boolean rejectEmptyFiles) {
        this.jdbc = jdbc;
        this.provisioningService = provisioningService;
        this.folderService = folderService;
        this.localStorageService = localStorageService;
        this.maxUploadBytes = maxUploadBytes;
        this.rejectEmptyFiles = rejectEmptyFiles;
    }

    @Transactional
    public FileRecord upload(Jwt jwt, UUID parentFolderId, MultipartFile upload) {
        UserRecord user = provisioningService.ensureUser(jwt);
        if (parentFolderId == null) {
            throw badRequest("parentFolderId is required");
        }
        folderService.requireOwnedActiveFolder(user, parentFolderId);
        validateUpload(upload);

        String originalName = sanitizeDisplayName(upload.getOriginalFilename());
        rejectDuplicateFileName(user.id(), parentFolderId, originalName);

        UUID fileId = UUID.randomUUID();
        StoredFile storedFile;
        try {
            storedFile = localStorageService.store(user.id(), fileId, upload);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to store file", ex);
        }

        try {
            return jdbc.queryForObject(
                    """
                    INSERT INTO files (
                      id, owner_id, parent_folder_id, original_name, storage_key,
                      content_type, size_bytes, checksum_sha256
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id, owner_id, parent_folder_id, original_name, storage_key,
                              content_type, size_bytes, checksum_sha256,
                              created_at, updated_at, deleted_at
                    """,
                    this::mapFile,
                    fileId,
                    user.id(),
                    parentFolderId,
                    originalName,
                    storedFile.storageKey(),
                    contentType(upload),
                    storedFile.sizeBytes(),
                    storedFile.checksumSha256());
        } catch (DataIntegrityViolationException ex) {
            throw badRequest("A file with this name already exists here");
        }
    }

    @Transactional(readOnly = true)
    public DownloadableFile download(Jwt jwt, UUID fileId) {
        UserRecord user = provisioningService.ensureUser(jwt);
        FileRecord file = requireOwnedActiveFile(user, fileId);
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

    @Transactional
    public void delete(Jwt jwt, UUID fileId) {
        UserRecord user = provisioningService.ensureUser(jwt);
        FileRecord file = requireOwnedActiveFile(user, fileId);
        jdbc.update(
                """
                UPDATE files
                SET deleted_at = now(), updated_at = now()
                WHERE id = ? AND owner_id = ? AND deleted_at IS NULL
                """,
                file.id(),
                user.id());
        deleteStoredBytesAfterCommit(file);
    }

    private void deleteStoredBytesAfterCommit(FileRecord file) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    localStorageService.deleteStorageKey(file.storageKey());
                } catch (IOException ex) {
                    log.warn("Unable to delete stored bytes for file {} at {}", file.id(), file.storageKey(), ex);
                }
            }
        });
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

    private void validateUpload(MultipartFile upload) {
        if (upload == null) {
            throw badRequest("file is required");
        }
        if (rejectEmptyFiles && upload.getSize() == 0) {
            throw badRequest("Empty files are not allowed");
        }
        if (upload.getSize() > maxUploadBytes) {
            throw badRequest("File exceeds max upload size");
        }
        sanitizeDisplayName(upload.getOriginalFilename());
    }

    private void rejectDuplicateFileName(UUID ownerId, UUID parentFolderId, String originalName) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM files
                WHERE owner_id = ?
                  AND parent_folder_id = ?
                  AND deleted_at IS NULL
                  AND lower(original_name) = lower(?)
                """,
                Integer.class,
                ownerId,
                parentFolderId,
                originalName);
        if (count != null && count > 0) {
            throw badRequest("A file with this name already exists here");
        }
    }

    private String sanitizeDisplayName(String rawName) {
        String name = rawName == null ? "download" : rawName.replace("\\", "/");
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\r\\n\\t]", " ").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) {
            throw badRequest("Filename is required");
        }
        if (name.length() > 255) {
            throw badRequest("Filename must be 255 characters or fewer");
        }
        return name;
    }

    private String contentType(MultipartFile upload) {
        String contentType = upload.getContentType();
        return contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
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
}
