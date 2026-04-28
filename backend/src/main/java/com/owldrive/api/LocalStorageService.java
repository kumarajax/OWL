package com.owldrive.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LocalStorageService {
    private final Path storageRoot;

    public LocalStorageService(@Value("${app.storage.local.root:./data/storage}") String storageRoot) {
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
    }

    public StoredFile store(UUID ownerId, UUID fileId, MultipartFile upload) throws IOException {
        String storageKey = ownerId + "/" + fileId + "/original";
        Path target = resolveStorageKey(storageKey);
        Files.createDirectories(target.getParent());

        MessageDigest digest = sha256Digest();
        long bytes;
        try (InputStream input = new DigestInputStream(upload.getInputStream(), digest);
                OutputStream output = Files.newOutputStream(target)) {
            bytes = input.transferTo(output);
        }

        return new StoredFile(storageKey, target, HexFormat.of().formatHex(digest.digest()), bytes);
    }

    public Path resolveStorageKey(String storageKey) {
        Path path = storageRoot.resolve(storageKey).normalize();
        if (!path.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return path;
    }

    public void deleteStorageKey(String storageKey) throws IOException {
        Path path = resolveStorageKey(storageKey);
        Files.deleteIfExists(path);
        pruneEmptyParents(path.getParent());
    }

    private void pruneEmptyParents(Path start) throws IOException {
        Path current = start;
        while (current != null && !current.equals(storageRoot) && current.startsWith(storageRoot)) {
            try {
                Files.delete(current);
            } catch (java.nio.file.DirectoryNotEmptyException ex) {
                return;
            } catch (java.nio.file.NoSuchFileException ex) {
                // Continue upward; another delete may have already removed this directory.
            }
            current = current.getParent();
        }
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
