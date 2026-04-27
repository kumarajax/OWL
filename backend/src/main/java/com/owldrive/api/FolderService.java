package com.owldrive.api;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FolderService {
    private final JdbcTemplate jdbc;
    private final ProvisioningService provisioningService;

    public FolderService(JdbcTemplate jdbc, ProvisioningService provisioningService) {
        this.jdbc = jdbc;
        this.provisioningService = provisioningService;
    }

    @Transactional(readOnly = true)
    public List<DriveItemRecord> children(Jwt jwt, UUID folderId) {
        UserRecord user = provisioningService.ensureUser(jwt);
        requireOwnedActiveFolder(user, folderId);
        List<DriveItemRecord> items = new ArrayList<>();
        List<FolderRecord> folders = jdbc.query(
                """
                SELECT id, name, owner_id, parent_id, created_at, updated_at, deleted_at
                FROM folders
                WHERE owner_id = ? AND parent_id = ? AND deleted_at IS NULL
                ORDER BY lower(name), name
                """,
                this::mapFolder,
                user.id(),
                folderId);
        folders.stream().map(DriveItemRecord::folder).forEach(items::add);

        jdbc.query(
                """
                SELECT id, owner_id, parent_folder_id, original_name, storage_key, content_type,
                       size_bytes, checksum_sha256, created_at, updated_at, deleted_at
                FROM files
                WHERE owner_id = ? AND parent_folder_id = ? AND deleted_at IS NULL
                ORDER BY lower(original_name), original_name
                """,
                this::mapFile,
                user.id(),
                folderId).stream().map(DriveItemRecord::file).forEach(items::add);
        return items;
    }

    @Transactional
    public FolderRecord create(Jwt jwt, CreateFolderRequest request) {
        UserRecord user = provisioningService.ensureUser(jwt);
        UUID parentId = request == null ? null : request.parentId();
        if (parentId == null) {
            throw badRequest("parentId is required");
        }
        FolderRecord parent = requireOwnedActiveFolder(user, parentId);
        String name = validateName(request.name());
        rejectDuplicateName(user.id(), parent.id(), name, null);

        try {
            return jdbc.queryForObject(
                    """
                    INSERT INTO folders (name, owner_id, parent_id)
                    VALUES (?, ?, ?)
                    RETURNING id, name, owner_id, parent_id, created_at, updated_at, deleted_at
                    """,
                    this::mapFolder,
                    name,
                    user.id(),
                    parent.id());
        } catch (DataIntegrityViolationException ex) {
            throw badRequest("A folder with this name already exists here");
        }
    }

    @Transactional
    public FolderRecord update(Jwt jwt, UUID folderId, Map<String, Object> request) {
        UserRecord user = provisioningService.ensureUser(jwt);
        FolderRecord folder = requireOwnedActiveFolder(user, folderId);
        if (request == null || request.isEmpty()) {
            throw badRequest("At least one field is required");
        }

        boolean hasName = request.containsKey("name");
        boolean hasParentId = request.containsKey("parentId");
        if (!hasName && !hasParentId) {
            throw badRequest("At least one supported field is required");
        }

        String newName = folder.name();
        UUID newParentId = folder.parentId();

        if (hasName) {
            Object rawName = request.get("name");
            if (!(rawName instanceof String value)) {
                throw badRequest("name must be a string");
            }
            newName = validateName(value);
        }

        if (hasParentId) {
            if (folder.parentId() == null) {
                throw badRequest("Root folder cannot be moved");
            }
            Object rawParentId = request.get("parentId");
            if (!(rawParentId instanceof String value) || value.isBlank()) {
                throw badRequest("parentId must be a UUID string");
            }
            newParentId = parseUuid(value, "parentId");
            if (folder.id().equals(newParentId)) {
                throw badRequest("Folder cannot be moved into itself");
            }
            requireOwnedActiveFolder(user, newParentId);
            if (isFolderInSubtree(user.id(), folder.id(), newParentId)) {
                throw badRequest("Folder cannot be moved into one of its descendants");
            }
        }

        rejectDuplicateName(user.id(), newParentId, newName, folder.id());

        return jdbc.queryForObject(
                """
                UPDATE folders
                SET name = ?, parent_id = ?, updated_at = now()
                WHERE id = ?
                RETURNING id, name, owner_id, parent_id, created_at, updated_at, deleted_at
                """,
                this::mapFolder,
                newName,
                newParentId,
                folder.id());
    }

    @Transactional
    public void delete(Jwt jwt, UUID folderId) {
        UserRecord user = provisioningService.ensureUser(jwt);
        FolderRecord folder = requireOwnedActiveFolder(user, folderId);
        if (folder.parentId() == null) {
            throw badRequest("Root folder cannot be deleted");
        }

        jdbc.update(
                """
                WITH RECURSIVE subtree AS (
                  SELECT id
                  FROM folders
                  WHERE id = ? AND owner_id = ? AND deleted_at IS NULL
                  UNION ALL
                  SELECT child.id
                  FROM folders child
                  JOIN subtree parent ON child.parent_id = parent.id
                  WHERE child.owner_id = ? AND child.deleted_at IS NULL
                )
                UPDATE folders
                SET deleted_at = now(), updated_at = now()
                WHERE id IN (SELECT id FROM subtree)
                """,
                folder.id(),
                user.id(),
                user.id());

        jdbc.update(
                """
                WITH RECURSIVE subtree AS (
                  SELECT id
                  FROM folders
                  WHERE id = ? AND owner_id = ?
                  UNION ALL
                  SELECT child.id
                  FROM folders child
                  JOIN subtree parent ON child.parent_id = parent.id
                  WHERE child.owner_id = ?
                )
                UPDATE files
                SET deleted_at = now(), updated_at = now()
                WHERE owner_id = ?
                  AND parent_folder_id IN (SELECT id FROM subtree)
                  AND deleted_at IS NULL
                """,
                folder.id(),
                user.id(),
                user.id(),
                user.id());
    }

    FolderRecord requireOwnedActiveFolder(UserRecord user, UUID folderId) {
        FolderRecord folder = jdbc.query(
                """
                SELECT id, name, owner_id, parent_id, created_at, updated_at, deleted_at
                FROM folders
                WHERE id = ?
                """,
                this::mapFolder,
                folderId).stream().findFirst().orElseThrow(() -> notFound("Folder not found"));
        if (folder.deletedAt() != null) {
            throw notFound("Folder not found");
        }
        if (!folder.ownerId().equals(user.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Folder belongs to another user");
        }
        return folder;
    }

    private String validateName(String rawName) {
        if (rawName == null) {
            throw badRequest("Folder name is required");
        }
        String name = rawName.trim();
        if (name.isEmpty()) {
            throw badRequest("Folder name is required");
        }
        if (name.length() > 255) {
            throw badRequest("Folder name must be 255 characters or fewer");
        }
        return name;
    }

    private void rejectDuplicateName(UUID ownerId, UUID parentId, String name, UUID currentFolderId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*)
                FROM folders
                WHERE owner_id = ?
                  AND parent_id = ?
                  AND deleted_at IS NULL
                  AND lower(name) = lower(?)
                  AND (?::uuid IS NULL OR id <> ?::uuid)
                """,
                Integer.class,
                ownerId,
                parentId,
                name,
                currentFolderId,
                currentFolderId);
        if (count != null && count > 0) {
            throw badRequest("A folder with this name already exists here");
        }
    }

    private boolean isFolderInSubtree(UUID ownerId, UUID rootFolderId, UUID candidateFolderId) {
        Boolean exists = jdbc.queryForObject(
                """
                WITH RECURSIVE subtree AS (
                  SELECT id
                  FROM folders
                  WHERE id = ? AND owner_id = ? AND deleted_at IS NULL
                  UNION ALL
                  SELECT child.id
                  FROM folders child
                  JOIN subtree parent ON child.parent_id = parent.id
                  WHERE child.owner_id = ? AND child.deleted_at IS NULL
                )
                SELECT EXISTS (SELECT 1 FROM subtree WHERE id = ?)
                """,
                Boolean.class,
                rootFolderId,
                ownerId,
                ownerId,
                candidateFolderId);
        return Boolean.TRUE.equals(exists);
    }

    private UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw badRequest(field + " must be a valid UUID");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private FolderRecord mapFolder(ResultSet rs, int rowNum) throws SQLException {
        return new FolderRecord(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getObject("owner_id", UUID.class),
                rs.getObject("parent_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("deleted_at", OffsetDateTime.class));
    }

    private FileRecord mapFile(ResultSet rs, int rowNum) throws SQLException {
        return new FileRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getObject("parent_folder_id", UUID.class),
                rs.getString("original_name"),
                rs.getString("storage_key"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("checksum_sha256"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("deleted_at", OffsetDateTime.class));
    }
}
