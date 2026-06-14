package com.owldrive.api;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.UploadPartResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import io.minio.messages.Part;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
        return storeStream(ownerId, fileId, upload::getInputStream, upload.getSize(), upload.getContentType());
    }

    @Override
    public StoredFile storeFile(UUID ownerId, UUID fileId, Path path, long sizeBytes, String contentType) throws IOException {
        return storeStream(ownerId, fileId, () -> Files.newInputStream(path), sizeBytes, contentType);
    }

    @Override
    public StoredUploadPart storeMultipartUploadPart(
            String storagePool,
            String minioUploadId,
            UUID ownerId,
            String uploadId,
            int chunkIndex,
            MultipartFile chunk) throws IOException {
        Pool pool = (storagePool == null || storagePool.isBlank()) ? pools.get(0) : requirePool(storagePool);
        ensureBucket(pool);
        String storageKey = multipartUploadStorageKey(ownerId, uploadId);
        try {
            String activeUploadId = minioUploadId;
            if (activeUploadId == null || activeUploadId.isBlank()) {
                activeUploadId = pool.asyncClient
                        .createMultipartUploadAsync(pool.bucket, null, storageKey, HashMultimap.create(), HashMultimap.create())
                        .get()
                        .result()
                        .uploadId();
            }
            try (InputStream input = chunk.getInputStream()) {
                UploadPartResponse response = pool.asyncClient
                        .uploadPartAsync(pool.bucket, null, storageKey, input, chunk.getSize(), activeUploadId, chunkIndex + 1, emptyMultimap(), emptyMultimap())
                        .get();
                return new StoredUploadPart(pool.name, activeUploadId, response.partNumber(), response.etag());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while storing upload part", ex);
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("Unable to store upload part", ex);
        }
    }

    @Override
    public StoredFile completeMultipartUpload(
            String storagePool,
            String minioUploadId,
            UUID ownerId,
            String uploadId,
            List<StoredUploadPart> parts,
            String checksumSha256,
            long sizeBytes) throws IOException {
        Pool pool = requirePool(storagePool);
        String storageKey = multipartUploadStorageKey(ownerId, uploadId);
        Part[] minioParts = parts.stream()
                .map(part -> new Part(part.partNumber(), part.etag()))
                .toArray(Part[]::new);
        try {
            pool.asyncClient
                    .completeMultipartUploadAsync(pool.bucket, null, storageKey, minioUploadId, minioParts, emptyMultimap(), emptyMultimap())
                    .get();
            return new StoredFile(pool.name, storageKey, checksumSha256, sizeBytes);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while completing upload", ex);
        } catch (Exception ex) {
            throw new IOException("Unable to complete multipart upload", ex);
        }
    }

    @Override
    public void abortMultipartUpload(String storagePool, String minioUploadId, UUID ownerId, String uploadId) throws IOException {
        if (storagePool == null || storagePool.isBlank() || minioUploadId == null || minioUploadId.isBlank()) {
            return;
        }
        Pool pool = requirePool(storagePool);
        try {
            pool.asyncClient
                    .abortMultipartUploadAsync(pool.bucket, null, multipartUploadStorageKey(ownerId, uploadId), minioUploadId, emptyMultimap(), emptyMultimap())
                    .get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while aborting upload", ex);
        } catch (Exception ex) {
            throw new IOException("Unable to abort multipart upload", ex);
        }
    }

    private StoredFile storeStream(UUID ownerId, UUID fileId, InputStreamSupplier sourceSupplier, long sizeBytes, String contentType) throws IOException {
        IOException lastError = null;
        for (Pool pool : pools) {
            MessageDigest digest = sha256Digest();
            try (InputStream input = new DigestInputStream(sourceSupplier.get(), digest)) {
                ensureBucket(pool);
                String storageKey = storageKey(ownerId, fileId);
                PutObjectArgs.Builder builder = PutObjectArgs.builder()
                        .bucket(pool.bucket)
                        .object(storageKey)
                        .stream(input, sizeBytes, UPLOAD_PART_SIZE);
                if (contentType != null && !contentType.isBlank()) {
                    builder.contentType(contentType);
                }
                pool.client.putObject(builder.build());
                return new StoredFile(pool.name, storageKey, HexFormat.of().formatHex(digest.digest()), sizeBytes);
            } catch (IOException ex) {
                lastError = ex;
            } catch (Exception ex) {
                lastError = new IOException("Unable to store file", ex);
            }
        }
        throw lastError == null ? new IOException("Unable to store file") : lastError;
    }

    @Override
    public StoredFile storeBytes(String storagePool, UUID ownerId, UUID fileId, String objectName, byte[] data, String contentType) throws IOException {
        Pool pool = requirePool(storagePool);
        ensureBucket(pool);
        byte[] payload = data == null ? new byte[0] : data;
        try {
            String storageKey = storageKey(ownerId, fileId, objectName);
            MessageDigest digest = sha256Digest();
            try (InputStream input = new DigestInputStream(new ByteArrayInputStream(payload), digest)) {
                PutObjectArgs.Builder builder = PutObjectArgs.builder()
                        .bucket(pool.bucket)
                        .object(storageKey)
                        .stream(input, payload.length, UPLOAD_PART_SIZE);
                if (contentType != null && !contentType.isBlank()) {
                    builder.contentType(contentType);
                }
                pool.client.putObject(builder.build());
            }
            return new StoredFile(pool.name, storageKey, HexFormat.of().formatHex(digest.digest()), payload.length);
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("Unable to store file", ex);
        }
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
        return storageKey(ownerId, fileId, "original");
    }

    private String storageKey(UUID ownerId, UUID fileId, String objectName) {
        return ownerId + "/" + fileId + "/" + objectName;
    }

    private String multipartUploadStorageKey(UUID ownerId, String uploadId) {
        return ownerId + "/uploads/" + uploadId + "/original";
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

    private Multimap<String, String> emptyMultimap() {
        return HashMultimap.create();
    }

    private static final class Pool {
        private final String name;
        private final String bucket;
        private final MinioClient client;
        private final MinioAsyncClient asyncClient;
        private final AtomicBoolean bucketReady = new AtomicBoolean(false);

        private Pool(String name, String endpoint, String accessKey, String secretKey, String bucket) {
            this.name = name;
            this.bucket = bucket;
            this.client = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();
            this.asyncClient = MinioAsyncClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .build();
        }
    }

    @FunctionalInterface
    private interface InputStreamSupplier {
        InputStream get() throws IOException;
    }
}
