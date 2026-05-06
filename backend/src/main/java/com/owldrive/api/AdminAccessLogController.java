package com.owldrive.api;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/access-logs")
public class AdminAccessLogController {
    private final AccessLogService accessLogService;
    private final AdminGuard adminGuard;

    public AdminAccessLogController(AccessLogService accessLogService, AdminGuard adminGuard) {
        this.accessLogService = accessLogService;
        this.adminGuard = adminGuard;
    }

    @GetMapping
    List<AccessLogRecord> recent(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "createdFrom", required = false) OffsetDateTime createdFrom,
            @RequestParam(name = "createdTo", required = false) OffsetDateTime createdTo,
            @RequestParam(name = "user", required = false) String user,
            @RequestParam(name = "displayName", required = false) String displayName,
            @RequestParam(name = "email", required = false) String email,
            @RequestParam(name = "keycloakId", required = false) String keycloakId,
            @RequestParam(name = "ipAddress", required = false) String ipAddress,
            @RequestParam(name = "country", required = false) String country,
            @RequestParam(name = "region", required = false) String region,
            @RequestParam(name = "city", required = false) String city,
            @RequestParam(name = "locationSource", required = false) String locationSource,
            @RequestParam(name = "userAgent", required = false) String userAgent,
            @RequestParam(name = "method", required = false) String method,
            @RequestParam(name = "path", required = false) String path,
            @RequestParam(name = "statusCode", required = false) Integer statusCode,
            @RequestParam(name = "durationMinMs", required = false) Long durationMinMs,
            @RequestParam(name = "durationMaxMs", required = false) Long durationMaxMs,
            @RequestParam(name = "eventType", required = false) String eventType) {
        adminGuard.requireAdminOrOperations(jwt);
        return accessLogService.recent(new AccessLogQuery(
                limit,
                createdFrom,
                createdTo,
                user,
                displayName,
                email,
                keycloakId,
                ipAddress,
                country,
                region,
                city,
                locationSource,
                userAgent,
                method,
                path,
                statusCode,
                durationMinMs,
                durationMaxMs,
                eventType));
    }
}
