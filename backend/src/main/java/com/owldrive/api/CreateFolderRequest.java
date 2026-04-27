package com.owldrive.api;

import java.util.UUID;

public record CreateFolderRequest(String name, UUID parentId) {}
