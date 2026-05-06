package com.owldrive.api;

import java.io.IOException;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

public interface ObjectStorageService {
    StoredFile store(UUID ownerId, UUID fileId, MultipartFile upload) throws IOException;

    StorageDownload download(String storageKey) throws IOException;

    void deleteStorageKey(String storageKey) throws IOException;

    void deleteOwnerStorage(UUID ownerId) throws IOException;
}
