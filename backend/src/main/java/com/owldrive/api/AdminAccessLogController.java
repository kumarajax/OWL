package com.owldrive.api;

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
    List<AccessLogRecord> recent(@AuthenticationPrincipal Jwt jwt, @RequestParam(name = "limit", defaultValue = "100") int limit) {
        adminGuard.requireAdminOrOperations(jwt);
        return accessLogService.recent(limit);
    }
}
