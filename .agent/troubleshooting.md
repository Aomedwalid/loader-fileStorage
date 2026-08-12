# Troubleshooting

Known problems, gotchas, and how to investigate issues. Start with how to confirm the app is healthy, then the known bugs and an investigation playbook.

## Confirming the app actually starts

- `contextLoads` test boots the full context — failure usually means Postgres or Redis isn't reachable. Check `spring.datasource.*` and `spring.data.redis.*` in the local (gitignored) `application.yml`.
- `ddl-auto: update` will create tables but **never alter/rename columns** cleanly. If you change an entity field, Hibernate may fail on the existing schema — recreate the DB or write a migration.

## Known bugs / gaps (derived from code)

### 1. Upload session is never marked `COMPLETED`
`uploadCompleted` in `uploadCompletionServiceImpl` saves `FileEntity` and returns the current status unchanged — `currentSession.getStatus()` is still `IN_PROGRESS`. A repeating `complete` call would re-run merge/scan/encrypt on top of stale state. Fix: set status to `COMPLETED` (and consider rejecting a second completion) — but first read `constraints.md` on the sweeper's use of statuses.

### 2. Redis progress keys are not cleaned up after completion
`uploadCompleted` never deletes `progress : {id}` / `Chunk : {id}`; they die only by 2h TTL. Effect is minor (new sessions reuse the id-less keys because `initializeRedisTracking` deletes first), but keys linger up to 2h and the sweeper treats absent keys as orphaned.

### 3. Stale cache for status and listing
- `getUploadSessionStatus` is `@Cacheable("uploadStatus", key="#uploadId")` — after a new chunk is uploaded, `status` may return the pre-cache snapshot for up to 1h. There is no cache eviction on chunk write.
- `getAllFiles` is `@Cacheable("files", key="#page + '-' + #size")` — new completed files are not visible until the cache TTL expires (1h) or Redis cache is flushed.
Fix pattern: evict/`CacheEvict` the affected keys on write, or timestamp-aware keys. `RedisConfig` sets default TTL 1h.

### 4. `uploadCompletionCheck` NPE risk on a fresh/missing session
It reads `progress.entrySet()` from Redis into a map and then indexes `keys.get("totalChunks")` / `keys.get("receivedChunks")`. If the session exists in DB but the Redis hash is missing/empty (e.g. expired), this throws an NPE (500, caught by the catch-all handler) instead of a clean domain error. `getUploadSessionStatus` has the same risk of `Instant.parse(...)` failing on a missing `createdAt`.

### 5. `complete` while a session is already beyond chunks
Nothing checks session status lifecycle on `complete` beyond the Redis counters; a `CANCELED` or orphaned session can still attempt finalization if its Redis keys remain.

### 6. ClamAV path is hardcoded (Windows-only)
`scanWithClamAv` builds `C:\\Program Files\\ClamAV\\clamscan.exe`. On non-Windows deployments this throws `DirectoryException` ("something went wrong with antivirus") on every completion. Abstract the executable path into config.

### 7. `.bin` extension unreachable / allow-list mismatch
`extensionFromMime` has a default `.bin`, but `.bin` is not in `FileTypeConfig.ALLOWED_MIME_TYPES`, so `MimeValidation` rejects any type that would map to `.bin` before the mapping is used. The default branch is dead code unless the allow-list gains a `.bin` MIME. Keep `extensionFromMime` and the allow-list in sync if you extend types.

### 8. `FileEntity.createdAt` is a `String`
Mapped with `@CreationTimestamp` into a `String` column and serialized with `@JsonFormat` on `FileResponse`. Not ISO-instant typed like `UploadSession.createdAt`. Beware when comparing/sorting dates.

### 9. No authentication
Every endpoint is public. High risk if this ever exposes the download endpoint. See goals/security.

## Investigating a reported failure

1. **API returns an envelope error** — read `message`/`errors` in the `MainResponse`; the `GlobalHandler` maps it. Internal exception messages are currently echoed to clients in the catch-all handler (leakage of internals — see improvement in goals).
2. **Chunk upload rejected** — check order: session exists (`code : 1`), status active (`code : 2`), index range, empty chunk, duplicate index in `Chunk : {id}` set. Check the Redis set with `SMEMBERS "Chunk : <uploadId>"`.
3. **Complete fails with "incompleted chunks"** — compare `totalChunks` vs `receivedChunks` in `HGETALL progress : <uploadId>`; a chunk may have hit the duplicate-index rejection.
4. **Complete fails at scan** — ClamAV missing or path mismatch (see bug #6). Confirm exit codes: 0 clean, 1 infected, 2+ error.
5. **Encryption failures** — codes `111/112/113` map through `AesEncryptionException`. Check key file exists/valid base64 and directory is writable.
6. **Download 500 / empty file** — confirm `FileEntity.filePath` exists and `encryption` flag in config matches how the file was stored (encrypting with flag off, then reading with flag on, or vice versa, will fail).
7. **Sweeper deletes things unexpectedly** — the sweeper cancels+deletes sessions older than 110 min whose Redis key is gone. If Redis was flushed, or the app was down >2h, legitimate in-progress sessions can be swept. This is by design today, but tends to surprise in dev.

## Redis inspection cheat sheet

```bash
redis-cli KEYS "progress :*"
redis-cli HGETALL "progress : <uploadId>"
redis-cli SMEMBERS "Chunk : <uploadId>"
redis-cli KEYS "files::*"          # Spring cache namespace
redis-cli KEYS "uploadStatus::*"
```

## Files most likely involved, by symptom

| Symptom | Files |
|---|---|
| Session/chunk/status API | `UploadServiceImpl`, `UploadSessionMapper`, `UploadSessionRepo`, `GlobalHandler` |
| Completion/merge/scan/encrypt | `uploadCompletionServiceImpl`, `FileTypeConfig`, `FileEntityMapper`, `FileRepo` |
| Listing/download | `FileServiceImpl`, `FileRepo`, `PageMapper` |
| Sweeper | `SchedularConfig`, `UploadServiceImpl.cleanOrphanedSessions` |
| Cache behavior | `RedisConfig`, `@Cacheable` sites in service impls |