package com.owldrive.api;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public AccountService(
            JdbcTemplate jdbc,
            ProvisioningService provisioningService,
            LocalStorageService localStorageService) {
        this.jdbc = jdbc;
        this.provisioningService = provisioningService;
        this.localStorageService = localStorageService;
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
