 
 

 Typical order:

  ./scripts/dev-up.sh
  ./scripts/start-app.sh

  Shutdown order:

  ./scripts/stop-app.sh
  ./scripts/dev-down.sh

  ----------------------
  You can update quota directly in PostgreSQL.

  Open psql:

  docker exec -it owl_drive-postgres-1 psql -U owldrive -d owldrive

  Check users:

  SELECT id, username, role, quota_bytes, used_bytes
  FROM app.users
  ORDER BY created_at;

  Set a non-admin user quota to 10 GB:

  UPDATE app.users
  SET quota_bytes = 10737418240
  WHERE username = '<local-username>'
    AND role = 'USER';

  Set to 50 GB:

  UPDATE app.users
  SET quota_bytes = 53687091200
  WHERE username = '<local-username>'
    AND role = 'USER';

  Verify:

  SELECT username, role, quota_bytes, used_bytes
  FROM app.users
  WHERE username = '<local-username>';

  Notes:

  - Quota is in bytes.
  - 2 GB = 2147483648
  - 10 GB = 10737418240
  - 50 GB = 53687091200
  - Admin users use quota_bytes = NULL.
  - Current app stores quota but does not enforce it during upload yet.

 
› Improve documentation in @filename
 


  --------------------

  cd /Users/ajay/Desktop/OWL_DRIVE

  ./scripts/dev-up.sh

  cd /Users/ajay/Desktop/OWL_DRIVE/backend

  mvn spring-boot:run

  Keep that terminal open.

  Open a new terminal, then run:

  cd /Users/ajay/Desktop/OWL_DRIVE/frontend

  cp .env.example .env.local

  npm install

  npm run dev

  Keep that terminal open.

  Then open:

  http://localhost:3000

  Create a user from the OWL Drive login page, or create one in the
  Keycloak admin console, then log in with that local test account.



--------------------Automated steps ------

 Run these steps exactly.

  1. Start Infrastructure

  cd /Users/ajay/Desktop/OWL_DRIVE
  ./scripts/dev-up.sh

  Wait 20-40 seconds for Keycloak.

  Verify:

  docker compose ps

  You should see:

  owl_drive-postgres-1   Up
  owl_drive-keycloak-1   Up

  Verify Keycloak realm:

  curl http://localhost:8080/realms/owldrive/.well-known/openid-configuration

  You should see JSON containing:

  "issuer":"http://localhost:8080/realms/owldrive"

  2. Start Backend + Frontend

  cd /Users/ajay/Desktop/OWL_DRIVE
  ./scripts/start-app.sh

  This starts:

  Backend:  http://localhost:8081
  Frontend: http://localhost:3000

  Logs:

  /Users/ajay/Desktop/OWL_DRIVE/logs/backend.log
  /Users/ajay/Desktop/OWL_DRIVE/logs/frontend.log

  3. Verify Backend

  Wait 10-20 seconds, then run:

  curl http://localhost:8081/health

  Expected:

  {"status":"UP"}

  4. Open Frontend

  Open this in browser:

  http://localhost:3000

  Click:

  Login with Keycloak

  Create a user from the OWL Drive login page, or create one in the
  Keycloak admin console, then log in with that local test account.

  Expected screen after login:

  Signed in as <local-email>
  My Drive
  Root folder ID: <uuid>

  5. Stop Application Later

  Stop backend + frontend:

  cd /Users/ajay/Desktop/OWL_DRIVE
  ./scripts/stop-app.sh

  Stop infrastructure:

  cd /Users/ajay/Desktop/OWL_DRIVE
  ./scripts/dev-down.sh

  Normal Startup Shortcut

  After Docker is running, your usual startup is just:

  cd /Users/ajay/Desktop/OWL_DRIVE
  ./scripts/dev-up.sh
  ./scripts/start-app.sh

  Then open:

  http://localhost:3000


---------------------


To restore postgress:
---------------------
 Postgres Backup
 ---------------
 ---------------

  Run from the repo root:

  cd /Users/ajay/DATA/CODE/OWL

  If the stack is not already up, start only Postgres first:

  docker compose up -d postgres

  Create a backup directory and dump the database in custom format:

  mkdir -p backups
  BACKUP_DIR="$PWD/backups/backup-$(date +%Y%m%d-%H%M%S)"

  docker compose exec postgres pg_dump \
    -U owldrive \
    -d owldrive \
    -Fc \
    -f /tmp/owldrive.dump

  docker cp "$(docker compose ps -q postgres):/tmp/owldrive.dump" "$BACKUP_DIR/owldrive.dump"

  Verify the dump exists:

  ls -lh "$BACKUP_DIR/owldrive.dump"

  This backup contains both:

  - OWL app data in app schema
  - Keycloak data in keycloak schema




  Postgres Restore
  -----------------
  -----------------

  If you want a clean restore, stop the stack first:

  cd /Users/ajay/DATA/CODE/OWL
  docker compose down --remove-orphans

  Start Postgres:

  docker compose up -d postgres

  Set the backup path:

  BACKUP_DIR="/Users/ajay/DATA/CODE/OWL/backups/backup-20260513-220523"

  Copy the dump into the Postgres container:

  docker cp "$BACKUP_DIR/owldrive.dump" "$(docker compose ps -q postgres):/tmp/owldrive.dump"

  Restore it:

  docker compose exec postgres pg_restore \
    -U owldrive \
    -d owldrive \
    --clean \
    --if-exists \
    /tmp/owldrive.dump

  Then start the rest of the stack:

  docker compose up -d --build

  Quick checks

  docker compose ps
  docker compose exec postgres psql -U owldrive -d owldrive -c '\dt app.*'
  docker compose exec postgres psql -U owldrive -d owldrive -c '\dt keycloak.*'



  MinIO Backup
  --------------
  -------------

  Since MinIO is mounted to the host in your current setup, the simplest backup is just the host directory copy.

  Run from the repo root:

  cd /Users/ajay/DATA/CODE/OWL

  Stop the stack first so MinIO is quiet:

  docker compose down --remove-orphans

  Create a backup folder and archive the MinIO data directory:

  mkdir -p backups
  BACKUP_DIR="$PWD/backups/backup-$(date +%Y%m%d-%H%M%S)"

  tar -czf "$BACKUP_DIR/minio.tgz" -C /Volumes/PEN/OWL_DRIVE minio

  Verify it:

  ls -lh "$BACKUP_DIR/minio.tgz"

  This contains the MinIO bucket/object data stored under:

  /Volumes/PEN/OWL_DRIVE/minio

  MinIO Restore

  Stop the stack if it is running:

  cd /Users/ajay/DATA/CODE/OWL
  docker compose down --remove-orphans

  Clear the current MinIO data directory:

  rm -rf /Volumes/PEN/OWL_DRIVE/minio
  mkdir -p /Volumes/PEN/OWL_DRIVE/minio

  Restore from the tarball:

  BACKUP_DIR="/Users/ajay/DATA/CODE/OWL/backups/backup-20260513-220523"

  tar -xzf "$BACKUP_DIR/minio.tgz" -C /Volumes/PEN/OWL_DRIVE

  Start the stack again:

  docker compose up -d --build --remove-orphans

  Quick check:

  docker compose ps
  find /Volumes/PEN/OWL_DRIVE/minio -maxdepth 5 -type f | head



==================================================================================================================================================================

==================================================================================================================================================================

On UBUNTU
----------

• Use this sequence on Ubuntu.

  1. Update the repo to the latest remote commit:

  cd /path/to/OWL
  git fetch origin --prune
  git switch main
  git pull --ff-only origin main
  git switch dev
  git pull --ff-only origin dev
  git switch main

  2. Confirm the backup script is present:

  ls -l scripts/backup-restore.sh

  3. Create your local compose config from the committed templates if you do not already have them:

  cp -n .env.example .env
  cp -n docker-compose.override.example.yml docker-compose.override.yml

  4. Set your local storage root in .env.

  For example:

  DATA_STORAGE_ROOT=/mnt/owl_drive

  5. Create the host directories the stack will bind to:

  mkdir -p /mnt/owl_drive/postgres
  mkdir -p /mnt/owl_drive/minio
  mkdir -p backups

  6. Start the stack:

  docker compose up -d --build --remove-orphans

  7. Verify it is up:

  docker compose ps
  curl -I http://localhost:3000
  curl -I http://localhost:8081/health
  curl -I http://localhost:8080/realms/owldrive
  curl -I http://localhost:9001

  8. Take a backup when you need one:

  ./scripts/backup-restore.sh backup all

  That writes a timestamped folder under:

  backups/backup-YYYYMMDD-HHMMSS

  9. Restore from a backup:

  docker compose down --remove-orphans
  ./scripts/backup-restore.sh restore all backups/backup-YYYYMMDD-HHMMSS
  docker compose up -d --build --remove-orphans

  10. If you only want one part:

  - Postgres only:

    ./scripts/backup-restore.sh backup postgres
    ./scripts/backup-restore.sh restore postgres backups/backup-YYYYMMDD-HHMMSS
  - MinIO only:

    ./scripts/backup-restore.sh backup minio
    ./scripts/backup-restore.sh restore minio backups/backup-YYYYMMDD-HHMMSS

  If you want, I can also give you the same sequence as a copy-paste Ubuntu runbook with your actual mount path filled in.