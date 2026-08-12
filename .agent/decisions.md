# Architecture Decisions

Decisions discovered from the existing implementation (reverse-engineered ADRs). Not formal ADRs yet — these record *why the code is the way it is* so future agents don't undo them.

## D1 — Redis is both the upload-progress store and the Spring cache

**Status**: Accepted (as built).
**Context**: Chunk progress must survive across requests and be cheap to read for the status endpoint; the app also wants caching of list/status reads.
**Decision**: A single Redis instance serves two roles: raw progress state (hash `progress : {id}` + set `Chunk : {id}` with 2h TTL, manipulated via `RedisTemplate`) and the Spring cache manager (`RedisCacheManager`, default 1h TTL, `@Cacheable` on status and listing).
**Consequences**:
- Progress is lost on Redis flush; sessions then look orphaned to the sweeper.
- Cache entries for status/listing are not evicted on writes → stale reads (see troubleshooting #3).
- Two different TTL regimes (2h progress vs 1h cache) — keep them distinct mentally.

## D2 — File name is the natural key for uniqueness and download

**Status**: Accepted.
**Context**: The service rejects duplicate file names at session start; downloads are addressed by `fileName`.
**Decision**: `fileName` is `unique` on both `UploadSession` and `FileEntity`; `download/{fileName}` resolves via `findByFileName`.
**Consequences**: two files with the same name cannot coexist — the product constraint today. Download name gets the stored extension appended when missing (`FileServiceImpl.downloadFile`).

## D3 — Files are encrypted at rest with an IV-prefixed AES/CBC stream

**Status**: Accepted; load-bearing.
**Context**: Untrusted uploads must be protected at rest; the app also must be able to serve them later.
**Decision**: `[16-byte IV][AES/CBC/PKCS5Padding ciphertext]` written streaming to `{name}.enc`; the plaintext is deleted. The AES key is stored base64 at `app.encryption.aes-key` and auto-generated on first use; downloads read the IV prefix and decrypt streaming.
**Consequences**: Strong file protection (no plaintext at rest), but the format is a migration constraint (see constraints). The key is only as safe as the file path's filesystem permissions. No key-rotation mechanism exists.

## D4 — Completion is a strict, ordered pipeline

**Status**: Accepted.
**Decision**: `uploadCompleted` always runs: merge (ordered by chunk index) → Tika MIME detection against `FileTypeConfig.ALLOWED_MIME_TYPES` → rename to the real extension → ClamAV scan → optional AES encryption → save `FileEntity`.
**Consequences**: Predictable safety ordering; but any stage failure mid-pipeline leaves partial state (e.g. an unscanned merged file if encryption throws — though scan happens before encryption, so an unencrypted file can only exist pre-encryption). No rollback/cleanup on later-stage failure today.

## D5 — Abandoned-session reclamation uses Redis key absence as the signal

**Status**: Accepted (by design, but surprising).
**Context**: Clients may never complete an upload; temp chunks and DB rows would leak.
**Decision**: A 4h scheduled sweeper finds sessions older than 110 minutes whose `progress : {id}` Redis key is gone (key TTL = 2h, so any session not touched for ~2h loses its key), bulk-marks them `CANCELED`, deletes them from the DB, and deletes their temp dirs.
**Consequences**: Self-cleaning, but a Redis flush or >2h app downtime can cause legitimate sessions to be swept. Deleting temp dirs before deleting DB rows would be slightly safer (currently rows go first).

## D6 — Error handling is centralized in a `@RestControllerAdvice` with typed domain exceptions

**Status**: Accepted.
**Decision**: Services throw custom `RuntimeException`s (grouped under the `costumeErrors` package); `GlobalHandler` maps each to a `MainResponse` envelope with a chosen HTTP status. The catch-all `Exception` handler echoes the exception message to the client.
**Consequences**: Consistent client contract; but the catch-all leaks internal messages and many handlers repeat near-identical `MainResponse`/`ErrorResponse` building code (a candidate for consolidation via `ErrorMapper`/`MainResponseMapper`).

## D7 — `ddl-auto: update` instead of migrations

**Status**: Accepted (current), flagged for change.
**Context**: No Flyway/Liquibase; schema is managed by Hibernate at startup.
**Decision**: `spring.jpa.hibernate.ddl-auto: update`.
**Consequences**: Schema evolves automatically in dev but is not versioned or production-safe (no column-rename/type-change handling). Any real deployment needs a migration strategy (see goals).

## D8 — No authentication layer

**Status**: Accepted as a deliberate early-stage scope cut.
**Decision**: No Spring Security dependency; all endpoints are open.
**Consequences**: Fine for a local/internal service slice; a blocker for public exposure. See goals.
