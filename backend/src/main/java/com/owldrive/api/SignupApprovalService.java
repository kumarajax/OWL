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
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SignupApprovalService {
    private static final Logger log = LoggerFactory.getLogger(SignupApprovalService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final ShardJdbcRegistry shardJdbcRegistry;
    private final ObjectMapper objectMapper;
    private final JavaMailSender mailSender;
    private final HttpClient httpClient;
    private final String keycloakBaseUrl;
    private final String keycloakRealm;
    private final String keycloakAdminUser;
    private final String keycloakAdminPassword;
    private final String publicBaseUrl;
    private final String approvalEmailTo;
    private final String legalVersion;
    private final String encryptionKeyMaterial;
    private final int tokenDays;
    private final String mailFrom;

    public SignupApprovalService(
            JdbcTemplate jdbc,
            ShardJdbcRegistry shardJdbcRegistry,
            ObjectMapper objectMapper,
            JavaMailSender mailSender,
            @Value("${app.keycloak.base-url:${KEYCLOAK_INTERNAL_URL:http://localhost:8080}}") String keycloakBaseUrl,
            @Value("${app.keycloak.realm:${KEYCLOAK_REALM:owldrive}}") String keycloakRealm,
            @Value("${app.keycloak.admin-user:${KEYCLOAK_ADMIN_USER:admin}}") String keycloakAdminUser,
            @Value("${app.keycloak.admin-password:${KEYCLOAK_ADMIN_PASSWORD:admin}}") String keycloakAdminPassword,
            @Value("${app.public-base-url:http://localhost:3000}") String publicBaseUrl,
            @Value("${app.signup.approval-email-to:kumarajax@gmail.com}") String approvalEmailTo,
            @Value("${app.legal.current-version:2026-05-26}") String legalVersion,
            @Value("${app.signup.password-encryption-key:local-dev-change-me}") String encryptionKeyMaterial,
            @Value("${app.signup.token-days:7}") int tokenDays,
            @Value("${spring.mail.username:}") String mailFrom) {
        this.jdbc = jdbc;
        this.shardJdbcRegistry = shardJdbcRegistry;
        this.objectMapper = objectMapper;
        this.mailSender = mailSender;
        this.httpClient = HttpClient.newHttpClient();
        this.keycloakBaseUrl = trimTrailingSlash(keycloakBaseUrl);
        this.keycloakRealm = keycloakRealm;
        this.keycloakAdminUser = keycloakAdminUser;
        this.keycloakAdminPassword = keycloakAdminPassword;
        this.publicBaseUrl = trimTrailingSlash(publicBaseUrl);
        this.approvalEmailTo = approvalEmailTo;
        this.legalVersion = legalVersion;
        this.encryptionKeyMaterial = encryptionKeyMaterial;
        this.tokenDays = tokenDays;
        this.mailFrom = mailFrom;
    }

    @Transactional
    public SignupRequestStatusRecord submit(SignupRequestSubmission submission, String ipAddress, String userAgent) {
        String email = normalizeEmail(submission == null ? null : submission.email());
        String displayName = trimToNull(submission == null ? null : submission.displayName());
        String password = submission == null ? null : submission.password();
        if (email == null || !email.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email address is required");
        }
        if (password == null || password.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters");
        }
        if (submission == null || !submission.termsAccepted() || !legalVersion.equals(submission.legalVersion())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You must accept the current terms");
        }
        if (shardJdbcRegistry.findSingleUserByVerifiedEmail(email).isPresent() || keycloakUserExists(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account already exists for this email");
        }
        Integer pendingCount = jdbc.queryForObject(
                "SELECT count(*) FROM signup_requests WHERE lower(email) = lower(?) AND status = 'PENDING'",
                Integer.class,
                email);
        if (pendingCount != null && pendingCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A signup request is already pending for this email");
        }

        String approveToken = randomToken();
        String rejectToken = randomToken();
        EncryptedPassword encryptedPassword = encryptPassword(password);
        jdbc.update(
                """
                INSERT INTO signup_requests (
                  email, display_name, encrypted_password, password_nonce, status, legal_version,
                  terms_accepted_at, requester_ip, requester_user_agent, approve_token_hash,
                  reject_token_hash, expires_at
                )
                VALUES (?, ?, ?, ?, 'PENDING', ?, now(), ?, ?, ?, ?, now() + (? * interval '1 day'))
                """,
                email,
                displayName,
                encryptedPassword.cipherText(),
                encryptedPassword.nonce(),
                legalVersion,
                trimToNull(ipAddress),
                trimToNull(userAgent),
                hashToken(approveToken),
                hashToken(rejectToken),
                Math.max(1, tokenDays));

        sendAdminEmail(email, displayName, approveToken, rejectToken);
        sendApplicantReceivedEmail(email);
        return new SignupRequestStatusRecord("PENDING", "Signup request sent for approval.", email, displayName);
    }

    public SignupRequestStatusRecord tokenInfo(String token) {
        SignupRequest request = findByAnyToken(token);
        return new SignupRequestStatusRecord(request.status(), statusMessage(request), request.email(), request.displayName());
    }

    @Transactional
    public SignupRequestStatusRecord approve(String token) {
        SignupRequest request = findPendingByToken(token, true);
        String password = decryptPassword(request.encryptedPassword(), request.passwordNonce());
        String keycloakUserId = createKeycloakUser(request.email(), request.displayName(), password);
        assignUserRole(keycloakUserId);
        jdbc.update(
                """
                UPDATE signup_requests
                SET status = 'APPROVED', reviewed_at = now(), reviewed_action = 'APPROVED',
                    encrypted_password = NULL, password_nonce = NULL, review_reason = NULL
                WHERE id = ? AND status = 'PENDING'
                """,
                request.id());
        try {
            sendApplicantApprovedEmail(request.email());
        } catch (ResponseStatusException ex) {
            log.warn("Unable to send signup approval email to {}", request.email(), ex);
        }
        return new SignupRequestStatusRecord("APPROVED", "Signup request approved. Keycloak account created.", request.email(), request.displayName());
    }

    @Transactional
    public SignupRequestStatusRecord reject(String token, RejectSignupRequest rejection) {
        SignupRequest request = findPendingByToken(token, false);
        String reason = trimToNull(rejection == null ? null : rejection.reason());
        jdbc.update(
                """
                UPDATE signup_requests
                SET status = 'REJECTED', reviewed_at = now(), reviewed_action = 'REJECTED',
                    review_reason = ?, encrypted_password = NULL, password_nonce = NULL
                WHERE id = ? AND status = 'PENDING'
                """,
                reason,
                request.id());
        try {
            sendApplicantRejectedEmail(request.email(), reason);
        } catch (ResponseStatusException ex) {
            log.warn("Unable to send signup rejection email to {}", request.email(), ex);
        }
        return new SignupRequestStatusRecord("REJECTED", "Signup request rejected.", request.email(), request.displayName());
    }

    private SignupRequest findByAnyToken(String token) {
        String hash = hashToken(requiredToken(token));
        List<SignupRequest> matches = jdbc.query(
                """
                SELECT id, email, display_name, encrypted_password, password_nonce, status, expires_at
                FROM signup_requests
                WHERE approve_token_hash = ? OR reject_token_hash = ?
                """,
                this::mapSignupRequest,
                hash,
                hash);
        if (matches.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Signup approval link was not found");
        }
        SignupRequest request = matches.get(0);
        if ("PENDING".equals(request.status()) && request.expiresAt().isBefore(OffsetDateTime.now())) {
            return request.withStatus("EXPIRED");
        }
        return request;
    }

    private SignupRequest findPendingByToken(String token, boolean approve) {
        String hash = hashToken(requiredToken(token));
        String column = approve ? "approve_token_hash" : "reject_token_hash";
        List<SignupRequest> matches = jdbc.query(
                """
                SELECT id, email, display_name, encrypted_password, password_nonce, status, expires_at
                FROM signup_requests
                WHERE %s = ? AND status = 'PENDING'
                """.formatted(column),
                this::mapSignupRequest,
                hash);
        if (matches.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Signup approval link was not found or already used");
        }
        SignupRequest request = matches.get(0);
        if (request.expiresAt().isBefore(OffsetDateTime.now())) {
            jdbc.update("UPDATE signup_requests SET status = 'EXPIRED' WHERE id = ? AND status = 'PENDING'", request.id());
            throw new ResponseStatusException(HttpStatus.GONE, "Signup approval link has expired");
        }
        return request;
    }

    private String createKeycloakUser(String email, String displayName, String password) {
        String adminToken = adminAccessToken();
        try {
            String fallbackName = email.substring(0, email.indexOf('@'));
            String firstName = displayName == null ? fallbackName : displayName;
            Map<String, Object> body = Map.of(
                    "username", email,
                    "email", email,
                    "firstName", firstName,
                    "lastName", "OWL Drive",
                    "enabled", true,
                    "emailVerified", true,
                    "credentials", List.of(Map.of(
                            "type", "password",
                            "value", password,
                            "temporary", false)));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(keycloakBaseUrl + "/admin/realms/" + keycloakRealm + "/users"))
                    .header("Authorization", "Bearer " + adminToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 409) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "An account already exists for this email");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Keycloak user creation failed with status {}", response.statusCode());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to create Keycloak user");
            }
            return keycloakUserIdFromLocation(response.headers().firstValue("Location").orElse(null), email, adminToken);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to create Keycloak user", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to create Keycloak user", ex);
        }
    }

    private void assignUserRole(String keycloakUserId) {
        String adminToken = adminAccessToken();
        try {
            HttpRequest roleRequest = HttpRequest.newBuilder()
                    .uri(URI.create(keycloakBaseUrl + "/admin/realms/" + keycloakRealm + "/roles/user"))
                    .header("Authorization", "Bearer " + adminToken)
                    .GET()
                    .build();
            HttpResponse<String> roleResponse = httpClient.send(roleRequest, HttpResponse.BodyHandlers.ofString());
            if (roleResponse.statusCode() < 200 || roleResponse.statusCode() >= 300) {
                log.warn("Keycloak user role lookup failed with status {}", roleResponse.statusCode());
                return;
            }
            JsonNode role = objectMapper.readTree(roleResponse.body());
            HttpRequest assignRequest = HttpRequest.newBuilder()
                    .uri(URI.create(keycloakBaseUrl + "/admin/realms/" + keycloakRealm + "/users/" + keycloakUserId + "/role-mappings/realm"))
                    .header("Authorization", "Bearer " + adminToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(List.of(role))))
                    .build();
            HttpResponse<String> assignResponse = httpClient.send(assignRequest, HttpResponse.BodyHandlers.ofString());
            if (assignResponse.statusCode() < 200 || assignResponse.statusCode() >= 300) {
                log.warn("Keycloak user role assignment failed with status {}", assignResponse.statusCode());
            }
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Unable to assign default Keycloak user role", ex);
        }
    }

    private boolean keycloakUserExists(String email) {
        String adminToken = adminAccessToken();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(keycloakBaseUrl + "/admin/realms/" + keycloakRealm + "/users?email=" + urlEncode(email) + "&exact=true"))
                    .header("Authorization", "Bearer " + adminToken)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to check existing Keycloak users");
            }
            return objectMapper.readTree(response.body()).size() > 0;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to check existing Keycloak users", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to check existing Keycloak users", ex);
        }
    }

    private String keycloakUserIdFromLocation(String location, String email, String adminToken) {
        if (location != null && location.contains("/users/")) {
            return location.substring(location.lastIndexOf('/') + 1);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(keycloakBaseUrl + "/admin/realms/" + keycloakRealm + "/users?email=" + urlEncode(email) + "&exact=true"))
                    .header("Authorization", "Bearer " + adminToken)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode users = objectMapper.readTree(response.body());
            if (response.statusCode() >= 200 && response.statusCode() < 300 && users.isArray() && users.size() > 0) {
                return users.get(0).get("id").asText();
            }
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to resolve created Keycloak user");
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

    private void sendAdminEmail(String email, String displayName, String approveToken, String rejectToken) {
        String approveUrl = publicBaseUrl + "/signup-approvals/" + approveToken + "/approve";
        String rejectUrl = publicBaseUrl + "/signup-approvals/" + rejectToken + "/reject";
        String nameLine = displayName == null ? "" : "\nName: " + displayName;
        sendEmail(
                approvalEmailTo,
                "OWL Drive signup approval request",
                "A new OWL Drive account request is pending review.\n\nEmail: " + email + nameLine
                        + "\n\nApprove:\n" + approveUrl
                        + "\n\nReject:\n" + rejectUrl
                        + "\n\nOnly approve users you personally authorize.");
    }

    private void sendApplicantReceivedEmail(String email) {
        sendEmail(email, "OWL Drive signup request received",
                "Your OWL Drive account request was received and is pending approval.");
    }

    private void sendApplicantApprovedEmail(String email) {
        sendEmail(email, "OWL Drive signup approved",
                "Your OWL Drive account request was approved. You can now sign in at " + publicBaseUrl + ".");
    }

    private void sendApplicantRejectedEmail(String email, String reason) {
        String reasonText = reason == null ? "" : "\n\nReason:\n" + reason;
        sendEmail(email, "OWL Drive signup request rejected",
                "Your OWL Drive account request was not approved." + reasonText);
    }

    private void sendEmail(String to, String subject, String text) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            if (mailFrom != null && !mailFrom.isBlank()) {
                message.setFrom(mailFrom);
            }
            message.setTo(to);
            message.setSubject(subject);
            message.setText(text);
            mailSender.send(message);
        } catch (MailException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to send email", ex);
        }
    }

    private EncryptedPassword encryptPassword(String password) {
        try {
            byte[] nonce = new byte[12];
            SECURE_RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, nonce));
            byte[] cipherText = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            return new EncryptedPassword(base64(cipherText), base64(nonce));
        } catch (GeneralSecurityException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to protect signup password", ex);
        }
    }

    private String decryptPassword(String cipherText, String nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, Base64.getDecoder().decode(nonce)));
            return new String(cipher.doFinal(Base64.getDecoder().decode(cipherText)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to read signup password", ex);
        }
    }

    private SecretKeySpec encryptionKey() {
        return new SecretKeySpec(sha256(encryptionKeyMaterial), "AES");
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String token) {
        return HexFormat.of().formatHex(sha256(token));
    }

    private byte[] sha256(String value) {
        return sha256(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to hash value", ex);
        }
    }

    private String base64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private String statusMessage(SignupRequest request) {
        if ("EXPIRED".equals(request.status()) || request.expiresAt().isBefore(OffsetDateTime.now())) {
            return "Signup approval link has expired.";
        }
        if ("APPROVED".equals(request.status())) {
            return "Signup request was already approved.";
        }
        if ("REJECTED".equals(request.status())) {
            return "Signup request was already rejected.";
        }
        return "Signup request is pending review.";
    }

    private SignupRequest mapSignupRequest(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SignupRequest(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("encrypted_password"),
                rs.getString("password_nonce"),
                rs.getString("status"),
                rs.getObject("expires_at", OffsetDateTime.class));
    }

    private String requiredToken(String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Signup approval token is required");
        }
        return token.trim();
    }

    private String normalizeEmail(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:3000";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record EncryptedPassword(String cipherText, String nonce) {}

    private record SignupRequest(
            UUID id,
            String email,
            String displayName,
            String encryptedPassword,
            String passwordNonce,
            String status,
            OffsetDateTime expiresAt
    ) {
        SignupRequest withStatus(String nextStatus) {
            return new SignupRequest(id, email, displayName, encryptedPassword, passwordNonce, nextStatus, expiresAt);
        }
    }
}
