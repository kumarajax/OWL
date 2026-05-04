package com.owldrive.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AccessLogRecord(
        UUID id,
        UUID userId,
        String keycloakId,
        String email,
        String ipAddress,
        String country,
        String region,
        String city,
        Double latitude,
        Double longitude,
        String locationSource,
        String userAgent,
        String method,
        String path,
        Integer statusCode,
        long durationMs,
        String eventType,
        OffsetDateTime createdAt
) {}
