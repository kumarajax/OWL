package com.owldrive.api;

public record RegistrationStatusRecord(
        long activeUsers,
        long maxUsers,
        boolean registrationAvailable
) {}
