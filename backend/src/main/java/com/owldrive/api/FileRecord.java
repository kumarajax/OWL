package com.owldrive.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FileRecord(
        UUID id,
        UUID ownerId,
        UUID parentFolderId,
        String originalName,
        String storageKey,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deletedAt
) {}
