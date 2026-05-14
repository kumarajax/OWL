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
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@Primary
public class MultiPoolMinioObjectStorageService implements ObjectStorageService {
    private static final long UPLOAD_PART_SIZE = 10L * 1024 * 1024;

    private final List<Pool> pools;

    public MultiPoolMinioObjectStorageService(
            MinioPoolProperties properties,
            @Value("${app.storage.minio.endpoint:http://localhost:9000}") String primaryEndpoint,
            @Value("${app.storage.minio.access-key:minioadmin}") String primaryAccessKey,
            @Value("${app.storage.minio.secret-key:minioadmin}") String primarySecretKey,
            @Value("${app.storage.minio.bucket:owl-drive}") String primaryBucket) {
        this.pools = new ArrayList<>();
        this.pools.add(new Pool("primary", primaryEndpoint, primaryAccessKey, primarySecretKey, primaryBucket));
        if (properties.getPools() != null) {
            for (MinioPoolProperties.Pool pool : properties.getPools()) {
                this.pools.add(new Pool(
                        pool.getName(),
                        pool.getEndpoint(),
                        pool.getAccessKey(),
                        pool.getSecretKey(),
                        pool.getBucket()));
            }
        }
        if (this.pools.isEmpty()) {
            throw new IllegalStateException("At least one MinIO pool must be configured");
        }
    }

    @Override
    public StoredFile store(UUID ownerId, UUID fileId, MultipartFile upload) throws IOException {
        IOException lastError = null;
        for (Pool pool : pools) {
            MessageDigest digest = sha256Digest();
            try {
                ensureBucket(pool);
                String storageKey = storageKey(ownerId, fileId);
                long bytes;
                try (InputStream input = new DigestInputStream(upload.getInputStream(), digest)) {
                    PutObjectArgs.Builder builder = PutObjectArgs.builder()
                            .bucket(pool.bucket)
                            .object(storageKey)
                            .stream(input, upload.getSize(), UPLOAD_PART_SIZE);
                    if (upload.getContentType() != null && !upload.getContentType().isBlank()) {
                        builder.contentType(upload.getContentType());
                    }
                    pool.client.putObject(builder.build());
                    bytes = upload.getSize();
                }
                return new StoredFile(pool.name, storageKey, HexFormat.of().formatHex(digest.digest()), bytes);
            } catch (IOException ex) {
                lastError = ex;
            } catch (Exception ex) {
                lastError = new IOException("Unable to store file", ex);
            }
        }
        throw lastError == null ? new IOException("Unable to store file") : lastError;
    }

    @Override
    public StorageDownload download(String storagePool, String storageKey) throws IOException {
        Pool pool = requirePool(storagePool);
        ensureBucket(pool);
        try {
            long size = pool.client.statObject(
                    StatObjectArgs.builder().bucket(pool.bucket).object(storageKey).build()).size();
            InputStream stream = pool.client.getObject(
                    GetObjectArgs.builder().bucket(pool.bucket).object(storageKey).build());
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
    public void deleteStorageKey(String storagePool, String storageKey) throws IOException {
        Pool pool = requirePool(storagePool);
        ensureBucket(pool);
        try {
            pool.client.removeObject(
                    RemoveObjectArgs.builder().bucket(pool.bucket).object(storageKey).build());
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
        String prefix = ownerId + "/";
        for (Pool pool : pools) {
            ensureBucket(pool);
            try {
                List<String> objectNames = new ArrayList<>();
                Iterable<Result<Item>> results = pool.client.listObjects(
                        ListObjectsArgs.builder().bucket(pool.bucket).prefix(prefix).recursive(true).build());
                for (Result<Item> result : results) {
                    Item item = result.get();
                    objectNames.add(item.objectName());
                }
                for (String objectName : objectNames) {
                    pool.client.removeObject(RemoveObjectArgs.builder().bucket(pool.bucket).object(objectName).build());
                }
            } catch (ErrorResponseException ex) {
                if (ex.errorResponse() != null && ex.errorResponse().code() != null) {
                    String code = ex.errorResponse().code();
                    if ("NoSuchBucket".equalsIgnoreCase(code)) {
                        continue;
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
    }

    private Pool requirePool(String storagePool) {
        return pools.stream()
                .filter(pool -> pool.name.equals(storagePool))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown MinIO pool: " + storagePool));
    }

    private void ensureBucket(Pool pool) throws IOException {
        if (pool.bucketReady.get()) {
            return;
        }
        synchronized (pool.bucketReady) {
            if (pool.bucketReady.get()) {
                return;
            }
            try {
                if (!pool.client.bucketExists(BucketExistsArgs.builder().bucket(pool.bucket).build())) {
                    pool.client.makeBucket(MakeBucketArgs.builder().bucket(pool.bucket).build());
                }
                pool.bucketReady.set(true);
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

    private static final class Pool {
        private final String name;
        private final String bucket;
        private final MinioClient client;
        private final AtomicBoolean bucketReady = new AtomicBoolean(false);

        private Pool(String name, String endpoint, String accessKey, String secretKey, String bucket) {
            this.name = name;
            this.bucket = bucket;
            this.client = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();
        }
    }
}
