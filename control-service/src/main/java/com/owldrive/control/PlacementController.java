package com.owldrive.control;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PlacementController {
    @GetMapping("/v1/storage/health")
    Map<String, Object> health() {
        return Map.of("status", "UP", "registeredNodes", List.of());
    }
}

