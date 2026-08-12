# Hard Constraints

Things an agent must never break, delete, or change casually. These are load-bearing for the running system.

## Security & secrets

- **Never commit secrets.** `src/main/resources/application.yml` is gitignored and contains local credentials (Postgres password, Redis config, AES key path). It is the "local secret" file. Do not commit it, do not add new secrets to tracked files, and do not log key material or passwords.
- **Never change the at-rest encryption format without a migration plan.** Files already stored as `.enc` use the layout: `[16-byte IV][AES/CBC/PKCS5Padding ciphertext]`, key base64 at `app.encryption.aes-key`. `encryptFile` (write side) and `FileServiceImpl.getFileData` (read side) must stay symmetric. Changing cipher, key handling, or IV layout breaks every stored file — unless an explicit migration is agreed.
- **Never weaken the file-safety ordering.** Completion order is: merge → Tika MIME allow-list check → rename to real extension → ClamAV scan → encrypt → persist metadata. Do not skip or reorder the scan/validation before storage.
- **The API currently has no authentication.** Do not add a homegrown auth scheme; if auth is needed, follow the `springboot-security` skill and standard Spring Security.

## Data / pipeline invariants

- **Upload ID format**: `upload_` + 12-char NanoId (uppercase letters + digits) generated in `UploadSessionMapper`. File paths on disk contain the uploadId; changing the format invalidates existing paths and DB rows.
- **Final file naming**: `{uploadId}_{fileName}{extension}` (`refactorFileExtension`). Encrypted files append `.enc`. Download relies on `FileEntity.filePath` and `fileName` being consistent.
- **Redis progress contract**: hash `progress : <uploadId>` with exactly the fields `totalChunks`, `receivedChunks`, `createdAt`, plus the set `Chunk : <uploadId>`. These are read by status, completion, and the sweeper. Prefixes come from config; keys expire in 2h — that TTL + the 110-minute sweeper cutoff is how orphan detection works. Change any of these consistently or not at all.
- **`FileEntity.fileName` is unique** and enforced at DB level; `UploadSession.fileName` is also unique. The dedupe check in `createUploadSession` depends on this.
- **Session status lifecycle**: `IN_PROGRESS → COMPLETED | CANCELED`. (Today nothing sets `COMPLETED` — that's a known bug, fixing it is fine, but don't casually remove status transitions used by the sweeper: `CANCELED` rows are bulk-deleted.)

## Package / naming sensitivities

- **`exception_handling.costumeErrors`** — the `costumeErrors` spelling is a typo for "customErrors". Renaming it changes every import and JSON-facing behavior is unaffected, but it is a large mechanical refactor. Do it deliberately with a plan, not as a drive-by.
- **`rest.test`** — the `test.java` controller (maps `GET /test`) is a dev leftover. Removing it is fine; just confirm nothing references it.
- The `FileServiceImpl` → `uploadCompletionServiceImpl` direct dependency (for `getOrCreateSecretKey`) is intentional-ish coupling today. Don't remove the method without checking both call sites.

## Behavioral invariants

- Chunk upload is effectively once-only per index (Redis set guards against replay). Keep that guarantee.
- `getUploadSessionStatus` is `@Cacheable` and returns the session status; a stale status is a bug, not a feature — fixes should evict/invalidate, not silently accept regression.
- All non-streaming endpoints must keep returning the `MainResponse` envelope through `MainResponseMapper`.

## Repository hygiene

- `.gitignore` ignores `target/`, `uploads/`, `.idea/`, `*.yml` and the wrapper jar. Keep `uploads/` (a local data dir with real store content) out of git.
- Do not commit the `uploads/` directory contents or the local `application.yml`.