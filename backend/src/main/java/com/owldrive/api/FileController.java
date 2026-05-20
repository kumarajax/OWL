package com.owldrive.api;

import java.util.UUID;
import java.util.Map;
import java.util.List;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
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
    List<FileRecord> upload(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("parentFolderId") UUID parentFolderId,
            @RequestParam("file") List<MultipartFile> files,
            @RequestParam(value = "relativePath", required = false) String relativePath) {
        return fileService.upload(jwt, parentFolderId, files, relativePath);
    }

    @PostMapping("/upload-chunk")
    ResponseEntity<Void> uploadChunk(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("uploadId") String uploadId,
            @RequestParam("chunkIndex") int chunkIndex,
            @RequestParam("totalChunks") int totalChunks,
            @RequestParam("totalSizeBytes") long totalSizeBytes,
            @RequestParam("chunk") MultipartFile chunk) {
        fileService.uploadChunk(jwt, uploadId, chunkIndex, totalChunks, totalSizeBytes, chunk);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/complete-chunked-upload")
    FileRecord completeChunkedUpload(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("parentFolderId") UUID parentFolderId,
            @RequestParam("uploadId") String uploadId,
            @RequestParam("fileName") String fileName,
            @RequestParam(value = "relativePath", required = false) String relativePath,
            @RequestParam(value = "contentType", required = false) String contentType,
            @RequestParam("totalChunks") int totalChunks,
            @RequestParam("totalSizeBytes") long totalSizeBytes) {
        return fileService.completeChunkedUpload(
                jwt, parentFolderId, uploadId, fileName, relativePath, contentType, totalChunks, totalSizeBytes);
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

    @GetMapping("/{fileId}/thumbnail")
    ResponseEntity<InputStreamResource> thumbnail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("fileId") UUID fileId) {
        StorageDownload download = fileService.thumbnail(jwt, fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .contentLength(download.sizeBytes())
                .body(download.resource());
    }

    @DeleteMapping("/{fileId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable("fileId") UUID fileId) {
        fileService.delete(jwt, fileId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{fileId}")
    FileRecord update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("fileId") UUID fileId,
            @RequestBody Map<String, Object> request) {
        return fileService.update(jwt, fileId, request);
    }
}
