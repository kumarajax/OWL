package com.owldrive.api;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
public class FileController {
    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload")
    FileRecord upload(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("parentFolderId") UUID parentFolderId,
            @RequestParam("file") MultipartFile file) {
        return fileService.upload(jwt, parentFolderId, file);
    }

    @GetMapping("/{fileId}/download")
    ResponseEntity<InputStreamResource> download(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("fileId") UUID fileId) {
        DownloadableFile download = fileService.download(jwt, fileId);
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

    @DeleteMapping("/{fileId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable("fileId") UUID fileId) {
        fileService.delete(jwt, fileId);
        return ResponseEntity.noContent().build();
    }
}
