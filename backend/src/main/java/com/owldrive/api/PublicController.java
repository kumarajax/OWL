package com.owldrive.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public")
public class PublicController {
    private final UserCapacityService userCapacityService;

    public PublicController(UserCapacityService userCapacityService) {
        this.userCapacityService = userCapacityService;
    }

    @GetMapping("/registration")
    RegistrationStatusRecord registration() {
        return userCapacityService.registrationStatus();
    }
}
