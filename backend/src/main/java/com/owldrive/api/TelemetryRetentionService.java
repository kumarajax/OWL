package com.owldrive.api;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class TelemetryRetentionService {
    static final long DEFAULT_MAX_RETENTION_ROWS = 100_000L;
    static final long MAX_ALLOWED_RETENTION_ROWS = 1_000_000L;

    private final JdbcTemplate jdbc;
    private final AtomicLong retentionCache = new AtomicLong(-1L);

    public TelemetryRetentionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TelemetryRetentionRecord current() {
        ensureRow();
        return jdbc.queryForObject(
                """
                SELECT max_retention_rows, updated_at
                FROM telemetry_settings
                WHERE id = 1
                """,
                (rs, rowNum) -> {
                    long maxRetentionRows = normalize(rs.getLong("max_retention_rows"));
                    retentionCache.set(maxRetentionRows);
                    return new TelemetryRetentionRecord(
                            maxRetentionRows,
                            MAX_ALLOWED_RETENTION_ROWS,
                            rs.getObject("updated_at", OffsetDateTime.class));
                });
    }

    public TelemetryRetentionRecord update(long maxRetentionRows) {
        long normalized = normalize(maxRetentionRows);
        ensureRow();
        jdbc.update(
                """
                UPDATE telemetry_settings
                SET max_retention_rows = ?,
                    updated_at = now()
                WHERE id = 1
                """,
                normalized);
        retentionCache.set(normalized);
        pruneToRetention(normalized);
        return current();
    }

    public void pruneToRetention(long maxRetentionRows) {
        long normalized = normalize(maxRetentionRows);
        jdbc.update(
                """
                WITH stale AS (
                  SELECT id
                  FROM user_access_logs
                  ORDER BY created_at DESC, id DESC
                  OFFSET ?
                )
                DELETE FROM user_access_logs AS logs
                USING stale
                WHERE logs.id = stale.id
                """,
                normalized);
    }

    public long currentMaxRetentionRows() {
        long cached = retentionCache.get();
        if (cached > 0) {
            return cached;
        }
        synchronized (this) {
            cached = retentionCache.get();
            if (cached > 0) {
                return cached;
            }
            ensureRow();
            Long loaded = jdbc.queryForObject(
                    """
                    SELECT max_retention_rows
                    FROM telemetry_settings
                    WHERE id = 1
                    """,
                    Long.class);
            long normalized = loaded == null ? DEFAULT_MAX_RETENTION_ROWS : normalize(loaded);
            retentionCache.set(normalized);
            return normalized;
        }
    }

    private void ensureRow() {
        jdbc.update(
                """
                INSERT INTO telemetry_settings (id, max_retention_rows)
                VALUES (1, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                DEFAULT_MAX_RETENTION_ROWS);
    }

    private long normalize(long maxRetentionRows) {
        if (maxRetentionRows < 1 || maxRetentionRows > MAX_ALLOWED_RETENTION_ROWS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Max retention rows must be between 1 and " + MAX_ALLOWED_RETENTION_ROWS);
        }
        return maxRetentionRows;
    }
}
