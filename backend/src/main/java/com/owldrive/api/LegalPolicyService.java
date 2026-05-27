package com.owldrive.api;

import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LegalPolicyService {
    private final ShardJdbcRegistry shardJdbcRegistry;
    private final ProvisioningService provisioningService;
    private final String currentVersion;

    public LegalPolicyService(
            ShardJdbcRegistry shardJdbcRegistry,
            ProvisioningService provisioningService,
            @Value("${app.legal.current-version:2026-05-26}") String currentVersion) {
        this.shardJdbcRegistry = shardJdbcRegistry;
        this.provisioningService = provisioningService;
        this.currentVersion = currentVersion;
    }

    public String currentVersion() {
        return currentVersion;
    }

    @Transactional
    public LegalAcceptanceRecord status(Jwt jwt) {
        UserRecord user = provisioningService.ensureUser(jwt);
        return status(user);
    }

    @Transactional
    public LegalAcceptanceRecord accept(Jwt jwt, LegalAcceptanceRequest request) {
        if (request == null || request.version() == null || !request.version().equals(currentVersion)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current legal terms version is required");
        }
        LocatedUserRecord located = provisioningService.locateUser(jwt);
        OffsetDateTime acceptedAt = shardJdbcRegistry.jdbc(located.shardId()).queryForObject(
                """
                UPDATE users
                SET terms_version = ?, terms_accepted_at = now()
                WHERE id = ? AND deactivated_at IS NULL
                RETURNING terms_accepted_at
                """,
                OffsetDateTime.class,
                currentVersion,
                located.user().id());
        return new LegalAcceptanceRecord(currentVersion, true, currentVersion, acceptedAt);
    }

    public LegalAcceptanceRecord status(UserRecord user) {
        boolean accepted = user != null
                && user.termsAcceptedAt() != null
                && currentVersion.equals(user.termsVersion());
        return new LegalAcceptanceRecord(
                currentVersion,
                accepted,
                user == null ? null : user.termsVersion(),
                user == null ? null : user.termsAcceptedAt());
    }
}
