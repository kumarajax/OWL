package com.owldrive.api;

public record UserStorageRecord(
        String role,
        Long quotaBytes,
        long usedBytes,
        boolean isUnlimited
) {}
