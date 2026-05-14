package com.owldrive.api;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserCapacityService {
    private final ShardJdbcRegistry shardJdbcRegistry;
    private final long maxUsers;
    private final ReentrantLock registrationLock = new ReentrantLock();

    public UserCapacityService(
            ShardJdbcRegistry shardJdbcRegistry,
            @Value("${app.users.max-users:1000}") long maxUsers) {
        this.shardJdbcRegistry = shardJdbcRegistry;
        this.maxUsers = maxUsers;
    }

    public RegistrationStatusRecord registrationStatus() {
        long activeUsers = activeUserCount();
        return new RegistrationStatusRecord(activeUsers, maxUsers, activeUsers < maxUsers);
    }

    public void requireAvailableSlot() {
        registrationLock.lock();
        try {
            if (activeUserCount() >= maxUsers) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Max usage reached");
            }
        } finally {
            registrationLock.unlock();
        }
    }

    private long activeUserCount() {
        return shardJdbcRegistry.countActiveUsers();
    }
}
