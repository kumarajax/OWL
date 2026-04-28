package com.owldrive.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class UserCapacityService {
    private final JdbcTemplate jdbc;
    private final long maxUsers;

    public UserCapacityService(
            JdbcTemplate jdbc,
            @Value("${app.users.max-users:1000}") long maxUsers) {
        this.jdbc = jdbc;
        this.maxUsers = maxUsers;
    }

    public RegistrationStatusRecord registrationStatus() {
        long activeUsers = activeUserCount();
        return new RegistrationStatusRecord(activeUsers, maxUsers, activeUsers < maxUsers);
    }

    public void requireAvailableSlot() {
        jdbc.execute("SELECT pg_advisory_xact_lock(hashtext('owl_drive_user_capacity'))");
        if (activeUserCount() >= maxUsers) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Max usage reached");
        }
    }

    private long activeUserCount() {
        Long count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM users
                WHERE deactivated_at IS NULL
                """,
                Long.class);
        return count == null ? 0 : count;
    }
}
