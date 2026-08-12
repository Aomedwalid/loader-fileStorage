# Common Commands

How to build, run, test, and deploy this project. All commands are run from the repository root.

## Build

```powershell
# Maven wrapper (Windows)
.\mvnw.cmd compile

# Or the plain Maven wrapper script (sh)
./mvnw compile

# Package (creates target/FileUploadTest-0.0.1-SNAPSHOT.jar)
.\mvnw.cmd package
```

Requires: JDK 17, Maven (bundled wrapper). Dependencies resolve from Maven Central.

## Run

```powershell
.\mvnw.cmd spring-boot:run
```

or run the packaged jar:

```powershell
java -jar target\FileUploadTest-0.0.1-SNAPSHOT.jar
```

**Runtime prerequisites** (per `application.yml`, which is a local, gitignored file):
- PostgreSQL on `localhost:5432`, database `filetest` (auto-creates schema via `ddl-auto: update`).
- Redis on `localhost:6379`.
- ClamAV installed and `clamscan.exe` present at `C:\Program Files\ClamAV\clamscan.exe` (required only for the `complete` stage).
- Writable upload directories configured at `app.upload.base-path` and `app.upload.final-path` (defaults `/var/myapp/uploads/temp` and `/var/myapp/uploads/fin` — adjust for local dev, e.g. `./uploads/temp`).
- If `app.encryption.enabled=true` (default), the AES key path `app.encryption.aes-key` must be creatable (auto-generated on first use).

## Test

```powershell
.\mvnw.cmd test
```

Currently only `FileUploadTestApplicationTests.contextLoads` exists (`@SpringBootTest`). Note: this test boots the Spring context, so it needs the runtime prerequisites (Postgres/Redis) available; it does not perform any assertions beyond context startup.

## Verify a change

1. `.\mvnw.cmd compile` — catches compile/type errors.
2. `.\mvnw.cmd test` — runs the suite.
3. For pipeline changes, exercise the API end-to-end:
   - `POST /api/uploads/start` with `{"fileName","totalChunks","contentType"}`
   - `POST /api/uploads/{uploadId}/chunk/{index}` with `multipart/form-data` field `file`
   - `GET /api/uploads/{uploadId}/status`
   - `POST /api/uploads/{uploadId}/complete`
   - `GET /api/files?page=0&size=20`
   - `GET /api/files/download/{fileName}`

## Lint / format

There is **no linter or formatter configured** (no Checkstyle, Spotless, or prettier plugin). Keep code consistent by matching the existing style. Consider adding one as a goal.

## Deploy

No Docker, CI/CD, or deployment scripts exist in the repository. Deployment today = `.\mvnw.cmd package` then run the jar. The git history shows a move toward production paths (`/var/myapp/...`) and removing the public `application.yml`, but infrastructure is not yet committed.

## Git

```powershell
git status           # uncommitted local changes (application.yml is local-only)
git log --oneline    # recent history
```
