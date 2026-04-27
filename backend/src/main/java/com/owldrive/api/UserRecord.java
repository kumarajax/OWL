package com.owldrive.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserRecord(
        UUID id,
        String keycloakId,
        String email,
        String username,
        String role,
        Long quotaBytes,
        long usedBytes,
        OffsetDateTime createdAt
) {}
