package com.owldrive.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService {
    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final JdbcTemplate jdbc;
    private final ProvisioningService provisioningService;
    private final LocalStorageService localStorageService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String keycloakBaseUrl;
    private final String keycloakRealm;
    private final String keycloakClientId;
    private final String keycloakAdminUser;
    private final String keycloakAdminPassword;

    public AccountService(
            JdbcTemplate jdbc,
            ProvisioningService provisioningService,
            LocalStorageService localStorageService,
            ObjectMapper objectMapper,
            @Value("${app.keycloak.base-url:${KEYCLOAK_INTERNAL_URL:http://localhost:8080}}") String keycloakBaseUrl,
            @Value("${app.keycloak.realm:${KEYCLOAK_REALM:owldrive}}") String keycloakRealm,
            @Value("${app.keycloak.client-id:${KEYCLOAK_CLIENT_ID:owl-drive-web}}") String keycloakClientId,
            @Value("${app.keycloak.admin-user:${KEYCLOAK_ADMIN_USER:admin}}") String keycloakAdminUser,
            @Value("${app.keycloak.admin-password:${KEYCLOAK_ADMIN_PASSWORD:admin}}") String keycloakAdminPassword) {
        this.jdbc = jdbc;
        this.provisioningService = provisioningService;
        this.localStorageService = localStorageService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
        this.keycloakBaseUrl = trimTrailingSlash(keycloakBaseUrl);
        this.keycloakRealm = keycloakRealm;
        this.keycloakClientId = keycloakClientId;
        this.keycloakAdminUser = keycloakAdminUser;
        this.keycloakAdminPassword = keycloakAdminPassword;
    }

    @Transactional
    public void deactivate(Jwt jwt, DeactivateAccountRequest request) {
        UserRecord user = provisioningService.ensureUser(jwt);
        String confirmation = request == null ? "" : request.confirmation();
        if (confirmation == null || !confirmation.trim().equalsIgnoreCase(user.email())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type your email address to confirm account deactivation");
        }

        jdbc.update(
                """
                DELETE FROM files
                WHERE owner_id = ?
                """,
                user.id());
        jdbc.update(
                """
                DELETE FROM folders
                WHERE owner_id = ?
                """,
                user.id());
        jdbc.update(
                """
                UPDATE users
                SET deactivated_at = now(), used_bytes = 0
                WHERE id = ? AND deactivated_at IS NULL
                """,
                user.id());

        deleteOwnerStorageAfterCommit(user);
    }

    @Transactional
    public UserRecord activate(Jwt jwt) {
        return provisioningService.activateUser(jwt);
    }

    public void changePassword(Jwt jwt, ChangePasswordRequest request) {
        UserRecord user = provisioningService.ensureUser(jwt);
        String currentPassword = request == null ? "" : request.currentPassword();
        String newPassword = request == null ? "" : request.newPassword();
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is required");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be at least 8 characters");
        }
        if (newPassword.equals(currentPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be different from current password");
        }

        verifyCurrentPassword(user, currentPassword);
        resetKeycloakPassword(user.keycloakId(), newPassword);
    }

    private void verifyCurrentPassword(UserRecord user, String currentPassword) {
        for (String username : Arrays.asList(user.username(), user.email())) {
            if (username == null || username.isBlank()) {
                continue;
            }
            HttpResponse<String> response = sendForm(
                    keycloakBaseUrl + "/realms/" + keycloakRealm + "/protocol/openid-connect/token",
                    Map.of(
                            "grant_type", "password",
                            "client_id", keycloakClientId,
                            "username", username,
                            "password", currentPassword));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
    }

    private void resetKeycloakPassword(String keycloakUserId, String newPassword) {
        String adminToken = adminAccessToken();
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "type", "password",
                    "value", newPassword,
                    "temporary", false));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(keycloakBaseUrl + "/admin/realms/" + keycloakRealm + "/users/" + keycloakUserId + "/reset-password"))
                    .header("Authorization", "Bearer " + adminToken)
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Keycloak password reset failed with status {}", response.statusCode());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to update password");
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to update password", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to update password", ex);
        }
    }

    private String adminAccessToken() {
        HttpResponse<String> response = sendForm(
                keycloakBaseUrl + "/realms/master/protocol/openid-connect/token",
                Map.of(
                        "grant_type", "password",
                        "client_id", "admin-cli",
                        "username", keycloakAdminUser,
                        "password", keycloakAdminPassword));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to authenticate Keycloak admin");
        }
        try {
            JsonNode json = objectMapper.readTree(response.body());
            JsonNode accessToken = json.get("access_token");
            if (accessToken == null || accessToken.asText().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Keycloak admin token was not returned");
            }
            return accessToken.asText();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to read Keycloak admin response", ex);
        }
    }

    private HttpResponse<String> sendForm(String url, Map<String, String> fields) {
        try {
            String body = fields.entrySet().stream()
                    .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining("&"));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to reach Keycloak", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to reach Keycloak", ex);
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8080";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private void deleteOwnerStorageAfterCommit(UserRecord user) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    localStorageService.deleteOwnerStorage(user.id());
                } catch (IOException ex) {
                    log.warn("Unable to delete stored bytes for deactivated user {}", user.id(), ex);
                }
            }
        });
    }
}
