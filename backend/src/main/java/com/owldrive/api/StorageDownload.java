package com.owldrive.api;

import org.springframework.core.io.InputStreamResource;

public record StorageDownload(InputStreamResource resource, long sizeBytes) {}
