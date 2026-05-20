package com.owldrive.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

public interface ObjectStorageService {
    StoredFile store(UUID ownerId, UUID fileId, MultipartFile upload) throws IOException;

    StoredFile storeFile(UUID ownerId, UUID fileId, Path path, long sizeBytes, String contentType) throws IOException;

    StoredFile storeBytes(String storagePool, UUID ownerId, UUID fileId, String objectName, byte[] data, String contentType) throws IOException;

    StorageDownload download(String storagePool, String storageKey) throws IOException;

    void deleteStorageKey(String storagePool, String storageKey) throws IOException;

    void deleteOwnerStorage(UUID ownerId) throws IOException;
}
