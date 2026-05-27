package com.owldrive.api;

public record SignupRequestStatusRecord(
        String status,
        String message,
        String email,
        String displayName
) {}
