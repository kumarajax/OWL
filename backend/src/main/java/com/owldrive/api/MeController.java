package com.owldrive.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MeController {
    private final ProvisioningService provisioningService;

    public MeController(ProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @GetMapping("/me")
    UserRecord me(@AuthenticationPrincipal Jwt jwt) {
        return provisioningService.ensureUser(jwt);
    }

    @GetMapping("/me/storage")
    UserStorageRecord storage(@AuthenticationPrincipal Jwt jwt) {
        UserRecord user = provisioningService.ensureUser(jwt);
        boolean unlimited = "ADMIN".equals(user.role()) || user.quotaBytes() == null;
        return new UserStorageRecord(user.role(), user.quotaBytes(), user.usedBytes(), unlimited);
    }
}
