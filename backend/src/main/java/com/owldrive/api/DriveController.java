package com.owldrive.api;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/drive")
public class DriveController {
    private final ProvisioningService provisioningService;

    public DriveController(ProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @GetMapping("/root")
    FolderRecord root(@AuthenticationPrincipal Jwt jwt) {
        UserRecord user = provisioningService.ensureUser(jwt);
        return provisioningService.ensureRootFolder(user);
    }
}
