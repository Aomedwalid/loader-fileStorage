# Engineering Principles

Principles an agent should uphold when working here. Order roughly reflects their importance to this service.

## Reliability

- **Idempotent chunk uploads**: a chunk index must be accepted at most once; a retried index is rejected. Preserve this.
- **Crash safety**: chunks live on disk + Redis progress independently; the sweeper reclaims abandoned sessions. If you change the sweeper thresholds (110 min cutoff, 4h interval, 2h Redis TTL), keep them consistent — Redis TTL expiring before the sweeper runs is exactly how orphan detection works.
- **Transactionality**: DB writes and filesystem writes are not atomic here. Be careful when reordering steps in `uploadCompleted`; a failure mid-pipeline must leave a recoverable state, not a half-registered file.

## Security

This is a file-storage service handling untrusted uploads. Security is not optional:

- Files are **virus-scanned** (ClamAV) and **type-verified** (Tika allow-list) before being stored or served. Do not weaken this ordering.
- Files are **encrypted at rest** (AES-256-CBC, IV-prefixed). Never change the cipher layout without a migration plan for already-encrypted files.
- Secrets (DB password, AES key, Redis password) must never be committed. `application.yml` is gitignored and carries local credentials — keep it that way.
- Treat the AES key file as a secret with filesystem-level protection; key auto-generation on first use is convenient but the key path must be secured.
- The API currently has **no authentication** — when adding auth, use the installed `springboot-security` skill and do not roll a custom scheme.

## Maintainability

- Reuse existing patterns (mappers, `GlobalHandler`, custom exceptions, Lombok) instead of inventing parallel ones.
- Keep classes small and single-purpose (the codebase already splits responsibilities cleanly; preserve that).
- Write tests for new behavior — the repo is under-tested today.
- Keep `MainResponse`/`ErrorResponse` shapes stable; they are the public API contract.

## Performance

- **Stream, don't buffer**: uploads and downloads stream through `StreamingResponseBody`/file channels. Do not load files into memory (`byte[]` is only used in the unused `FileDownloadResponse`).
- Chunk merge uses zero-copy `FileChannel.transferTo` — keep it.
- Redis is used to avoid hitting the DB for progress reads; keep hot-path progress reads in Redis.
- Be aware the current cache use (status + listing) trades freshness for speed and is not evicted on writes (see troubleshooting) — fix by evicting, not by dropping caching wholesale.

## Simplicity

- Prefer the simplest change that satisfies the requirement. Do not add frameworks, abstractions, or dependencies without need.
- Do not refactor code you are not modifying; avoid unnecessary churn (the instruction is to deepen, not rewrite).

## Clean architecture

- Layering is enforced by convention: `rest/` → `services/` → `repos/`; entities only in `entities/`; DTOs only in `dto/`; mapping in `mapper/`. Keep dependencies pointing inward.
- Domain errors surface as typed exceptions mapped in `GlobalHandler`, keeping controllers and services free of HTTP concerns.
