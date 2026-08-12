# Architecture

## Overview

`FileUploadTest` is a Spring Boot 3.5.5 microservice (Java 17, Maven) that implements a **chunked, resumable file upload and retrieval service**. It is the "storage service" of a larger file-manager system: clients split a file into chunks, upload them one at a time, and finalize when all chunks have arrived. The service:

- Tracks upload sessions in **PostgreSQL** (via Spring Data JPA / Hibernate).
- Tracks per-chunk progress in **Redis** (both as a progress store and as a cache).
- Validates file type via **Apache Tika** against an allow-list.
- Scans every completed file with **ClamAV**.
- Encrypts completed files at rest with **AES-256-CBC** (IV prefixed).
- Streams decrypted files back on download.

There is **no authentication/authorization** (no Spring Security). There is **no Dockerfile, no CI/CD, no deployment config** in the repository.

## High-level data flow

```
Client
  │  POST /api/uploads/start            (fileName, totalChunks, contentType)
  ▼
UploadSession (PostgreSQL)  ──►  Redis: progress:uploadId (hash), Chunk:uploadId (set), TTL 2h
  │  POST /api/uploads/{id}/chunk/{i}   (multipart "file")
  ▼
temp dir {base-path}/{uploadId}/i       ──►  Redis set add + increment receivedChunks
  │  GET  /api/uploads/{id}/status
  ▼
Redis progress + chunk set  (cached 1h)
  │  POST /api/uploads/{id}/complete
  ▼
merge chunks → MIME check (Tika) → rename w/ real extension → ClamAV scan → AES encrypt
  → FileEntity (PostgreSQL) + {final-path}/{uploadId}_{fileName}.enc
  │  GET /api/files            (page/size, cached)
  │  GET /api/files/download/{fileName}   (streams decrypted bytes)
  ▼
Client
```

## Component map (package `org.uploader.fileuploadtest`)

### REST layer (`rest/`)
| Class | Base path | Responsibility |
|---|---|---|
| `FileUpload` | `/api/uploads` | `start`, `chunk/{index}`, `status`, `complete` endpoints |
| `UsersFiles` | `/api/files` | paginated metadata listing, streaming download |
| `test` | `/test` | placeholder endpoint returning `"test"` (dev leftover) |

### Service layer (`services/` + `services/impl/`)
| Interface | Impl | Responsibility |
|---|---|---|
| `UploadService` | `UploadServiceImpl` | session creation, chunk upload, status read, orphan cleanup |
| `UploadCompletionService` | `uploadCompletionServiceImpl` | merge, MIME check, ClamAV scan, AES encryption, metadata save |
| `FileService` | `FileServiceImpl` | paginated file metadata, streaming (decrypting) download |

Note: `FileServiceImpl` depends directly on the concrete `uploadCompletionServiceImpl` (for `getOrCreateSecretKey`), not on the interface — a coupling to keep in mind.

### Persistence (`entities/`, `repos/`)
- `UploadSession` — one row per upload (id, uploadId, fileName unique, totalChunks, contentType, status `IN_PROGRESS|COMPLETED|CANCELED`, createdAt). Note: nothing in the code ever sets status to `COMPLETED` (see troubleshooting).
- `FileEntity` — one row per finalized file (id, fileName unique, uploadSession, filePath, fileType, fileSize, createdAt as String).
- `UploadSessionRepo` — `findByUploadId`, `existsUploadSessionByFileName`, `findByCreatedAtBefore`, `markListAsStatus` (bulk UPDATE), `deleteByStatus`.
- `FileRepo` — `findAllMetadata` (JPQL constructor projection → `FileResponse`), `findByFileName`.

### Config (`configs/`)
- `RedisConfig` — `RedisTemplate<String,String>` (string serializers) and `CacheManager` (default cache TTL 1h, nulls disabled).
- `SchedularConfig` — `@EnableScheduling`; every 4h calls `UploadServiceImpl.cleanOrphanedSessions()` (the "sweeper").
- `FileTypeConfig` — allow-list of MIME types (images, mp4/webm/mov, pdf, json, txt).

### DTOs (`dto/`)
- `request/UploadSessionRequest` — `fileName`, `totalChunks` (1..100), `contentType`, bean-validated.
- `response/main/MainResponse` — uniform envelope `{success, status, message, details, errors}`.
- `response/ErrorResponse` — `{timestamp, path, details}`.
- `response/files/` — `FileResponse`, `PageResponse`, `FileDownloadResponse` (unused currently).
- `response/upload/` — `UploadSessionResponse`, `ChunkResponse`, `UploadStatusResponse`, `UploadCompletedResponse`.

### Mappers (`mapper/`)
- `MainResponseMapper` — `success(...)` / `failed(...)` envelope builders.
- `ErrorMapper` — builds `ErrorResponse`.
- `PageMapper` — builds `PageResponse`.
- `uploadProccess/` — `UploadSessionMapper` (also generates the `upload_` + 12-char NanoId uploadId), `ChunksMapper`, `UploadStatusMapper`, `CompletedResponse`, `FileEntityMapper`.

### Exception handling (`exception_handling/`)
- `GlobalHandler` (`@RestControllerAdvice`) maps custom exceptions + framework exceptions (validation, multipart, wrong method, 404, data-integrity, catch-all) to the `MainResponse` envelope.
- Custom exceptions under `costumeErrors/` (note the typo in the package name — it is intentional to keep for now, see constraints):
  - `directory/` — `DirectoryException`, `DirectorySortingException`
  - `encryption/` — `AesEncryptionException`
  - `uploading/` — `AlreadyExistedFileName`, `AssemblingException`, `IllegalFileException`, `InactivatedUploadSession`, `IncompletedUploadSession`, `InvalidChunk`, `InvalidUploadSession`

## External dependencies (runtime)

| Dependency | How it is used | Config |
|---|---|---|
| PostgreSQL | UploadSession + FileEntity persistence | `spring.datasource.url` = `jdbc:postgresql://localhost:5432/filetest` |
| Redis | upload progress (hash + set), Spring cache | `spring.data.redis.host` localhost, port 6379 |
| ClamAV binary | `clamscan.exe` per completed file | hardcoded path `C:\Program Files\ClamAV\clamscan.exe` |
| Apache Tika | MIME detection of merged file | `tika-core` |
| AES key file | key persisted at `app.encryption.aes-key` path, base64 | auto-generated on first use |

## Build / dependency stack

- Java 17, Spring Boot 3.5.5 parent POM.
- Starters: `web`, `data-jpa`, `validation`, `cache`, `data-redis`, `devtools` (runtime), `test`.
- Third-party: `lombok` (with annotation processor), `com.aventrix.jnanoid` 2.0.0, `commons-io` 2.14.0, `tika-core` 3.1.0, `org.postgresql:postgresql`.
- No test dependencies beyond `spring-boot-starter-test`; only a `contextLoads` smoke test exists.
