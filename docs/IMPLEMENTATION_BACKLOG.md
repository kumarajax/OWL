# Implementation Backlog

## Phase 1

- Implement Keycloak login in frontend.
- Map Keycloak subject to `users`.
- Create personal tenant and root folder on first login.
- Implement folder list/create/rename/move/delete.
- Implement resumable upload session.
- Implement chunk encryption and storage-node write path.
- Implement download reassembly.
- Emit audit events.

## Phase 2

- Direct user/group permissions.
- Shared with Me view.
- Public links with expiration and password.
- Access request workflow.
- Admin sharing policies.

## Phase 3

- OpenSearch indexing pipeline.
- Permission-aware search filters.
- File previews for PDF/image/text.
- OCR extraction worker.

## Phase 4

- AI summarization worker.
- Auto-tagging and classification.
- Duplicate detection.
- DLP scans and share blocking.

## Phase 5

- Organization admin dashboard.
- MFA enforcement policy.
- Device/session view.
- Quotas and retention policies.
- Notifications.

## Phase 6

- Multi-node storage repair.
- Rebalancing.
- Load tests.
- Chaos tests for disk/node failure.
- Kubernetes manifests.

