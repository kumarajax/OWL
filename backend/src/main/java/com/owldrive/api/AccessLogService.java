package com.owldrive.api;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class AccessLogService {
    private final JdbcTemplate jdbc;
    private final TelemetryRetentionService telemetryRetentionService;

    public AccessLogService(JdbcTemplate jdbc, TelemetryRetentionService telemetryRetentionService) {
        this.jdbc = jdbc;
        this.telemetryRetentionService = telemetryRetentionService;
    }

    public void record(HttpServletRequest request, Jwt jwt, int statusCode, long durationMs) {
        String keycloakId = jwt == null ? null : jwt.getSubject();
        String email = jwt == null ? null : jwt.getClaimAsString("email");
        UUID userId = keycloakId == null ? null : findUserId(keycloakId);
        String eventType = eventType(request.getRequestURI());
        jdbc.update(
                """
                INSERT INTO user_access_logs (
                  user_id, keycloak_id, email, ip_address, country, region, city,
                  latitude, longitude, location_source, user_agent, method, path,
                  status_code, duration_ms, event_type
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                userId,
                keycloakId,
                email,
                clientIp(request),
                blankToNull(firstHeaderValue(request, "CF-IPCountry")),
                blankToNull(firstHeaderValue(request, "CF-Region")),
                blankToNull(firstHeaderValue(request, "CF-IPCity")),
                parseDouble(firstHeaderValue(request, "CF-IPLatitude")),
                parseDouble(firstHeaderValue(request, "CF-IPLongitude")),
                locationSource(request),
                truncate(blankToNull(request.getHeader("User-Agent")), 1000),
                request.getMethod(),
                pathWithQuery(request),
                statusCode,
                durationMs,
                eventType);
        telemetryRetentionService.pruneToRetention(telemetryRetentionService.currentMaxRetentionRows());
    }

    public List<AccessLogRecord> recent(int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        return jdbc.query(
                """
                SELECT id, user_id, keycloak_id, email, ip_address, country, region, city,
                       latitude, longitude, location_source, user_agent, method, path,
                       status_code, duration_ms, event_type, created_at
                FROM user_access_logs
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """,
                this::mapAccessLog,
                boundedLimit);
    }

    private UUID findUserId(String keycloakId) {
        return jdbc.query(
                """
                SELECT id
                FROM users
                WHERE keycloak_id = ?
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                keycloakId).stream().findFirst().orElse(null);
    }

    private String eventType(String path) {
        if ("/api/me".equals(path)) {
            return "APP_LOGIN";
        }
        if (path != null && path.startsWith("/api/public/shares/")) {
            return "PUBLIC_SHARE_DOWNLOAD";
        }
        if (path != null && path.contains("/upload")) {
            return "FILE_UPLOAD";
        }
        if (path != null && path.contains("/download")) {
            return "FILE_DOWNLOAD";
        }
        return "API_ACCESS";
    }

    private String clientIp(HttpServletRequest request) {
        String cfConnectingIp = firstHeaderValue(request, "CF-Connecting-IP");
        if (cfConnectingIp != null && !cfConnectingIp.isBlank()) {
            return cfConnectingIp;
        }
        String forwardedFor = firstHeaderValue(request, "X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor;
        }
        String realIp = firstHeaderValue(request, "X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }

    private String pathWithQuery(HttpServletRequest request) {
        String path = request.getRequestURI();
        String query = request.getQueryString();
        if (query == null || query.isBlank()) {
            return path;
        }
        return path + "?" + query;
    }

    private String locationSource(HttpServletRequest request) {
        if (request.getHeader("CF-IPCountry") != null) {
            return "cloudflare";
        }
        return null;
    }

    private String firstHeaderValue(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.split(",")[0].trim();
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private AccessLogRecord mapAccessLog(ResultSet rs, int rowNum) throws SQLException {
        Object latitudeValue = rs.getObject("latitude");
        Double latitude = latitudeValue instanceof Number number ? number.doubleValue() : null;
        Object longitudeValue = rs.getObject("longitude");
        Double longitude = longitudeValue instanceof Number number ? number.doubleValue() : null;
        Object statusCodeValue = rs.getObject("status_code");
        Integer statusCode = statusCodeValue instanceof Number number ? number.intValue() : null;
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new AccessLogRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("keycloak_id"),
                rs.getString("email"),
                rs.getString("ip_address"),
                rs.getString("country"),
                rs.getString("region"),
                rs.getString("city"),
                latitude,
                longitude,
                rs.getString("location_source"),
                rs.getString("user_agent"),
                rs.getString("method"),
                rs.getString("path"),
                statusCode,
                rs.getLong("duration_ms"),
                rs.getString("event_type"),
                createdAt == null ? null : createdAt.toInstant().atOffset(ZoneOffset.UTC));
    }
}
