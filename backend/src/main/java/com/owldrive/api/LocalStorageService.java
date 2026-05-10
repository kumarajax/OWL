package com.owldrive.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@Primary
public class LocalStorageService implements ObjectStorageService {
    private final List<Path> storageRoots;

    public LocalStorageService(StorageProperties storageProperties) {
        this.storageRoots = storageProperties.resolvedRoots();
    }

    @Override
    public StoredFile store(UUID ownerId, UUID fileId, MultipartFile upload) throws IOException {
        String storageKey = storageKey(ownerId, fileId);
        IOException lastFailure = null;
        for (Path root : storageRoots) {
            Path target = resolveStorageKey(storageKey, root);
            try {
                Files.createDirectories(target.getParent());
                MessageDigest digest = sha256Digest();
                long bytes;
                try (InputStream input = new DigestInputStream(upload.getInputStream(), digest);
                        OutputStream output = Files.newOutputStream(
                                target,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE)) {
                    bytes = input.transferTo(output);
                }
                return new StoredFile(storageKey, HexFormat.of().formatHex(digest.digest()), bytes);
            } catch (IOException ex) {
                lastFailure = ex;
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanupEx) {
                    lastFailure.addSuppressed(cleanupEx);
                }
            }
        }

        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new IOException("No storage roots configured");
    }

    @Override
    public StorageDownload download(String storageKey) throws IOException {
        for (Path storageRoot : storageRoots) {
            Path path = resolveStorageKey(storageKey, storageRoot);
            if (Files.isRegularFile(path)) {
                return new StorageDownload(new org.springframework.core.io.InputStreamResource(Files.newInputStream(path)), Files.size(path));
            }
        }
        throw new java.nio.file.NoSuchFileException(storageKey);
    }

    @Override
    public void deleteStorageKey(String storageKey) throws IOException {
        boolean deleted = false;
        IOException lastFailure = null;
        for (Path storageRoot : storageRoots) {
            Path path = resolveStorageKey(storageKey, storageRoot);
            try {
                deleted |= Files.deleteIfExists(path);
            } catch (IOException ex) {
                lastFailure = ex;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        if (!deleted) {
            throw new java.nio.file.NoSuchFileException(storageKey);
        }
    }

    @Override
    public void deleteOwnerStorage(UUID ownerId) throws IOException {
        IOException lastFailure = null;
        String prefix = ownerId + "/";
        for (Path storageRoot : storageRoots) {
            Path ownerRoot = storageRoot.resolve(prefix).normalize();
            if (!ownerRoot.startsWith(storageRoot)) {
                throw new IllegalArgumentException("Invalid storage key");
            }
            if (!Files.exists(ownerRoot)) {
                continue;
            }
            try (var stream = Files.walk(ownerRoot)) {
                stream.sorted((left, right) -> right.compareTo(left))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ex) {
                                throw new RuntimeException(ex);
                            }
                        });
            } catch (RuntimeException ex) {
                if (ex.getCause() instanceof IOException ioException) {
                    lastFailure = ioException;
                } else {
                    throw ex;
                }
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
    }

    private String storageKey(UUID ownerId, UUID fileId) {
        return ownerId + "/" + fileId + "/original";
    }

    private Path resolveStorageKey(String storageKey, Path storageRoot) {
        Path path = storageRoot.resolve(storageKey).normalize();
        if (!path.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return path;
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
