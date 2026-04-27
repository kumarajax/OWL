package com.owldrive.api;

import java.nio.file.Path;

public record StoredFile(String storageKey, Path path, String checksumSha256, long sizeBytes) {}
