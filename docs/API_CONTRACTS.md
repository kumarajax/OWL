# API Contracts

The canonical OpenAPI document is in `api/openapi.yaml`.

## API Groups

- Auth/Profile: current user, device/session views.
- Files/Folders: upload, download, folder CRUD, copy, move, rename, trash, restore.
- Permissions: share with users/groups and calculate effective access.
- Sharing: public links, expiration, password, access requests.
- Search: permission-aware keyword and semantic search.
- AI: summarize, classify, tag, DLP result retrieval.
- Admin: users, policies, audit logs, storage health.

## Mandatory API Rules

- All endpoints require authentication except explicitly marked public link endpoints.
- All object endpoints accept UUIDs, not physical paths.
- Every object request must call the authorization layer.
- Responses never include storage node disk paths.
- Mutating APIs emit audit events.

