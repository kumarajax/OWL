package com.owldrive.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FileShareRecord(
        UUID id,
        UUID fileId,
        UUID ownerId,
        String shareUrl,
        OffsetDateTime expiresAt,
        OffsetDateTime revokedAt,
        long downloadCount,
        OffsetDateTime lastDownloadedAt,
        OffsetDateTime createdAt
) {}
