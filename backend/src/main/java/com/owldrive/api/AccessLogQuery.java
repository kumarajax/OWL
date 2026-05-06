package com.owldrive.api;

import java.time.OffsetDateTime;

public record AccessLogQuery(
        int limit,
        OffsetDateTime createdFrom,
        OffsetDateTime createdTo,
        String user,
        String displayName,
        String email,
        String keycloakId,
        String ipAddress,
        String country,
        String region,
        String city,
        String locationSource,
        String userAgent,
        String method,
        String path,
        Integer statusCode,
        Long durationMinMs,
        Long durationMaxMs,
        String eventType
) {}
