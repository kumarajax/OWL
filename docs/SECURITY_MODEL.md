# Security Model

## Identity

Keycloak is the source of truth for authentication, OAuth federation, MFA, sessions, device tracking, account lockout, and token issuance.

Supported identity flows:

- Email/password.
- Google, Microsoft, Apple OAuth through Keycloak identity brokering.
- MFA through Keycloak policies.
- JWT access tokens.
- Refresh token rotation through Keycloak.

## Authorization

Spring Security validates JWTs and enforces coarse-grained endpoint authorization. Application services enforce object-level authorization.

Every request validates:

- Authenticated principal.
- Tenant membership.
- File or folder existence.
- Effective permission for the requested action.
- Admin and sharing policy restrictions.
- Trash/deleted state.
- Link password/expiration where applicable.

## Permission Roles

Ordered from highest to lowest:

- Owner
- Manager
- Editor
- Commenter
- Viewer
- Restricted Viewer

Permissions may be direct, inherited from parent folder, group-based, link-based, or admin policy-derived. Deny and policy restrictions override grants.

## Data Protection

- TLS required for all traffic.
- No physical file paths exposed to clients.
- UUID-based storage paths only.
- Sensitive metadata encrypted where needed.
- Files encrypted before disk write.
- Chunk checksums validated on upload, read, repair, and replication.
- Passwords never stored by OWL Drive; Keycloak stores credential hashes.

## Upload Security

- Validate size limits and MIME hints.
- Never trust client-provided MIME type.
- Virus scan uploads before broad sharing.
- Quarantine suspicious files.
- Prevent path traversal through UUID storage and logical path normalization.
- Enforce DLP checks before external sharing.

## Rate Limiting And Abuse Controls

- Redis-backed rate limits per IP, user, tenant, and endpoint.
- Account lockout/CAPTCHA handled by Keycloak after failed attempts.
- Upload throttling per tenant quota and policy.

## Audit Logging

Immutable audit events are emitted for:

- Login and logout activity.
- File view/download/upload/delete/restore.
- Permission and sharing changes.
- Admin actions.
- Policy changes.
- Storage repair, corruption, and replica failures.

