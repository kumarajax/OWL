# Testing Strategy

## Unit Tests

- Permission calculation.
- Path normalization.
- Upload session state.
- Chunk manifest generation.
- Sharing link validation.
- DLP classification rules.

## Integration Tests

- Keycloak JWT validation.
- PostgreSQL migrations.
- Upload/download lifecycle.
- Folder inheritance permissions.
- Search index permission filtering.
- Storage node heartbeat and chunk commit.

## Security Tests

- Unauthorized access attempts.
- Tenant isolation.
- Path traversal attempts.
- Broken object-level authorization.
- Rate limit and lockout behavior.
- Sharing policy enforcement.

## Load Tests

- Concurrent folder listing.
- Resumable uploads.
- Chunk replication.
- Search queries.
- Permission-heavy shared folder trees.

