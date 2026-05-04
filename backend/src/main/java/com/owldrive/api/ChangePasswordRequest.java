package com.owldrive.api;

public record ChangePasswordRequest(
        String currentPassword,
        String newPassword
) {}
