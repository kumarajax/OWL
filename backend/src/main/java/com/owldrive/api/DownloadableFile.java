package com.owldrive.api;

import org.springframework.core.io.InputStreamResource;

public record DownloadableFile(FileRecord file, InputStreamResource resource, long contentLength) {}
