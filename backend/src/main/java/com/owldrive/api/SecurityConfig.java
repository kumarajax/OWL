package com.owldrive.api;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.PatternMatchUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    @Value("${app.cors.allowed-origin-patterns:}")
    private String allowedOriginPatterns;

    @Value("${app.security.oauth2.jwt.allowed-issuers:}")
    private String allowedIssuers;

    @Value("${app.security.oauth2.jwt.allowed-issuer-patterns:}")
    private String allowedIssuerPatterns;

    @Value("${app.security.oauth2.jwt.jwk-set-uri:http://localhost:8080/realms/owldrive/protocol/openid-connect/certs}")
    private String jwkSetUri;

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .cors(cors -> {})
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                allowedIssuerValidator()));
        return decoder;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = commaSeparatedValues(allowedOrigins).toList();
        List<String> originPatterns = commaSeparatedValues(allowedOriginPatterns).toList();
        if (!origins.isEmpty()) {
            config.setAllowedOrigins(origins);
        }
        if (!originPatterns.isEmpty()) {
            config.setAllowedOriginPatterns(originPatterns);
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private OAuth2TokenValidator<Jwt> allowedIssuerValidator() {
        Set<String> issuers = commaSeparatedValues(allowedIssuers)
                .collect(java.util.stream.Collectors.toSet());
        List<String> issuerPatterns = commaSeparatedValues(allowedIssuerPatterns).toList();
        return token -> {
            String issuer = token.getIssuer() == null ? null : token.getIssuer().toString();
            if (issuer != null && (issuers.contains(issuer) || PatternMatchUtils.simpleMatch(issuerPatterns.toArray(String[]::new), issuer))) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "The token issuer is not allowed",
                    null));
        };
    }

    private Stream<String> commaSeparatedValues(String value) {
        if (value == null || value.isBlank()) {
            return Stream.empty();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank());
    }
}
