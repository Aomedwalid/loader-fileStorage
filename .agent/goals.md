# Goals

What this repository should become, and how current work fits in.

## Product vision

A reliable **file manager repository service / storage microservice**: clients upload large files in resumable chunks, and the service stores them safely (type-checked, virus-scanned, encrypted at rest) and serves them back on demand. The current codebase is an early, working slice of that vision: the full chunked-upload → finalize → list → download loop exists and runs end-to-end.

## Where the code stands today

Working today:
- Chunked upload sessions with Redis-backed progress and resumability (status endpoint).
- Finalization: chunk merge, MIME detection/allow-listing, ClamAV scanning, AES-256-CBC at-rest encryption, metadata persistence.
- Paginated file listing and streaming download with on-the-fly decryption.
- Scheduled garbage collection of abandoned sessions.

Gaps / rough edges (see `troubleshooting.md` and `decisions.md` for detail):
- Upload sessions are never marked `COMPLETED`; Redis progress keys are not cleaned up after completion.
- Spring Cache is used for status + listing without eviction, so reads can be stale.
- No authentication/authorization anywhere (no Spring Security).
- Only a placeholder test exists; no unit/integration tests.
- No Docker, no CI/CD, no deployment artifacts; `application.yml` is a local secret file.
- ClamAV path is hardcoded to a Windows path; path configuration is not portable.
- There are dev leftovers (`rest/test.java`, unused `FileDownloadResponse`, commented config).

## Near-term goals (proposed direction)

1. Correctness first: mark sessions `COMPLETED`, clean up Redis keys on completion, evict caches on writes (fix the known stale-read bugs).
2. Make configuration portable and secret-safe: externalize DB/Redis/ClamAV settings via environment variables, remove hardcoded paths, keep `application.yml` out of git (already gitignored).
3. Add a real test suite: unit tests for mappers/services and integration tests for the pipeline using test containers or embedded Redis/Postgres.
4. Decide on authentication/authorization scope before this becomes a public service — encryption protects at rest, but nothing protects the API.

## Principles to preserve while evolving

- Keep the uniform `MainResponse` envelope and mapper-based DTO mapping.
- Keep the layered service architecture; deepen modules rather than flattening them.
- Keep files encrypted at rest and scanned before storage.
- Keep the pipeline stages ordered as: merge → validate → scan → encrypt → persist.
