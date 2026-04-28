package com.owldrive.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MeController {
    private final ProvisioningService provisioningService;
    private final AccountService accountService;

    public MeController(ProvisioningService provisioningService, AccountService accountService) {
        this.provisioningService = provisioningService;
        this.accountService = accountService;
    }

    @GetMapping("/me")
    UserRecord me(@AuthenticationPrincipal Jwt jwt) {
        return provisioningService.currentUser(jwt);
    }

    @GetMapping("/me/storage")
    UserStorageRecord storage(@AuthenticationPrincipal Jwt jwt) {
        UserRecord user = provisioningService.ensureUser(jwt);
        boolean unlimited = "ADMIN".equals(user.role()) || user.quotaBytes() == null;
        return new UserStorageRecord(user.role(), user.quotaBytes(), user.usedBytes(), unlimited);
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deactivate(@AuthenticationPrincipal Jwt jwt, @RequestBody DeactivateAccountRequest request) {
        accountService.deactivate(jwt, request);
    }

    @PostMapping("/me/activate")
    UserRecord activate(@AuthenticationPrincipal Jwt jwt) {
        return accountService.activate(jwt);
    }
}
