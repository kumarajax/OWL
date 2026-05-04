package com.owldrive.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FileShareController {
    private final FileShareService fileShareService;

    public FileShareController(FileShareService fileShareService) {
        this.fileShareService = fileShareService;
    }

    @PostMapping("/api/files/{fileId}/shares")
    FileShareRecord create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("fileId") UUID fileId,
            @RequestBody(required = false) CreateFileShareRequest request,
            HttpServletRequest servletRequest) {
        String publicUrlBase = publicUrlBase(servletRequest);
        return fileShareService.create(jwt, fileId, request, publicUrlBase);
    }

    @GetMapping("/api/files/{fileId}/shares")
    List<FileShareRecord> list(@AuthenticationPrincipal Jwt jwt, @PathVariable("fileId") UUID fileId) {
        return fileShareService.list(jwt, fileId);
    }

    @DeleteMapping("/api/files/{fileId}/shares/{shareId}")
    ResponseEntity<Void> revoke(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("fileId") UUID fileId,
            @PathVariable("shareId") UUID shareId) {
        fileShareService.revoke(jwt, fileId, shareId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/public/shares/{token}/download")
    ResponseEntity<InputStreamResource> publicDownload(@PathVariable("token") String token) {
        DownloadableFile download = fileShareService.publicDownload(token);
        String contentType = download.file().contentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : download.file().contentType();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(download.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.file().originalName())
                        .build()
                .toString())
                .body(download.resource());
    }

    private String publicUrlBase(HttpServletRequest request) {
        String proto = firstHeaderValue(request, "X-Forwarded-Proto");
        String host = firstHeaderValue(request, "X-Forwarded-Host");
        if (proto == null || proto.isBlank()) {
            proto = request.getScheme();
        }
        if (host == null || host.isBlank()) {
            host = request.getHeader("Host");
        }
        if (host == null || host.isBlank()) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        return proto + "://" + host;
    }

    private String firstHeaderValue(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.split(",")[0].trim();
    }
}
