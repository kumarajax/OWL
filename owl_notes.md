 
 

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
  WHERE username = 'testuser'
    AND role = 'USER';

  Set to 50 GB:

  UPDATE app.users
  SET quota_bytes = 53687091200
  WHERE username = 'testuser'
    AND role = 'USER';

  Verify:

  SELECT username, role, quota_bytes, used_bytes
  FROM app.users
  WHERE username = 'testuser';

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

  Login:

  username: testuser
  password: TestPassword123!



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

  Login using:

  username: testuser
  password: TestPassword123!

  Expected screen after login:

  Signed in as testuser@example.com
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




 TOKEN='eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJfNWt4U1JnN2xDRXMtTnRUZmg4T1hYWUpXc21aQ1RPMnRpN0pGVTE0NFlVIn0.eyJleHAiOjE3NzcyNzc3MzEsImlhdCI6MTc3NzI3NzQzMSwiYXV0aF90aW1lIjoxNzc3Mjc3NDMwLCJqdGkiOiI1YjA3ZTljZi01NTAwLTRkMDgtOTdiOS01NzdiZTA3OWU2MmEiLCJpc3MiOiJodHRwOi8vbG9jYWxob3N0OjgwODAvcmVhbG1zL293bGRyaXZlIiwic3ViIjoiYmE4ZTFkMTUtOGE3Zi00OWViLWI4MjEtY2VjOGFlZWU2YzM1IiwidHlwIjoiQmVhcmVyIiwiYXpwIjoib3dsLWRyaXZlLXdlYiIsInNpZCI6ImU2OTI4YTRlLTIzMzItNDBiMS05YTdjLTA1ZWQxNGFmMzAyZiIsImFjciI6IjEiLCJhbGxvd2VkLW9yaWdpbnMiOlsiaHR0cDovL2xvY2FsaG9zdDozMDAwIl0sInNjb3BlIjoib3BlbmlkIGVtYWlsIHByb2ZpbGUiLCJlbWFpbF92ZXJpZmllZCI6dHJ1ZSwibmFtZSI6IlRlc3QgVXNlciIsInByZWZlcnJlZF91c2VybmFtZSI6InRlc3R1c2VyIiwiZ2l2ZW5fbmFtZSI6IlRlc3QiLCJmYW1pbHlfbmFtZSI6IlVzZXIiLCJlbWFpbCI6InRlc3R1c2VyQGV4YW1wbGUuY29tIn0.QB7EcIcTFuPpG_QwJbqf1mRcdxV_ZFxIe0xs_TzpuaBm7743SCpmbOUnxy2n1PiHGGNrWv3EJ50LIjLfER40TSBwgCO7bMBSsOL74ix4QabJyLn2jQQFj6U1zSsrBZrSDW5s1Mg9F87i33GVamBgTuUe_qjqqwu4Y4ZyyCx_X5iefQpVVJIlHPJpkNWgaCHcVXpf4GqqPhmISny8Umho9hLlOo4_8HulLVPalGkwfBa6TzPVh9fjF-BiGs5CNpvTR5t57hqKbrMeKMg2zDLdF98mLWB7UjPaoyQPiWUYBj0exzl-J-gFCU8kTa3SuYZuR6qk3crmroC-UsRVYe3WuQ'
  curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/me