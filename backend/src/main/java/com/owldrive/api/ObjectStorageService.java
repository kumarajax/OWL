package com.owldrive.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

public interface ObjectStorageService {
    StoredFile store(UUID ownerId, UUID fileId, MultipartFile upload) throws IOException;

    StoredFile storeFile(UUID ownerId, UUID fileId, Path path, long sizeBytes, String contentType) throws IOException;

    StoredUploadPart storeMultipartUploadPart(
            String storagePool,
            String minioUploadId,
            UUID ownerId,
            String uploadId,
            int chunkIndex,
            MultipartFile chunk) throws IOException;

    StoredFile completeMultipartUpload(
            String storagePool,
            String minioUploadId,
            UUID ownerId,
            String uploadId,
            List<StoredUploadPart> parts,
            String checksumSha256,
            long sizeBytes) throws IOException;

    void abortMultipartUpload(String storagePool, String minioUploadId, UUID ownerId, String uploadId) throws IOException;

    StoredFile storeBytes(String storagePool, UUID ownerId, UUID fileId, String objectName, byte[] data, String contentType) throws IOException;

    StorageDownload download(String storagePool, String storageKey) throws IOException;

    void deleteStorageKey(String storagePool, String storageKey) throws IOException;

    void deleteOwnerStorage(UUID ownerId) throws IOException;
}
