package com.owldrive.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FolderRecord(
        UUID id,
        String name,
        UUID ownerId,
        UUID parentId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deletedAt
) {}
