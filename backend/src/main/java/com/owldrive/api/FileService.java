package com.owldrive.api;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
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
    private final ShardJdbcRegistry shardJdbcRegistry;
    private final ProvisioningService provisioningService;
    private final FolderService folderService;
    private final ObjectStorageService objectStorageService;
    private final long maxUploadBytes;
    private final boolean rejectEmptyFiles;
    private final Path chunkUploadRoot;

    public FileService(
            JdbcTemplate jdbc,
            ShardJdbcRegistry shardJdbcRegistry,
            ProvisioningService provisioningService,
            FolderService folderService,
            ObjectStorageService objectStorageService,
            @Value("${app.storage.max-upload-bytes:9223372036854775807}") long maxUploadBytes,
            @Value("${app.storage.reject-empty-files:true}") boolean rejectEmptyFiles,
            @Value("${app.storage.chunk-upload-root:${java.io.tmpdir}/owl-drive-uploads}") String chunkUploadRoot) {
        this.jdbc = jdbc;
        this.shardJdbcRegistry = shardJdbcRegistry;
        this.provisioningService = provisioningService;
        this.folderService = folderService;
        this.objectStorageService = objectStorageService;
        this.maxUploadBytes = maxUploadBytes;
        this.rejectEmptyFiles = rejectEmptyFiles;
        this.chunkUploadRoot = Path.of(chunkUploadRoot);
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
        FileRecord existing = findActiveFileByName(currentJdbc(), user.id(), destinationFolder.id(), uploadPath.fileName());
        if (existing != null) {
            return overwriteExistingFile(user, existing, uploadPath.fileName(), upload);
        }
        return createNewFile(user, destinationFolder.id(), uploadPath.fileName(), upload);
    }

    public void uploadChunk(
            Jwt jwt,
            String uploadId,
            int chunkIndex,
            int totalChunks,
            long totalSizeBytes,
            MultipartFile chunk) {
        UserRecord user = provisioningService.ensureUser(jwt);
        validateChunkRequest(uploadId, chunkIndex, totalChunks, totalSizeBytes, chunk);
        Path chunkPath = chunkPath(user.id(), uploadId, chunkIndex);
        try {
            Files.createDirectories(chunkPath.getParent());
            Files.deleteIfExists(chunkPath);
            chunk.transferTo(chunkPath);
            String storagePool = readUploadStoragePool(chunkPath.getParent());
            String minioUploadId = readMinioUploadId(chunkPath.getParent());
            StoredUploadPart storedPart = objectStorageService.storeMultipartUploadPart(
                    storagePool, minioUploadId, user.id(), uploadId, chunkIndex, chunk);
            writeUploadStoragePool(chunkPath.getParent(), storedPart.storagePool());
            writeMinioUploadId(chunkPath.getParent(), storedPart.minioUploadId());
            writeUploadPart(chunkPath.getParent(), chunkIndex, storedPart);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to store upload chunk", ex);
        }
    }

    @Transactional
    public FileRecord completeChunkedUpload(
            Jwt jwt,
            UUID parentFolderId,
            String uploadId,
            String fileName,
            String relativePath,
            String contentType,
            int totalChunks,
            long totalSizeBytes) {
        UserRecord user = provisioningService.ensureUser(jwt);
        if (parentFolderId == null) {
            throw badRequest("parentFolderId is required");
        }
        folderService.requireOwnedActiveFolder(user, parentFolderId);
        validateCompleteChunkedRequest(uploadId, fileName, totalChunks, totalSizeBytes);

        UploadPath uploadPath = parseUploadPath(relativePath, fileName);
        FolderRecord destinationFolder = folderService.resolveOrCreateFolderPath(user, parentFolderId, uploadPath.folderSegments());
        Path uploadDir = uploadDir(user.id(), uploadId);
        try {
            List<Path> chunks = validateAndListChunks(uploadDir, totalChunks, totalSizeBytes);
            String storagePool = readUploadStoragePool(uploadDir);
            if (storagePool == null || storagePool.isBlank()) {
                throw badRequest("Upload storage pool is missing");
            }
            String minioUploadId = readMinioUploadId(uploadDir);
            if (minioUploadId == null || minioUploadId.isBlank()) {
                throw badRequest("MinIO upload id is missing");
            }
            List<StoredUploadPart> uploadParts = readUploadParts(uploadDir, storagePool, minioUploadId, totalChunks);
            String checksumSha256 = checksumChunks(chunks);
            FileRecord existing = findActiveFileByName(currentJdbc(), user.id(), destinationFolder.id(), uploadPath.fileName());
            FileRecord uploaded;
            if (existing != null) {
                uploaded = overwriteExistingFile(
                        user, existing, uploadPath.fileName(), storagePool, minioUploadId, uploadId, uploadParts, checksumSha256, totalSizeBytes, contentType);
            } else {
                uploaded = createNewFile(
                        user, destinationFolder.id(), uploadPath.fileName(), storagePool, minioUploadId, uploadId, uploadParts, checksumSha256, totalSizeBytes, contentType);
            }
            return uploaded;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to store upload", ex);
        } finally {
            deleteDirectoryQuietly(uploadDir);
        }
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

    @Transactional(readOnly = true)
    public StorageDownload thumbnail(Jwt jwt, UUID fileId) {
        UserRecord user = provisioningService.ensureUser(jwt);
        FileRecord file = requireOwnedActiveFile(user, fileId);
        if (!supportsThumbnail(file)) {
            throw notFound("Thumbnail unavailable");
        }
        String thumbnailKey = thumbnailStorageKey(file);
        try {
            return objectStorageService.download(file.storagePool(), thumbnailKey);
        } catch (java.nio.file.NoSuchFileException ex) {
            byte[] thumbnailBytes;
            try {
                thumbnailBytes = generateThumbnailBytes(file);
            } catch (IOException thumbnailEx) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to generate thumbnail", thumbnailEx);
            }
            if (thumbnailBytes == null || thumbnailBytes.length == 0) {
                throw notFound("Thumbnail unavailable");
            }
            try {
                objectStorageService.storeBytes(file.storagePool(), file.ownerId(), file.id(), "thumbnail.jpg", thumbnailBytes, "image/jpeg");
            } catch (IOException storeEx) {
                log.warn("Unable to persist generated thumbnail for file {} at {}", file.id(), thumbnailKey, storeEx);
            }
            return new StorageDownload(new InputStreamResource(new ByteArrayInputStream(thumbnailBytes)), thumbnailBytes.length);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to read thumbnail", ex);
        }
    }

    @Transactional
    public FileRecord update(Jwt jwt, UUID fileId, Map<String, Object> request) {
        UserRecord user = provisioningService.ensureUser(jwt);
        JdbcTemplate shardJdbc = currentJdbc();
        FileRecord file = requireOwnedActiveFile(shardJdbc, user, fileId);
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
        String newOriginalName = uniqueMoveFileName(shardJdbc, user.id(), parent.id(), file.originalName());
        try {
            return shardJdbc.queryForObject(
                    """
                    UPDATE files
                    SET parent_folder_id = ?, original_name = ?, updated_at = now()
                    WHERE id = ? AND owner_id = ? AND deleted_at IS NULL
                    RETURNING id, owner_id, parent_folder_id, original_name, storage_pool, storage_key,
                              content_type, size_bytes, checksum_sha256,
                              created_at, updated_at, deleted_at
                    """,
                    this::mapFile,
                    parent.id(),
                    newOriginalName,
                    file.id(),
                    user.id());
        } catch (DataIntegrityViolationException ex) {
            String retryName = uniqueMoveFileName(currentJdbc(), user.id(), parent.id(), file.originalName());
            if (!retryName.equals(newOriginalName)) {
                try {
                    return shardJdbc.queryForObject(
                            """
                            UPDATE files
                            SET parent_folder_id = ?, original_name = ?, updated_at = now()
                            WHERE id = ? AND owner_id = ? AND deleted_at IS NULL
                            RETURNING id, owner_id, parent_folder_id, original_name, storage_pool, storage_key,
                                      content_type, size_bytes, checksum_sha256,
                                      created_at, updated_at, deleted_at
                            """,
                            this::mapFile,
                            parent.id(),
                            retryName,
                            file.id(),
                            user.id());
                } catch (DataIntegrityViolationException retryEx) {
                    throw badRequest("A file with this name already exists there");
                }
            }
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
            FileRecord existing = findActiveFileByName(currentJdbc(), user.id(), parentFolderId, originalName);
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

    private FileRecord createNewFile(UserRecord user, UUID parentFolderId, String originalName, Path path, long sizeBytes, String contentType) {
        UUID fileId = UUID.randomUUID();
        StoredFile storedFile;
        try {
            storedFile = objectStorageService.storeFile(user.id(), fileId, path, sizeBytes, contentType);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to store file", ex);
        }

        try {
            reserveUserStorage(user, storedFile.sizeBytes());
            return insertFileRecord(user, parentFolderId, fileId, originalName, contentType, storedFile);
        } catch (DataIntegrityViolationException ex) {
            FileRecord existing = findActiveFileByName(currentJdbc(), user.id(), parentFolderId, originalName);
            if (existing != null) {
                adjustUserStorage(user, -existing.sizeBytes());
                return overwriteStoredBytes(user, existing, originalName, contentType, storedFile);
            }
            deleteStoredBytesQuietly(storedFile);
            throw badRequest("A file with this name already exists here");
        } catch (ResponseStatusException ex) {
            deleteStoredBytesQuietly(storedFile);
            throw ex;
        }
    }

    private FileRecord createNewFile(
            UserRecord user,
            UUID parentFolderId,
            String originalName,
            String storagePool,
            String minioUploadId,
            String uploadId,
            List<StoredUploadPart> uploadParts,
            String checksumSha256,
            long sizeBytes,
            String contentType) {
        UUID fileId = UUID.randomUUID();
        StoredFile storedFile;
        try {
            storedFile = objectStorageService.completeMultipartUpload(
                    storagePool, minioUploadId, user.id(), uploadId, uploadParts, checksumSha256, sizeBytes);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to store file", ex);
        }

        try {
            reserveUserStorage(user, storedFile.sizeBytes());
            return insertFileRecord(user, parentFolderId, fileId, originalName, contentType, storedFile);
        } catch (DataIntegrityViolationException ex) {
            FileRecord existing = findActiveFileByName(currentJdbc(), user.id(), parentFolderId, originalName);
            if (existing != null) {
                adjustUserStorage(user, -existing.sizeBytes());
                return overwriteStoredBytes(user, existing, originalName, contentType, storedFile);
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

    private FileRecord overwriteExistingFile(UserRecord user, FileRecord existingFile, String originalName, Path path, long sizeBytes, String contentType) {
        UUID fileId = UUID.randomUUID();
        StoredFile storedFile;
        try {
            storedFile = objectStorageService.storeFile(user.id(), fileId, path, sizeBytes, contentType);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to store file", ex);
        }
        try {
            adjustUserStorage(user, storedFile.sizeBytes() - existingFile.sizeBytes());
            return overwriteStoredBytes(user, existingFile, originalName, contentType, storedFile);
        } catch (ResponseStatusException ex) {
            deleteStoredBytesQuietly(storedFile);
            throw ex;
        }
    }

    private FileRecord overwriteExistingFile(
            UserRecord user,
            FileRecord existingFile,
            String originalName,
            String storagePool,
            String minioUploadId,
            String uploadId,
            List<StoredUploadPart> uploadParts,
            String checksumSha256,
            long sizeBytes,
            String contentType) {
        UUID fileId = UUID.randomUUID();
        StoredFile storedFile;
        try {
            storedFile = objectStorageService.completeMultipartUpload(
                    storagePool, minioUploadId, user.id(), uploadId, uploadParts, checksumSha256, sizeBytes);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to store file", ex);
        }
        try {
            adjustUserStorage(user, storedFile.sizeBytes() - existingFile.sizeBytes());
            return overwriteStoredBytes(user, existingFile, originalName, contentType, storedFile);
        } catch (ResponseStatusException ex) {
            deleteStoredBytesQuietly(storedFile);
            throw ex;
        }
    }

    private FileRecord overwriteStoredBytes(UserRecord user, FileRecord existingFile, String originalName, MultipartFile upload, StoredFile storedFile) {
        return overwriteStoredBytes(user, existingFile, originalName, contentType(upload), storedFile);
    }

    private FileRecord overwriteStoredBytes(UserRecord user, FileRecord existingFile, String originalName, String contentType, StoredFile storedFile) {
        JdbcTemplate shardJdbc = currentJdbc();
        try {
            FileRecord updated = shardJdbc.queryForObject(
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
                    normalizeContentType(contentType),
                    storedFile.sizeBytes(),
                    storedFile.checksumSha256(),
                    originalName,
                    existingFile.id(),
                    user.id());
            deleteStoredArtifactsAfterCommit(existingFile);
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
        return insertFileRecord(user, parentFolderId, fileId, originalName, contentType(upload), storedFile);
    }

    private FileRecord insertFileRecord(UserRecord user, UUID parentFolderId, UUID fileId, String originalName, String contentType, StoredFile storedFile) {
        JdbcTemplate shardJdbc = currentJdbc();
        try {
            return shardJdbc.queryForObject(
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
                    normalizeContentType(contentType),
                    storedFile.sizeBytes(),
                    storedFile.checksumSha256());
        } catch (DataIntegrityViolationException ex) {
            throw ex;
        }
    }

    @Transactional
    public void delete(Jwt jwt, UUID fileId) {
        UserRecord user = provisioningService.ensureUser(jwt);
        JdbcTemplate shardJdbc = currentJdbc();
        FileRecord file = requireOwnedActiveFile(shardJdbc, user, fileId);
        shardJdbc.update(
                """
                UPDATE files
                SET deleted_at = now(), updated_at = now()
                WHERE id = ? AND owner_id = ? AND deleted_at IS NULL
                """,
                file.id(),
                user.id());
        releaseUserStorage(user, file.sizeBytes());
        deleteStoredArtifactsAfterCommit(file);
    }

    private void reserveUserStorage(UserRecord user, long sizeBytes) {
        adjustUserStorage(user, sizeBytes);
    }

    private void adjustUserStorage(UserRecord user, long deltaBytes) {
        int updated = currentJdbc().update(
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
        currentJdbc().update(
                """
                UPDATE users
                SET used_bytes = GREATEST(used_bytes - ?, 0)
                WHERE id = ?
                """,
                sizeBytes,
                user.id());
    }

    private void deleteStoredArtifactsAfterCommit(FileRecord file) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    objectStorageService.deleteStorageKey(file.storagePool(), file.storageKey());
                } catch (IOException ex) {
                    log.warn("Unable to delete stored bytes for file {} at {} in pool {}", file.id(), file.storageKey(), file.storagePool(), ex);
                }
                try {
                    objectStorageService.deleteStorageKey(file.storagePool(), thumbnailStorageKey(file));
                } catch (IOException ex) {
                    log.warn("Unable to delete stored thumbnail for file {} in pool {}", file.id(), file.storagePool(), ex);
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

    private boolean supportsThumbnail(FileRecord file) {
        return mediaKind(file) != null;
    }

    private byte[] generateThumbnailBytes(FileRecord file) throws IOException {
        MediaKind mediaKind = mediaKind(file);
        if (mediaKind == null) {
            return null;
        }

        StorageDownload download = objectStorageService.download(file.storagePool(), file.storageKey());
        Path source = Files.createTempFile("owl-thumb-source-", mediaKind == MediaKind.IMAGE ? ".img" : ".vid");
        Path frame = null;
        try (InputStream input = download.resource().getInputStream()) {
            Files.copy(input, source, StandardCopyOption.REPLACE_EXISTING);
            if (mediaKind == MediaKind.IMAGE) {
                return encodeJpeg(resizeImage(ImageIO.read(source.toFile())));
            }

            frame = Files.createTempFile("owl-thumb-frame-", ".jpg");
            byte[] thumbnail = extractVideoThumbnail(source, frame);
            if (thumbnail != null) {
                return thumbnail;
            }
            return null;
        } finally {
            try {
                Files.deleteIfExists(source);
            } catch (IOException ex) {
                log.debug("Unable to delete thumbnail source temp file {}", source, ex);
            }
            if (frame != null) {
                try {
                    Files.deleteIfExists(frame);
                } catch (IOException ex) {
                    log.debug("Unable to delete thumbnail frame temp file {}", frame, ex);
                }
            }
        }
    }

    private byte[] extractVideoThumbnail(Path source, Path frame) {
        List<String> seekPoints = List.of("00:00:01.000", "00:00:00.500", "00:00:00.100", "00:00:00.000");
        for (String seekPoint : seekPoints) {
            try {
                Files.deleteIfExists(frame);
                Process process = new ProcessBuilder(
                        "ffmpeg",
                        "-hide_banner",
                        "-loglevel",
                        "error",
                        "-y",
                        "-ss",
                        seekPoint,
                        "-i",
                        source.toString(),
                        "-frames:v",
                        "1",
                        frame.toString())
                        .redirectErrorStream(true)
                        .start();
                boolean finished = process.waitFor(30, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    continue;
                }
                if (process.exitValue() != 0 || !Files.exists(frame) || Files.size(frame) == 0) {
                    continue;
                }
                BufferedImage image = ImageIO.read(frame.toFile());
                if (image == null) {
                    continue;
                }
                return encodeJpeg(resizeImage(image));
            } catch (IOException ex) {
                log.debug("Unable to generate video thumbnail from {}", source, ex);
                continue;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private BufferedImage resizeImage(BufferedImage source) {
        if (source == null) {
            return null;
        }
        int maxDimension = 480;
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        double scale = Math.min(1.0d, (double) maxDimension / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        BufferedImage output = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setComposite(AlphaComposite.Src);
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, targetWidth, targetHeight);
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        graphics.dispose();
        return output;
    }

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        if (image == null) {
            return null;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpg", output)) {
            return null;
        }
        return output.toByteArray();
    }

    private MediaKind mediaKind(FileRecord file) {
        String contentType = file.contentType() == null ? "" : file.contentType().toLowerCase();
        if (contentType.startsWith("image/")) {
            return MediaKind.IMAGE;
        }
        if (contentType.startsWith("video/")) {
            return MediaKind.VIDEO;
        }
        String name = file.originalName() == null ? "" : file.originalName().toLowerCase();
        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".gif")
                || name.endsWith(".webp") || name.endsWith(".bmp") || name.endsWith(".avif")
                || name.endsWith(".tif") || name.endsWith(".tiff")) {
            return MediaKind.IMAGE;
        }
        if (name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".webm") || name.endsWith(".m4v")
                || name.endsWith(".avi") || name.endsWith(".mkv") || name.endsWith(".ogv") || name.endsWith(".ogg")) {
            return MediaKind.VIDEO;
        }
        return null;
    }

    private String thumbnailStorageKey(FileRecord file) {
        return file.ownerId() + "/" + file.id() + "/thumbnail.jpg";
    }

    private void rejectDuplicateFileName(JdbcTemplate shardJdbc, UUID ownerId, UUID parentFolderId, String originalName) {
        if (findActiveFileByName(shardJdbc, ownerId, parentFolderId, originalName) != null) {
            throw badRequest("A file with this name already exists here");
        }
    }

    private String uniqueMoveFileName(JdbcTemplate shardJdbc, UUID ownerId, UUID parentFolderId, String originalName) {
        String candidate = originalName;
        int suffix = 1;
        while (findActiveFileByName(shardJdbc, ownerId, parentFolderId, candidate) != null) {
            candidate = suffixFileName(originalName, suffix);
            suffix += 1;
        }
        return candidate;
    }

    private String suffixFileName(String originalName, int suffix) {
        String suffixText = "_" + suffix;
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex <= 0) {
            return trimToLength(originalName, 255 - suffixText.length()) + suffixText;
        }
        String base = originalName.substring(0, dotIndex);
        String extension = originalName.substring(dotIndex);
        int maxBaseLength = Math.max(1, 255 - suffixText.length() - extension.length());
        return trimToLength(base, maxBaseLength) + suffixText + extension;
    }

    private String trimToLength(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength));
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

    private FileRecord findActiveFileByName(JdbcTemplate shardJdbc, UUID ownerId, UUID parentFolderId, String originalName) {
        return shardJdbc.query(
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

    private JdbcTemplate currentJdbc() {
        return shardJdbcRegistry.currentOrPrimary();
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

    private void validateChunkRequest(String uploadId, int chunkIndex, int totalChunks, long totalSizeBytes, MultipartFile chunk) {
        validateUploadId(uploadId);
        validateChunkCounts(totalChunks, totalSizeBytes);
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw badRequest("chunkIndex is out of range");
        }
        if (chunk == null || chunk.isEmpty()) {
            throw badRequest("chunk is required");
        }
        if (chunk.getSize() > maxUploadBytes) {
            throw badRequest("Chunk exceeds max upload size");
        }
    }

    private void validateCompleteChunkedRequest(String uploadId, String fileName, int totalChunks, long totalSizeBytes) {
        validateUploadId(uploadId);
        validateChunkCounts(totalChunks, totalSizeBytes);
        sanitizeDisplayName(fileName);
    }

    private void validateUploadId(String uploadId) {
        if (uploadId == null || uploadId.isBlank()) {
            throw badRequest("uploadId is required");
        }
        try {
            UUID.fromString(uploadId);
        } catch (IllegalArgumentException ex) {
            throw badRequest("uploadId must be a UUID string");
        }
    }

    private void validateChunkCounts(int totalChunks, long totalSizeBytes) {
        if (totalChunks <= 0) {
            throw badRequest("totalChunks must be positive");
        }
        if (totalSizeBytes <= 0) {
            throw badRequest("totalSizeBytes must be positive");
        }
        if (totalSizeBytes > maxUploadBytes) {
            throw badRequest("File exceeds max upload size");
        }
    }

    private List<Path> validateAndListChunks(Path uploadDir, int totalChunks, long totalSizeBytes) throws IOException {
        Files.createDirectories(uploadDir);
        List<Path> chunks = new ArrayList<>();
        long chunkBytes = 0;
        for (int index = 0; index < totalChunks; index += 1) {
            Path part = uploadDir.resolve(chunkFileName(index));
            if (!Files.isRegularFile(part)) {
                throw badRequest("Missing upload chunk " + index);
            }
            long partSize = Files.size(part);
            chunkBytes += partSize;
            chunks.add(part);
        }
        if (chunkBytes != totalSizeBytes) {
            throw badRequest("Upload size mismatch");
        }
        return chunks;
    }

    private String readUploadStoragePool(Path uploadDir) throws IOException {
        Path poolPath = uploadDir.resolve("storage-pool");
        if (!Files.isRegularFile(poolPath)) {
            return null;
        }
        String storagePool = Files.readString(poolPath).trim();
        return storagePool.isBlank() ? null : storagePool;
    }

    private void writeUploadStoragePool(Path uploadDir, String storagePool) throws IOException {
        if (storagePool == null || storagePool.isBlank()) {
            throw new IOException("Upload storage pool is missing");
        }
        Files.writeString(uploadDir.resolve("storage-pool"), storagePool);
    }

    private String readMinioUploadId(Path uploadDir) throws IOException {
        Path uploadIdPath = uploadDir.resolve("minio-upload-id");
        if (!Files.isRegularFile(uploadIdPath)) {
            return null;
        }
        String minioUploadId = Files.readString(uploadIdPath).trim();
        return minioUploadId.isBlank() ? null : minioUploadId;
    }

    private void writeMinioUploadId(Path uploadDir, String minioUploadId) throws IOException {
        if (minioUploadId == null || minioUploadId.isBlank()) {
            throw new IOException("MinIO upload id is missing");
        }
        Files.writeString(uploadDir.resolve("minio-upload-id"), minioUploadId);
    }

    private void writeUploadPart(Path uploadDir, int chunkIndex, StoredUploadPart storedPart) throws IOException {
        Files.writeString(uploadDir.resolve(uploadPartFileName(chunkIndex)), storedPart.partNumber() + "\n" + storedPart.etag());
    }

    private List<StoredUploadPart> readUploadParts(Path uploadDir, String storagePool, String minioUploadId, int totalChunks) throws IOException {
        List<StoredUploadPart> uploadParts = new ArrayList<>();
        for (int index = 0; index < totalChunks; index += 1) {
            Path partPath = uploadDir.resolve(uploadPartFileName(index));
            if (!Files.isRegularFile(partPath)) {
                throw badRequest("Missing stored upload part " + index);
            }
            List<String> lines = Files.readAllLines(partPath);
            if (lines.size() < 2) {
                throw badRequest("Invalid stored upload part " + index);
            }
            int partNumber;
            try {
                partNumber = Integer.parseInt(lines.get(0).trim());
            } catch (NumberFormatException ex) {
                throw badRequest("Invalid stored upload part " + index);
            }
            String etag = lines.get(1).trim();
            if (etag.isBlank()) {
                throw badRequest("Invalid stored upload part " + index);
            }
            uploadParts.add(new StoredUploadPart(storagePool, minioUploadId, partNumber, etag));
        }
        return uploadParts;
    }

    private String checksumChunks(List<Path> chunks) throws IOException {
        MessageDigest digest = sha256Digest();
        for (Path chunk : chunks) {
            try (InputStream input = new DigestInputStream(Files.newInputStream(chunk), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String uploadPartFileName(int chunkIndex) {
        return chunkFileName(chunkIndex) + ".etag";
    }

    private Path chunkPath(UUID userId, String uploadId, int chunkIndex) {
        return uploadDir(userId, uploadId).resolve(chunkFileName(chunkIndex));
    }

    private Path uploadDir(UUID userId, String uploadId) {
        return chunkUploadRoot.resolve(userId.toString()).resolve(uploadId).normalize();
    }

    private String chunkFileName(int chunkIndex) {
        return String.format("%08d.part", chunkIndex);
    }

    private void deleteDirectoryQuietly(Path directory) {
        try {
            if (!Files.exists(directory)) {
                return;
            }
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ex) {
                        log.warn("Unable to delete upload temp path {}", path, ex);
                    }
                });
            }
        } catch (IOException ex) {
            log.warn("Unable to clean upload temp directory {}", directory, ex);
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
        return normalizeContentType(upload.getContentType());
    }

    private String normalizeContentType(String contentType) {
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

    private enum MediaKind {
        IMAGE,
        VIDEO
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
