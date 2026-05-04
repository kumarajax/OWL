package com.owldrive.api;

import java.time.OffsetDateTime;

public record TelemetryRetentionRecord(
        long maxRetentionRows,
        long maxAllowedRetentionRows,
        OffsetDateTime updatedAt
) {}
