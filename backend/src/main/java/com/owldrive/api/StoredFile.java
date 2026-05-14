package com.owldrive.api;

public record StoredFile(String storagePool, String storageKey, String checksumSha256, long sizeBytes) {}
