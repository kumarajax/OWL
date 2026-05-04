package com.owldrive.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/telemetry-retention")
public class AdminTelemetryRetentionController {
    private final AdminGuard adminGuard;
    private final TelemetryRetentionService telemetryRetentionService;

    public AdminTelemetryRetentionController(AdminGuard adminGuard, TelemetryRetentionService telemetryRetentionService) {
        this.adminGuard = adminGuard;
        this.telemetryRetentionService = telemetryRetentionService;
    }

    @GetMapping
    TelemetryRetentionRecord current(@AuthenticationPrincipal Jwt jwt) {
        adminGuard.requireAdminOrOperations(jwt);
        return telemetryRetentionService.current();
    }

    @PatchMapping
    TelemetryRetentionRecord update(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody UpdateTelemetryRetentionRequest request) {
        adminGuard.requireAdminOrOperations(jwt);
        return telemetryRetentionService.update(request.maxRetentionRows());
    }

    public record UpdateTelemetryRetentionRequest(long maxRetentionRows) {}
}
