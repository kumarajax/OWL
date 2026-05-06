package com.owldrive.api;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
        UserSnapshot user = keycloakId == null ? null : findUserSnapshot(keycloakId);
        UUID userId = user == null ? null : user.id();
        String displayName = resolveDisplayName(jwt, user, email, keycloakId);
        String eventType = eventType(request.getRequestURI());
        jdbc.update(
                """
                INSERT INTO user_access_logs (
                  user_id, display_name, keycloak_id, email, ip_address, country, region, city,
                  latitude, longitude, location_source, user_agent, method, path,
                  status_code, duration_ms, event_type
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                userId,
                displayName,
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

    public List<AccessLogRecord> recent(AccessLogQuery query) {
        int boundedLimit = Math.max(1, Math.min(query.limit(), 500));
        StringBuilder sql = new StringBuilder(
                """
                SELECT l.id, l.user_id,
                       COALESCE(l.display_name, u.display_name, l.email, l.keycloak_id) AS display_name,
                       l.keycloak_id, l.email, l.ip_address, l.country, l.region, l.city,
                       l.latitude, l.longitude, l.location_source, l.user_agent, l.method, l.path,
                       l.status_code, l.duration_ms, l.event_type, l.created_at
                FROM user_access_logs l
                LEFT JOIN users u ON u.id = l.user_id
                WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();

        appendTextFilter(sql, params, "COALESCE(l.display_name, u.display_name, l.email, l.keycloak_id)", query.displayName());
        appendTextFilter(sql, params, "COALESCE(l.display_name, u.display_name, l.email, l.keycloak_id)", query.user());
        appendTextFilter(sql, params, "l.email", query.email());
        appendTextFilter(sql, params, "l.keycloak_id", query.keycloakId());
        appendTextFilter(sql, params, "l.ip_address", query.ipAddress());
        appendTextFilter(sql, params, "l.country", query.country());
        appendTextFilter(sql, params, "l.region", query.region());
        appendTextFilter(sql, params, "l.city", query.city());
        appendTextFilter(sql, params, "l.location_source", query.locationSource());
        appendTextFilter(sql, params, "l.user_agent", query.userAgent());
        appendTextFilter(sql, params, "l.method", query.method());
        appendTextFilter(sql, params, "l.path", query.path());
        appendTextFilter(sql, params, "l.event_type", query.eventType());
        appendExactFilter(sql, params, "l.status_code", query.statusCode());
        appendLowerBound(sql, params, "l.duration_ms", query.durationMinMs());
        appendUpperBound(sql, params, "l.duration_ms", query.durationMaxMs());
        appendTimestampLowerBound(sql, params, "l.created_at", query.createdFrom());
        appendTimestampUpperBound(sql, params, "l.created_at", query.createdTo());

        sql.append(" ORDER BY l.created_at DESC, l.id DESC LIMIT ?");
        params.add(boundedLimit);

        return jdbc.query(sql.toString(), this::mapAccessLog, params.toArray());
    }

    private void appendTextFilter(StringBuilder sql, List<Object> params, String column, String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return;
        }
        sql.append(" AND ").append(column).append(" ILIKE ?");
        params.add('%' + trimmed + '%');
    }

    private void appendExactFilter(StringBuilder sql, List<Object> params, String column, Integer value) {
        if (value == null) {
            return;
        }
        sql.append(" AND ").append(column).append(" = ?");
        params.add(value);
    }

    private void appendLowerBound(StringBuilder sql, List<Object> params, String column, Long value) {
        if (value == null) {
            return;
        }
        sql.append(" AND ").append(column).append(" >= ?");
        params.add(value);
    }

    private void appendUpperBound(StringBuilder sql, List<Object> params, String column, Long value) {
        if (value == null) {
            return;
        }
        sql.append(" AND ").append(column).append(" <= ?");
        params.add(value);
    }

    private void appendTimestampLowerBound(StringBuilder sql, List<Object> params, String column, java.time.OffsetDateTime value) {
        if (value == null) {
            return;
        }
        sql.append(" AND ").append(column).append(" >= ?");
        params.add(value);
    }

    private void appendTimestampUpperBound(StringBuilder sql, List<Object> params, String column, java.time.OffsetDateTime value) {
        if (value == null) {
            return;
        }
        sql.append(" AND ").append(column).append(" <= ?");
        params.add(value);
    }

    private UserSnapshot findUserSnapshot(String keycloakId) {
        var matches = jdbc.query(
                """
                SELECT id, display_name
                FROM users
                WHERE keycloak_id = ?
                """,
                (rs, rowNum) -> new UserSnapshot(
                        rs.getObject("id", UUID.class),
                        rs.getString("display_name")),
                keycloakId);
        return matches.stream().findFirst().orElse(null);
    }

    private String resolveDisplayName(Jwt jwt, UserSnapshot user, String email, String keycloakId) {
        if (user != null && user.displayName() != null && !user.displayName().isBlank()) {
            return user.displayName();
        }
        String name = trimToNull(jwt == null ? null : jwt.getClaimAsString("name"));
        if (name != null) {
            return name;
        }
        String givenName = trimToNull(jwt == null ? null : jwt.getClaimAsString("given_name"));
        String familyName = trimToNull(jwt == null ? null : jwt.getClaimAsString("family_name"));
        if (givenName != null || familyName != null) {
            String fullName = ((givenName == null ? "" : givenName) + " " + (familyName == null ? "" : familyName)).trim();
            if (!fullName.isBlank()) {
                return fullName;
            }
        }
        String preferredUsername = trimToNull(jwt == null ? null : jwt.getClaimAsString("preferred_username"));
        if (preferredUsername != null) {
            return preferredUsername;
        }
        if (email != null) {
            int at = email.indexOf('@');
            if (at > 0) {
                return email.substring(0, at);
            }
            if (!email.isBlank()) {
                return email;
            }
        }
        return keycloakId == null ? "Anonymous" : keycloakId;
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

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
                rs.getString("display_name"),
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

    private record UserSnapshot(UUID id, String displayName) {}
}
