package com.owldrive.api;

import java.util.Collection;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AdminGuard {
    public void requireAdmin(Jwt jwt) {
        if (jwt == null || !isAdmin(jwt)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role is required");
        }
    }

    public void requireAdminOrOperations(Jwt jwt) {
        if (jwt == null || (!isAdmin(jwt) && !hasRealmRole(jwt, "operations"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin or operations role is required");
        }
    }

    public boolean isAdmin(Jwt jwt) {
        return hasRealmRole(jwt, "admin");
    }

    private boolean hasRealmRole(Jwt jwt, String expectedRole) {
        Object realmAccess = jwt.getClaims().get("realm_access");
        if (realmAccess instanceof Map<?, ?> access) {
            Object roles = access.get("roles");
            if (roles instanceof Collection<?> values) {
                return values.stream()
                        .map(String::valueOf)
                        .anyMatch(role -> role.equalsIgnoreCase(expectedRole));
            }
        }
        return false;
    }
}
