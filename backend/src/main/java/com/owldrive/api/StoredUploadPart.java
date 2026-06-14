package com.owldrive.api;

public record StoredUploadPart(String storagePool, String minioUploadId, int partNumber, String etag) {}
