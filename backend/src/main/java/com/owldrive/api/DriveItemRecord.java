package com.owldrive.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DriveItemRecord(
        String itemType,
        UUID id,
        String name,
        UUID ownerId,
        UUID parentId,
        String contentType,
        Long sizeBytes,
        String checksumSha256,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    static DriveItemRecord folder(FolderRecord folder) {
        return new DriveItemRecord(
                "folder",
                folder.id(),
                folder.name(),
                folder.ownerId(),
                folder.parentId(),
                null,
                null,
                null,
                folder.createdAt(),
                folder.updatedAt());
    }

    static DriveItemRecord file(FileRecord file) {
        return new DriveItemRecord(
                "file",
                file.id(),
                file.originalName(),
                file.ownerId(),
                file.parentFolderId(),
                file.contentType(),
                file.sizeBytes(),
                file.checksumSha256(),
                file.createdAt(),
                file.updatedAt());
    }
}
