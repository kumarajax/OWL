package com.owldrive.api;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MinioObjectStorageService implements ObjectStorageService {
    private static final long UPLOAD_PART_SIZE = 10L * 1024 * 1024;

    private final MinioClient minioClient;
    private final String bucket;
    private final AtomicBoolean bucketReady = new AtomicBoolean(false);

    public MinioObjectStorageService(
            @Value("${app.storage.minio.endpoint:http://localhost:9000}") String endpoint,
            @Value("${app.storage.minio.access-key:minioadmin}") String accessKey,
            @Value("${app.storage.minio.secret-key:minioadmin}") String secretKey,
            @Value("${app.storage.minio.bucket:owl-drive}") String bucket) {
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.bucket = bucket;
    }

    @Override
    public StoredFile store(UUID ownerId, UUID fileId, MultipartFile upload) throws IOException {
        String storageKey = storageKey(ownerId, fileId);
        ensureBucket();

        MessageDigest digest = sha256Digest();
        long bytes;
        try (InputStream input = new DigestInputStream(upload.getInputStream(), digest)) {
            PutObjectArgs.Builder builder = PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(storageKey)
                    .stream(input, upload.getSize(), UPLOAD_PART_SIZE);
            if (upload.getContentType() != null && !upload.getContentType().isBlank()) {
                builder.contentType(upload.getContentType());
            }
            minioClient.putObject(builder.build());
            bytes = upload.getSize();
        } catch (Exception ex) {
            throw asIOException("Unable to store file", ex);
        }

        return new StoredFile(storageKey, HexFormat.of().formatHex(digest.digest()), bytes);
    }

    @Override
    public StorageDownload download(String storageKey) throws IOException {
        ensureBucket();
        try {
            long size = minioClient.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(storageKey).build()).size();
            InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(storageKey).build());
            return new StorageDownload(new InputStreamResource(stream), size);
        } catch (ErrorResponseException ex) {
            if (ex.errorResponse() != null && ex.errorResponse().code() != null) {
                String code = ex.errorResponse().code();
                if ("NoSuchKey".equalsIgnoreCase(code) || "NoSuchObject".equalsIgnoreCase(code)) {
                    throw new java.nio.file.NoSuchFileException(storageKey);
                }
            }
            throw asIOException("Unable to read file", ex);
        } catch (Exception ex) {
            throw asIOException("Unable to read file", ex);
        }
    }

    @Override
    public void deleteStorageKey(String storageKey) throws IOException {
        ensureBucket();
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder().bucket(bucket).object(storageKey).build());
        } catch (ErrorResponseException ex) {
            if (ex.errorResponse() != null && ex.errorResponse().code() != null) {
                String code = ex.errorResponse().code();
                if ("NoSuchKey".equalsIgnoreCase(code) || "NoSuchObject".equalsIgnoreCase(code)) {
                    return;
                }
            }
            throw asIOException("Unable to delete stored bytes", ex);
        } catch (Exception ex) {
            throw asIOException("Unable to delete stored bytes", ex);
        }
    }

    @Override
    public void deleteOwnerStorage(UUID ownerId) throws IOException {
        ensureBucket();
        String prefix = ownerId + "/";
        try {
            List<String> objectNames = new ArrayList<>();
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build());
            for (Result<Item> result : results) {
                Item item = result.get();
                objectNames.add(item.objectName());
            }
            for (String objectName : objectNames) {
                minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectName).build());
            }
        } catch (ErrorResponseException ex) {
            if (ex.errorResponse() != null && ex.errorResponse().code() != null) {
                String code = ex.errorResponse().code();
                if ("NoSuchBucket".equalsIgnoreCase(code)) {
                    return;
                }
            }
            throw asIOException("Unable to delete stored bytes for user", ex);
        } catch (Exception ex) {
            if (ex instanceof IOException ioException) {
                throw ioException;
            }
            throw asIOException("Unable to delete stored bytes for user", ex);
        }
    }

    private void ensureBucket() throws IOException {
        if (bucketReady.get()) {
            return;
        }
        synchronized (bucketReady) {
            if (bucketReady.get()) {
                return;
            }
            try {
                if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                }
                bucketReady.set(true);
            } catch (ErrorResponseException ex) {
                throw asIOException("Unable to initialize storage bucket", ex);
            } catch (Exception ex) {
                throw asIOException("Unable to initialize storage bucket", ex);
            }
        }
    }

    private String storageKey(UUID ownerId, UUID fileId) {
        return ownerId + "/" + fileId + "/original";
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private IOException asIOException(String message, Exception ex) {
        return new IOException(message, ex);
    }
}
