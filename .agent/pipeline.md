# File-Processing Pipeline

The complete lifecycle of a file through the service. Implementations: `UploadServiceImpl`, `uploadCompletionServiceImpl`, `FileServiceImpl`.

## Stage 0 — Create upload session

`POST /api/uploads/start` → `FileUpload.startUploadSession` → `UploadServiceImpl.createUploadSession`

1. **Uniqueness check**: `uploadSessionRepo.existsUploadSessionByFileName(fileName)` — if the file name already exists, throws `AlreadyExistedFileName("code u1")` → 400.
2. **Persist session**: maps `UploadSessionRequest` → `UploadSession` with a generated `uploadId` (`upload_` + 12-char NanoId from `UploadSessionMapper`), status `IN_PROGRESS`. Saved via JPA (`ddl-auto: update`).
3. **Initialize Redis tracking** (`initializeRedisTracking`): deletes any prior keys, then writes a hash `progress : <uploadId>` `{totalChunks, receivedChunks:0, createdAt}` and an empty set `Chunk : <uploadId>`. Both keys get a **2-hour TTL**.
4. Returns `UploadSessionResponse {uploadId, totalChunks, status}` wrapped in `MainResponse` (201).

Keys on disk / Redis: DB row + `progress : {id}` + `Chunk : {id}`.

## Stage 1 — Upload chunks

`POST /api/uploads/{uploadId}/chunk/{index}` (multipart form field named `file`) → `UploadServiceImpl.uploadChunk`

Validations (in order):
1. `getCurrentSession(uploadId)` — session must exist, else `InvalidUploadSession("code : 1")`.
2. `sessionStatusVerify` — `CANCELED`/`COMPLETED` sessions reject further chunks (`InactivatedUploadSession`).
3. `validateChunkIndex` — index must be in `[0, totalChunks)`; chunk must not be empty; index must not already be present in the Redis set `Chunk : {id}`.

Then:
4. `createUploadSessionDirectory` — creates `{base-path}/{uploadId}/` if missing (`DirectoryException` on failure).
5. `uploadChunkToDirectory` — copies the multipart chunk to `{base-path}/{uploadId}/{index}` (overwrite).
6. `saveProgressInRedis` — adds `index` to the Redis set, increments `receivedChunks`, refreshes both TTLs to 2h.
7. Returns `ChunkResponse {index, fileName}` (200).

## Stage 2 — Poll status (optional, resumable)

`GET /api/uploads/{uploadId}/status` → `UploadServiceImpl.getUploadSessionStatus`

- Reads Redis hash `progress : {id}` (totalChunks, receivedChunks, createdAt) and set `Chunk : {id}`.
- Returns `UploadStatusResponse {fileName, status, createdAt, totalChunks, receivedChunks, chunkIndices}`.
- **`@Cacheable("uploadStatus", key = "#uploadId")`** — result cached in Redis cache store for up to 1h. **Known issue**: this can serve stale progress after a new chunk upload; the cache is not evicted on chunk write (see troubleshooting).

## Stage 3 — Complete / finalize

`POST /api/uploads/{uploadId}/complete` → `uploadCompletionServiceImpl.uploadCompleted` (declares `throws IOException`)

1. **Preflight** (`uploadCompletionCheck`): `receivedChunks == totalChunks` from Redis hash, else `IncompletedUploadSession("code 6")`; temp dir must exist, else `InactivatedUploadSession`.
2. **Dir check** (`pathsValidityCheck`): ensure `{final-path}` exists (created if needed).
3. **Merge** (`mergingChunks`): sequentially `FileChannel.transferTo` each chunk (sorted numerically by chunk filename) into `{final-path}/{uploadId}_{fileName}`. Failure → `AssemblingException` (500).
4. **Cleanup** (`deleteTempFiles`): deletes the whole temp dir `{base-path}/{uploadId}` (`DirectoryException` on failure).
5. **Size**: `Files.size(...)` → `fileSize` (String).
6. **MIME validation** (`MimeValidation`): Apache Tika detects the real type; if not in `FileTypeConfig.ALLOWED_MIME_TYPES`, the file is deleted and `IllegalFileException` thrown (400).
7. **Extension fix** (`refactorFileExtension`): maps MIME → extension (`extensionFromMime`) and renames to `{uploadId}_{fileName}{ext}`. Unknown MIME falls back to `.bin` only if Tika returned an allowed type with no mapping — note `.bin` is NOT in the allow-list, so this is effectively unreachable for non-listed types.
8. **Antivirus scan** (`scanWithClamAv`): shells out to `C:\Program Files\ClamAV\clamscan.exe {file}`. Exit 0 = clean, 1 = infected (file deleted + `IllegalFileException`), other = `DirectoryException` ("ClamAV scan error").
9. **Encryption** (`encryptFile`, only if `app.encryption.enabled=true`):
   - Reads/generates the AES key from `app.encryption.aes-key` (base64; auto-generated on first run).
   - Generates a random 16-byte IV, writes it as the **first 16 bytes** of output, then streams AES/CBC/PKCS5Padding ciphertext to `{file}.enc`; deletes the plaintext file.
10. **Metadata** (`FileEntityMapper.createFile`): saves `FileEntity {fileName, uploadSession=uploadId, filePath=encrypted path, fileType=extension, fileSize}`.
11. Returns `UploadCompletedResponse {fileName, fileSize, contentType, status}` (200).

**Important bug**: the `UploadSession` is **never** marked `COMPLETED` (status remains `IN_PROGRESS`), and the Redis progress keys are not cleared on completion. See troubleshooting.

## Stage 4 — List metadata

`GET /api/files?page=0&size=20` → `UsersFiles.getAllFilesMetadata` → `FileServiceImpl.getAllFiles`

- `FileRepo.findAllMetadata` (JPQL projection) paged via `PageRequest.of(page, size)`.
- `@Cacheable("files", key = "#page + '-' + #size")` — cached 1h; **known issue**: the cache is never evicted when new files are uploaded, so listings can be stale (see troubleshooting).
- Returns `PageResponse {content, currentPage, totalPages, totalItems, pageSize}`.

## Stage 5 — Download

`GET /api/files/download/{fileName}` → `UsersFiles.downloadFile` → `FileServiceImpl.downloadFile`

1. `fileRepo.findByFileName(fileName)` else `IllegalFileException` (400).
2. `getFileData(Path)` builds a `StreamingResponseBody`:
   - Encryption disabled → stream raw bytes.
   - Encryption enabled → read 16-byte IV prefix, then decrypt AES/CBC/PKCS5Padding streaming, write plaintext to the response.
3. Sets `Content-Disposition: attachment; filename="{fileName}{ext}"` (appends extension if missing) and `Content-Type` from stored MIME type (octet-stream fallback).

## Stage 6 — Sweeper (background)

`SchedularConfig` (`@Scheduled(fixedRate = 4h)`) → `UploadServiceImpl.cleanOrphanedSessions` (`@Transactional`)

1. Find `UploadSession`s with `createdAt < now - 110 minutes`.
2. `fetchRedisKeys` — SCAN Redis for `progress : *` keys (count 1000).
3. Sessions whose Redis key is **absent** are considered orphaned/abandoned (Redis 2h TTL expired but DB row + temp dir remain).
4. Bulk-mark them `CANCELED`, then `deleteByStatus(CANCELED)` and delete their temp dirs (`FileUtils.deleteDirectory`).

## Redis key/namespace summary

| Key | Type | TTL | Written by |
|---|---|---|---|
| `progress : {uploadId}` | hash (`totalChunks`, `receivedChunks`, `createdAt`) | 2h | session create / chunk upload |
| `Chunk : {uploadId}` | set of chunk indices | 2h | session create / chunk upload |
| Spring cache `uploadStatus::<id>` | cache entry | 1h | status read |
| Spring cache `files::<page-size>` | cache entry | 1h | listing read |

Prefixes are configurable: `app.redis.progress-key-prefix`, `app.redis.chunk-key-prefix`.

## Storage layout

- Temp chunks: `{app.upload.base-path}/{uploadId}/{0..n-1}`
- Final files: `{app.upload.final-path}/{uploadId}_{fileName}{ext}` (optionally `.enc`)
- AES key: `{app.encryption.aes-key}` (base64, auto-created)
