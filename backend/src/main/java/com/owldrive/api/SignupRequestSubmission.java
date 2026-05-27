package com.owldrive.api;

public record SignupRequestSubmission(
        String email,
        String displayName,
        String password,
        String legalVersion,
        boolean termsAccepted
) {}
