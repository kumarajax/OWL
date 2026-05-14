package com.owldrive.api;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
    private final ObjectStorageService objectStorageService;
    private final long maxUploadBytes;
    private final boolean rejectEmptyFiles;

    public FileService(
            JdbcTemplate jdbc,
            ProvisioningService provisioningService,
            FolderService folderService,
            ObjectStorageService objectStorageService,
            @Value("${app.storage.max-upload-bytes:1073741824}") long maxUploadBytes,
            @Value("${app.storage.reject-empty-files:true}") boolean rejectEmptyFiles) {
        this.jdbc = jdbc;
        this.provisioningService = provisioningService;
        this.folderService = folderService;
        this.objectStorageService = objectStorageService;
        this.maxUploadBytes = maxUploadBytes;
        this.rejectEmptyFiles = rejectEmptyFiles;
    }

    @Transactional
    public List<FileRecord> upload(Jwt jwt, UUID parentFolderId, List<MultipartFile> uploads, String relativePath) {
        if (uploads == null || uploads.isEmpty()) {
            throw badRequest("file is required");
        }
        UserRecord user = provisioningService.ensureUser(jwt);
        if (parentFolderId == null) {
            throw badRequest("parentFolderId is required");
        }
        folderService.requireOwnedActiveFolder(user, parentFolderId);
        List<FileRecord> createdFiles = new ArrayList<>();
        for (int index = 0; index < uploads.size(); index += 1) {
            MultipartFile upload = uploads.get(index);
            createdFiles.add(uploadSingle(user, parentFolderId, upload, relativePath));
        }
        return createdFiles;
    }

    private FileRecord uploadSingle(UserRecord user, UUID parentFolderId, MultipartFile upload, String relativePath) {
        validateUpload(upload);
        UploadPath uploadPath = parseUploadPath(relativePath, upload.getOriginalFilename());
        FolderRecord destinationFolder = folderService.resolveOrCreateFolderPath(user, parentFolderId, uploadPath.folderSegments());
        FileRecord existing = findActiveFileByName(user.id(), destinationFolder.id(), uploadPath.fileName());
        if (existing != null) {
            return overwriteExistingFile(user, existing, uploadPath.fileName(), upload);
        }
        return createNewFile(user, destinationFolder.id(), uploadPath.fileName(), upload);
    }

    @Transactional(readOnly = true)
    public DownloadableFile download(Jwt jwt, UUID fileId) {
        UserRecord user = provisioningService.ensureUser(jwt);
        FileRecord file = requireOwnedActiveFile(user, fileId);
        try {
            StorageDownload download = objectStorageService.download(file.storagePool(), file.storageKey());
            return new DownloadableFile(file, download.resource(), download.sizeBytes());
        } catch (java.nio.file.NoSuchFileException ex) {
            throw notFound("File bytes not found");
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to read file", ex);
        }
    }

    @Transactional
    public FileRecord update(Jwt jwt, UUID fileId, Map<String, Object> request) {
        UserRecord user = provisioningService.ensureUser(jwt);
        FileRecord file = requireOwnedActiveFile(user, fileId);
        if (request == null || !request.containsKey("parentFolderId")) {
            throw badRequest("parentFolderId is required");
        }
        Object rawParentFolderId = request.get("parentFolderId");
        if (!(rawParentFolderId instanceof String value) || value.isBlank()) {
            throw badRequest("parentFolderId must be a UUID string");
        }
        UUID parentFolderId = parseUuid(value, "parentFolderId");
        FolderRecord parent = folderService.requireOwnedActiveFolder(user, parentFolderId);
        if (file.parentFolderId().equals(parent.id())) {
            return file;
        }
        rejectDuplicateFileName(user.id(), parent.id(), file.originalName());
        try {
            return jdbc.queryForObject(
                    """
                    UPDATE files
                    SET parent_folder_id = ?, updated_at = now()
                    WHERE id = ? AND owner_id = ? AND deleted_at IS NULL
                    RETURNING id, owner_id, parent_folder_id, original_name, storage_pool, storage_key,
                              content_type, size_bytes, checksum_sha256,
                              created_at, updated_at, deleted_at
                    """,
                    this::mapFile,
                    parent.id(),
                    file.id(),
                    user.id());
        } catch (DataIntegrityViolationException ex) {
            throw badRequest("A file with this name already exists there");
        }
    }

    private FileRecord createNewFile(UserRecord user, UUID parentFolderId, String originalName, MultipartFile upload) {
        UUID fileId = UUID.randomUUID();
        StoredFile storedFile;
        try {
            storedFile = objectStorageService.store(user.id(), fileId, upload);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to store file", ex);
        }

        try {
            reserveUserStorage(user, storedFile.sizeBytes());
            return insertFileRecord(user, parentFolderId, fileId, originalName, upload, storedFile);
        } catch (DataIntegrityViolationException ex) {
            FileRecord existing = findActiveFileByName(user.id(), parentFolderId, originalName);
            if (existing != null) {
                adjustUserStorage(user, -existing.sizeBytes());
                return overwriteStoredBytes(user, existing, originalName, upload, storedFile);
            }
            deleteStoredBytesQuietly(storedFile);
            throw badRequest("A file with this name already exists here");
        } catch (ResponseStatusException ex) {
            deleteStoredBytesQuietly(storedFile);
            throw ex;
        }
    }

    private FileRecord overwriteExistingFile(UserRecord user, FileRecord existingFile, String originalName, MultipartFile upload) {
        UUID fileId = UUID.randomUUID();
        StoredFile storedFile;
        try {
            storedFile = objectStorageService.store(user.id(), fileId, upload);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to store file", ex);
        }
        try {
            adjustUserStorage(user, storedFile.sizeBytes() - existingFile.sizeBytes());
            return overwriteStoredBytes(user, existingFile, originalName, upload, storedFile);
        } catch (ResponseStatusException ex) {
            deleteStoredBytesQuietly(storedFile);
            throw ex;
        }
    }

    private FileRecord overwriteStoredBytes(UserRecord user, FileRecord existingFile, String originalName, MultipartFile upload, StoredFile storedFile) {
        try {
            FileRecord updated = jdbc.queryForObject(
                    """
                    UPDATE files
                    SET storage_pool = ?, storage_key = ?, content_type = ?, size_bytes = ?, checksum_sha256 = ?,
                        original_name = ?, updated_at = now()
                    WHERE id = ? AND owner_id = ? AND deleted_at IS NULL
                    RETURNING id, owner_id, parent_folder_id, original_name, storage_pool, storage_key,
                              content_type, size_bytes, checksum_sha256,
                              created_at, updated_at, deleted_at
                    """,
                    this::mapFile,
                    storedFile.storagePool(),
                    storedFile.storageKey(),
                    contentType(upload),
                    storedFile.sizeBytes(),
                    storedFile.checksumSha256(),
                    originalName,
                    existingFile.id(),
                    user.id());
            deleteStoredBytesAfterCommit(existingFile);
            return updated;
        } catch (DataIntegrityViolationException ex) {
            deleteStoredBytesQuietly(storedFile);
            throw badRequest("A file with this name already exists here");
        } catch (ResponseStatusException ex) {
            deleteStoredBytesQuietly(storedFile);
            throw ex;
        }
    }

    private FileRecord insertFileRecord(UserRecord user, UUID parentFolderId, UUID fileId, String originalName, MultipartFile upload, StoredFile storedFile) {
        try {
            return jdbc.queryForObject(
                    """
                    INSERT INTO files (
                      id, owner_id, parent_folder_id, original_name, storage_pool, storage_key,
                      content_type, size_bytes, checksum_sha256
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING id, owner_id, parent_folder_id, original_name, storage_pool, storage_key,
                              content_type, size_bytes, checksum_sha256,
                              created_at, updated_at, deleted_at
                    """,
                    this::mapFile,
                    fileId,
                    user.id(),
                    parentFolderId,
                    originalName,
                    storedFile.storagePool(),
                    storedFile.storageKey(),
                    contentType(upload),
                    storedFile.sizeBytes(),
                    storedFile.checksumSha256());
        } catch (DataIntegrityViolationException ex) {
            throw ex;
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
        releaseUserStorage(user, file.sizeBytes());
        deleteStoredBytesAfterCommit(file);
    }

    private void reserveUserStorage(UserRecord user, long sizeBytes) {
        adjustUserStorage(user, sizeBytes);
    }

    private void adjustUserStorage(UserRecord user, long deltaBytes) {
        int updated = jdbc.update(
                """
                UPDATE users
                SET used_bytes = used_bytes + ?
                WHERE id = ?
                  AND deactivated_at IS NULL
                  AND (? <= 0 OR quota_bytes IS NULL OR used_bytes + ? <= quota_bytes)
                """,
                deltaBytes,
                user.id(),
                deltaBytes,
                deltaBytes);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Storage quota exceeded");
        }
    }

    private void releaseUserStorage(UserRecord user, long sizeBytes) {
        jdbc.update(
                """
                UPDATE users
                SET used_bytes = GREATEST(used_bytes - ?, 0)
                WHERE id = ?
                """,
                sizeBytes,
                user.id());
    }

    private void deleteStoredBytesAfterCommit(FileRecord file) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    objectStorageService.deleteStorageKey(file.storagePool(), file.storageKey());
                } catch (IOException ex) {
                    log.warn("Unable to delete stored bytes for file {} at {} in pool {}", file.id(), file.storageKey(), file.storagePool(), ex);
                }
            }
        });
    }

    private void deleteStoredBytesQuietly(StoredFile storedFile) {
        try {
            objectStorageService.deleteStorageKey(storedFile.storagePool(), storedFile.storageKey());
        } catch (IOException ex) {
            log.warn("Unable to delete stored bytes after failed upload at {} in pool {}", storedFile.storageKey(), storedFile.storagePool(), ex);
        }
    }

    private FileRecord findActiveFileByName(UUID ownerId, UUID parentFolderId, String originalName) {
        return jdbc.query(
                """
                SELECT id, owner_id, parent_folder_id, original_name, storage_pool, storage_key, content_type,
                       size_bytes, checksum_sha256, created_at, updated_at, deleted_at
                FROM files
                WHERE owner_id = ?
                  AND parent_folder_id = ?
                  AND deleted_at IS NULL
                  AND lower(original_name) = lower(?)
                """,
                this::mapFile,
                ownerId,
                parentFolderId,
                originalName).stream().findFirst().orElse(null);
    }

    private void rejectDuplicateFileName(UUID ownerId, UUID parentFolderId, String originalName) {
        if (findActiveFileByName(ownerId, parentFolderId, originalName) != null) {
            throw badRequest("A file with this name already exists here");
        }
    }

    private FileRecord requireOwnedActiveFile(UserRecord user, UUID fileId) {
        FileRecord file = jdbc.query(
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

    private UploadPath parseUploadPath(String relativePath, String fallbackName) {
        String candidate = relativePath == null || relativePath.isBlank() ? fallbackName : relativePath;
        String normalized = candidate == null ? "" : candidate.replace("\\", "/");
        List<String> segments = new ArrayList<>();
        for (String rawSegment : normalized.split("/")) {
            String segment = rawSegment == null ? "" : rawSegment.trim();
            if (segment.isEmpty()) {
                continue;
            }
            if (segment.equals(".") || segment.equals("..")) {
                throw badRequest("Invalid upload path");
            }
            if (segment.length() > 255) {
                throw badRequest("Path segment must be 255 characters or fewer");
            }
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            throw badRequest("Filename is required");
        }
        String fileName = sanitizeDisplayName(segments.remove(segments.size() - 1));
        return new UploadPath(List.copyOf(segments), fileName);
    }

    private String contentType(MultipartFile upload) {
        String contentType = upload.getContentType();
        return contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
    }

    private UUID parseUuid(String value, String fieldName) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw badRequest(fieldName + " must be a UUID string");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private record UploadPath(List<String> folderSegments, String fileName) {}

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
}
