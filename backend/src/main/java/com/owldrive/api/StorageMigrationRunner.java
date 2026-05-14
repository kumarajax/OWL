package com.owldrive.api;

import jakarta.annotation.PostConstruct;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.stereotype.Component;

@Component
public class StorageMigrationRunner {
    private final DataSourceProperties primaryDataSourceProperties;
    private final PostgresShardProperties postgresShardProperties;

    public StorageMigrationRunner(
            @Qualifier("spring.datasource-org.springframework.boot.autoconfigure.jdbc.DataSourceProperties")
            DataSourceProperties primaryDataSourceProperties,
            PostgresShardProperties postgresShardProperties) {
        this.primaryDataSourceProperties = primaryDataSourceProperties;
        this.postgresShardProperties = postgresShardProperties;
    }

    @PostConstruct
    public void migrate() {
        migrateWithRetry(primaryDataSourceProperties.getUrl(), primaryDataSourceProperties.getUsername(), primaryDataSourceProperties.getPassword());
        for (PostgresShardProperties.Shard shard : postgresShardProperties.getShards()) {
            migrateWithRetry(shard.getJdbcUrl(), shard.getUsername(), shard.getPassword());
        }
    }

    private void migrateWithRetry(String jdbcUrl, String username, String password) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= 30; attempt += 1) {
            try {
                Flyway.configure()
                        .dataSource(jdbcUrl, username, password)
                        .locations("classpath:db/migration")
                        .schemas("app")
                        .createSchemas(true)
                        .baselineOnMigrate(true)
                        .load()
                        .migrate();
                return;
            } catch (RuntimeException ex) {
                lastError = ex;
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        throw lastError == null ? new IllegalStateException("Unable to migrate storage shards") : lastError;
    }
}
