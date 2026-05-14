package com.owldrive.api;

import com.zaxxer.hikari.HikariDataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

@Configuration
@EnableConfigurationProperties({PostgresShardProperties.class, MinioPoolProperties.class})
public class StorageTopologyConfig {
    @Bean
    @Primary
    public DataSource dataSource(
            @Qualifier("spring.datasource-org.springframework.boot.autoconfigure.jdbc.DataSourceProperties")
            DataSourceProperties primaryDataSourceProperties,
            PostgresShardProperties postgresShardProperties) {
        Map<Object, Object> targets = new LinkedHashMap<>();
        targets.put("primary", buildDataSource(primaryDataSourceProperties));
        List<PostgresShardProperties.Shard> shards = postgresShardProperties.getShards();
        for (PostgresShardProperties.Shard shard : shards) {
            targets.put(shard.getName(), buildDataSource(shard));
        }
        ShardRoutingDataSource routingDataSource = new ShardRoutingDataSource();
        routingDataSource.setTargetDataSources(targets);
        routingDataSource.setDefaultTargetDataSource(targets.values().iterator().next());
        routingDataSource.afterPropertiesSet();
        return routingDataSource;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public ShardJdbcRegistry shardJdbcRegistry(
            @Qualifier("spring.datasource-org.springframework.boot.autoconfigure.jdbc.DataSourceProperties")
            DataSourceProperties primaryDataSourceProperties,
            PostgresShardProperties postgresShardProperties) {
        Map<String, JdbcTemplate> templates = new LinkedHashMap<>();
        templates.put("primary", new JdbcTemplate(buildDataSource(primaryDataSourceProperties)));
        for (PostgresShardProperties.Shard shard : postgresShardProperties.getShards()) {
            templates.put(shard.getName(), new JdbcTemplate(buildDataSource(shard)));
        }
        return new ShardJdbcRegistry(templates);
    }

    private DataSource buildDataSource(DataSourceProperties properties) {
        HikariDataSource dataSource = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        dataSource.setPoolName("owl-primary");
        return dataSource;
    }

    private DataSource buildDataSource(PostgresShardProperties.Shard shard) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(shard.getJdbcUrl());
        dataSource.setUsername(shard.getUsername());
        dataSource.setPassword(shard.getPassword());
        dataSource.setPoolName("owl-" + shard.getName());
        return dataSource;
    }

    static final class ShardRoutingDataSource extends AbstractRoutingDataSource {
        @Override
        protected Object determineCurrentLookupKey() {
            String shardId = ShardContext.currentShard();
            return shardId == null || shardId.isBlank() ? null : shardId;
        }
    }
}
