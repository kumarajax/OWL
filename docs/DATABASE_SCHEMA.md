# Database Schema

PostgreSQL stores logical metadata, permissions, sharing, audit, storage placement, and AI metadata. Physical chunk data lives on storage nodes.

## Core Tables

- `tenants`: personal or organization tenant boundary.
- `users`: application profile mapped to Keycloak subject.
- `groups`: organization groups.
- `group_members`: users in groups.
- `nodes`: storage nodes.
- `storage_volumes`: disks mounted on storage nodes.
- `file_objects`: files and folders.
- `file_versions`: version metadata.
- `chunks`: content chunks and checksums.
- `chunk_locations`: replica placement.
- `permissions`: direct object permissions.
- `sharing_links`: public or restricted links.
- `audit_logs`: immutable security and activity events.
- `ai_metadata`: summaries, tags, DLP classifications, embeddings references.
- `upload_sessions`: resumable upload state.

## Indexing Strategy

- UUID primary keys.
- Tenant-scoped indexes on every user-facing object table.
- Parent folder indexes for listing.
- Owner and recent/starred indexes.
- Permission indexes by subject.
- Search index documents store object ID and ACL materialization.

## Soft Delete

`file_objects.deleted_at` marks trash. Permanent deletion requires retention policy checks and background chunk garbage collection.

