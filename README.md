# OWL Drive - Phase 1.1 + Phase 3

This phase implements only:

- Keycloak OIDC login.
- Spring Boot JWT resource server.
- Current user and `My Drive` root provisioning.
- User role and storage quota model.
- Authenticated folder CRUD inside `My Drive`.
- MinIO-backed file upload, download, listing, and soft delete.

Not included:

- Search or OpenSearch.
- AI or Qdrant.
- Sharing links.
- Advanced permissions.
- Version history.
- Preview generation.
- Chunking, replication, or encryption.
- Quota enforcement during upload.

## Services

- Keycloak: `http://localhost:8080`
- Backend API: `http://localhost:8081`
- Frontend: `http://localhost:3000`
- PostgreSQL: `localhost:5432`

The Keycloak realm import seeds local-only test users. Both use a temporary
password and should be changed immediately after first login.

```text
username: testuser
password: Changeme
email: testuser@example.com
role: user
quota: 2 GB
```

```text
username: adminuser
password: Changeme
email: adminuser@example.com
role: admin
quota: unlimited
```

## Start Everything

From a fresh clone:

```bash
git clone https://github.com/kumarajax/OWL.git
cd OWL
docker compose up --build
```

Open:

```text
http://localhost:3000
```

On the login screen, use `Create account` to register with an email and password. The email becomes the username.

For local external-drive storage, create local config files from the committed examples before starting:

```bash
cp .env.example .env
./scripts/sync-storage-config.sh
set -a
. ./.env
set +a
mkdir -p "$DATA_STORAGE_ROOT/minio" "$DATA_STORAGE_ROOT/minio-2" "$DATA_STORAGE_ROOT/postgres" "$DATA_STORAGE_ROOT/postgres-shard-2"
docker compose up --build
```

Edit `DATA_STORAGE_ROOT`, `DATA_STORAGE_ROOT_2`, and any later `DATA_STORAGE_ROOT_N` entries in `.env` for your machine. Run `./scripts/sync-storage-config.sh` after changing them so `docker-compose.yml` and the override files stay aligned. Docker Compose reads `.env` and `docker-compose.override.yml` automatically; the generated local files are ignored by git.

Stop everything:

```bash
cd OWL
docker compose down --remove-orphans
```

## Storage

Regular users receive `quota_bytes = 2147483648` and `used_bytes = 0`.

Admins receive `quota_bytes = NULL`, which means unlimited storage.

The quota model is exposed by:

```text
GET /api/me/storage
```

Quota is not enforced during upload yet.

## Token Expiration

Keycloak access tokens currently expire after 300 seconds, or 5 minutes.

For a fresh realm import, this is configured in:

```text
infra/keycloak/realm-export.json
```

```json
"accessTokenLifespan": 300
```

For an already running Keycloak realm, update it in the Keycloak Admin UI:

```text
Realm settings -> Tokens -> Access Token Lifespan
```

When an access token expires, the frontend clears the local session and returns to the login screen.

## Object Storage

OWL Drive stores file bytes in MinIO pools and keeps file metadata in Postgres shards.

New user accounts are assigned to the least-loaded Postgres shard. New file uploads try MinIO pools in order and fall through to the next pool if a pool rejects the write. Existing users and files stay on the shard or pool where they were created.

By default, Compose stores MinIO and Postgres data in Docker named volumes:

```text
minio-data
minio-2-data
postgres-data
postgres-shard-2-data
```

For a Compose-based external-drive setup, copy the root examples:

```bash
cp .env.example .env
cp docker-compose.override.example.yml docker-compose.override.yml
```

Then set one local value:

```env
DATA_STORAGE_ROOT=/Volumes/PEN/OWL_DRIVE
```

The override derives durable paths from it:

```text
${DATA_STORAGE_ROOT}/minio
${DATA_STORAGE_ROOT}/minio-2
${DATA_STORAGE_ROOT}/postgres
${DATA_STORAGE_ROOT}/postgres-shard-2
```

The upload limits can be overridden with:

```bash
APP_STORAGE_MAX_FILE_SIZE=1GB
APP_STORAGE_MAX_REQUEST_SIZE=1GB
APP_STORAGE_MAX_UPLOAD_BYTES=1073741824
```

Object keys use generated IDs only:

```text
{userId}/{fileId}/original
```

User-provided filenames are stored only as metadata and download display names.

Backups and restores:

```bash
./scripts/backup-restore.sh backup all
./scripts/backup-restore.sh restore all backups/backup-YYYYMMDD-HHMMSS
```

## User Capacity

OWL Drive defaults to 1000 active users. Change the limit with:

```yaml
app.users.max-users: 1000
```

or the environment variable:

```bash
APP_USERS_MAX_USERS=1000
```

When active users reach this limit, the frontend disables account creation and the backend rejects new OWL Drive provisioning.

## Browser Test

1. Start Docker services.
2. Start backend.
3. Start frontend.
4. Open `http://localhost:3000`.
5. Create an account or login with an account created in Keycloak.
6. Confirm the sidebar shows `USER` and `0 B of 2.0 GB used`.
7. Open `My Drive`.
8. Create a folder.
9. Open that folder.
10. Click `Upload File` and upload a small text file.
11. Confirm the file appears in the list with name, size, type, and modified date.
12. Click the file download button.
13. Confirm downloaded file content matches the original.
14. Delete the file.
15. Refresh the browser and confirm the deleted file is hidden.
16. Log out and login as `adminuser`.
17. Confirm the sidebar shows `ADMIN` and `Unlimited storage`.

## Clone Notes

After cloning, these should all come up cleanly:

- frontend on `http://localhost:3000`
- backend on `http://localhost:8081`
- Keycloak on `http://localhost:8080`
- MinIO console on `http://localhost:9001`
- PostgreSQL on `localhost:5432`

If you open the app as `http://127.0.0.1:3000`, the current local config also allows that host.

On Windows, run the commands from Git Bash or WSL, and use Docker Desktop so `docker compose` is available.

## Why The Fresh Clone Failed

Earlier local setup issues included:

- `infra/keycloak/realm-export.json` previously seeded reusable test passwords. The seeded users now use temporary `Changeme` credentials for local setup.
- Backend CORS and Keycloak redirect settings only allowed `http://localhost:3000`, not `http://127.0.0.1:3000`.
- The Next dev server needed an explicit local allowed-origin entry when accessed through `127.0.0.1`.

Those issues are fixed in the current local tree.

## API Test

Get a token:

```bash
OWL_USERNAME='testuser'
OWL_PASSWORD='Changeme'
TOKEN=$(curl -s -X POST http://localhost:8080/realms/owldrive/protocol/openid-connect/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=password' \
  -d 'client_id=owl-drive-web' \
  -d "username=$OWL_USERNAME" \
  -d "password=$OWL_PASSWORD" \
  | node -pe "JSON.parse(require('fs').readFileSync(0, 'utf8')).access_token")
```

Verify regular user storage:

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/me/storage
```

Expected:

```json
{
  "role": "USER",
  "quotaBytes": 2147483648,
  "usedBytes": 0,
  "isUnlimited": false
}
```

Verify admin storage:

```bash
OWL_ADMIN_USERNAME='adminuser'
OWL_ADMIN_PASSWORD='Changeme'
ADMIN_TOKEN=$(curl -s -X POST http://localhost:8080/realms/owldrive/protocol/openid-connect/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=password' \
  -d 'client_id=owl-drive-web' \
  -d "username=$OWL_ADMIN_USERNAME" \
  -d "password=$OWL_ADMIN_PASSWORD" \
  | node -pe "JSON.parse(require('fs').readFileSync(0, 'utf8')).access_token")

curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8081/api/me/storage
```

Expected:

```json
{
  "role": "ADMIN",
  "quotaBytes": null,
  "usedBytes": 0,
  "isUnlimited": true
}
```

Create a folder and upload a file:

```bash
ROOT_ID=$(curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/drive/root \
  | node -pe "JSON.parse(require('fs').readFileSync(0, 'utf8')).id")

FOLDER_ID=$(curl -s -X POST http://localhost:8081/api/folders \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"Phase 3\",\"parentId\":\"$ROOT_ID\"}" \
  | node -pe "JSON.parse(require('fs').readFileSync(0, 'utf8')).id")

printf 'hello owl drive\n' > /tmp/owl-upload.txt

FILE_ID=$(curl -s -X POST http://localhost:8081/api/files/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "parentFolderId=$FOLDER_ID" \
  -F "file=@/tmp/owl-upload.txt;type=text/plain" \
  | node -pe "JSON.parse(require('fs').readFileSync(0, 'utf8')).id")
```

List, download, compare, and delete:

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/folders/$FOLDER_ID/children

curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8081/api/files/$FILE_ID/download \
  -o /tmp/owl-download.txt

diff /tmp/owl-upload.txt /tmp/owl-download.txt

curl -i -X DELETE http://localhost:8081/api/files/$FILE_ID \
  -H "Authorization: Bearer $TOKEN"

curl -i -H "Authorization: Bearer $TOKEN" \
  http://localhost:8081/api/files/$FILE_ID/download
```

Expected results:

- Upload returns file metadata with `checksumSha256`.
- Folder children includes both `itemType: "folder"` and `itemType: "file"` records.
- `diff` has no output.
- File delete returns `204`.
- Downloading a deleted file returns `404`.

Invalid cases:

```bash
# Missing file should fail with 404.
curl -i -H "Authorization: Bearer $TOKEN" \
  http://localhost:8081/api/files/00000000-0000-0000-0000-000000000000/download

# Path traversal filename should not affect storage path.
printf 'safe bytes\n' > /tmp/path-traversal.txt
curl -s -X POST http://localhost:8081/api/files/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "parentFolderId=$FOLDER_ID" \
  -F "file=@/tmp/path-traversal.txt;filename=../../evil.txt;type=text/plain"
```

## Database Verification

```bash
docker exec -it owl_drive-postgres-1 psql -U owldrive -d owldrive
```

Then:

```sql
SELECT id, name, owner_id, parent_id, deleted_at FROM app.folders ORDER BY created_at;
SELECT id, owner_id, parent_folder_id, original_name, storage_key, content_type, size_bytes, checksum_sha256, deleted_at FROM app.files ORDER BY created_at;
SELECT id, username, role, quota_bytes, used_bytes FROM app.users ORDER BY created_at;
```

Deleted files remain in `app.files` with `deleted_at` populated and do not appear in `/api/folders/{folderId}/children`.

## Phase 1.1 Files

- Migration: `backend/src/main/resources/db/migration/V4__phase1_1_user_roles_and_quota.sql`
- User provisioning and role mapping: `backend/src/main/java/com/owldrive/api/ProvisioningService.java`
- Storage API: `backend/src/main/java/com/owldrive/api/MeController.java`, `backend/src/main/java/com/owldrive/api/UserStorageRecord.java`
- User model: `backend/src/main/java/com/owldrive/api/UserRecord.java`
- Keycloak realm roles, client config, and local seed users: `infra/keycloak/realm-export.json`
- Frontend storage display: `frontend/app/page.tsx`

## Phase 3 Files

- Migration: `backend/src/main/resources/db/migration/V3__phase3_files.sql`
- File API: `backend/src/main/java/com/owldrive/api/FileController.java`
- File logic: `backend/src/main/java/com/owldrive/api/FileService.java`
- Object storage: `backend/src/main/java/com/owldrive/api/MultiPoolMinioObjectStorageService.java`
- File records: `backend/src/main/java/com/owldrive/api/FileRecord.java`, `backend/src/main/java/com/owldrive/api/DriveItemRecord.java`, `backend/src/main/java/com/owldrive/api/DownloadableFile.java`, `backend/src/main/java/com/owldrive/api/StoredFile.java`
- Folder listing update: `backend/src/main/java/com/owldrive/api/FolderService.java`, `backend/src/main/java/com/owldrive/api/FolderController.java`
- Storage config: `backend/src/main/resources/application.yml`
- Frontend file UI: `frontend/app/page.tsx`
