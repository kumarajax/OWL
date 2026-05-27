package com.owldrive.api;

import java.time.OffsetDateTime;

public record LegalAcceptanceRecord(
        String currentVersion,
        boolean accepted,
        String acceptedVersion,
        OffsetDateTime acceptedAt
) {}
