package com.owldrive.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/folders")
public class FolderController {
    private final FolderService folderService;

    public FolderController(FolderService folderService) {
        this.folderService = folderService;
    }

    @GetMapping("/{folderId}/children")
    List<DriveItemRecord> children(@AuthenticationPrincipal Jwt jwt, @PathVariable("folderId") UUID folderId) {
        return folderService.children(jwt, folderId);
    }

    @PostMapping
    FolderRecord create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateFolderRequest request) {
        return folderService.create(jwt, request);
    }

    @PatchMapping("/{folderId}")
    FolderRecord update(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("folderId") UUID folderId,
            @RequestBody Map<String, Object> request) {
        return folderService.update(jwt, folderId, request);
    }

    @DeleteMapping("/{folderId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable("folderId") UUID folderId) {
        folderService.delete(jwt, folderId);
        return ResponseEntity.noContent().build();
    }
}
