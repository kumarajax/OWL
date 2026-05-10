package com.owldrive.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.storage.local")
public record StorageProperties(String configFile, String fallbackRoot) {
    public List<Path> resolvedRoots() {
        Set<Path> resolved = new LinkedHashSet<>();
        if (configFile != null && !configFile.isBlank()) {
            Path configPath = Path.of(configFile).toAbsolutePath().normalize();
            resolved.addAll(readConfigFile(configPath));
        }
        if (resolved.isEmpty() && fallbackRoot != null && !fallbackRoot.isBlank()) {
            resolved.add(Path.of(fallbackRoot).toAbsolutePath().normalize());
        }
        if (resolved.isEmpty()) {
            resolved.add(Path.of("./backend/data/storage").toAbsolutePath().normalize());
        }
        return List.copyOf(resolved);
    }

    private List<Path> readConfigFile(Path configPath) {
        if (!Files.exists(configPath)) {
            return List.of();
        }
        try {
            return Files.readAllLines(configPath, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .filter(line -> !line.startsWith("#"))
                    .map(this::toAbsolutePath)
                    .distinct()
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read storage roots config: " + configPath, ex);
        }
    }

    private Path toAbsolutePath(String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            return path.toAbsolutePath().normalize();
        }
        return path.normalize();
    }
}
