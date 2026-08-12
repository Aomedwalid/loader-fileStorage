# Development Rules

Conventions every agent must follow when modifying this project. Derived from the existing codebase.

## Code structure & style

- **Package layout**: keep the existing layered structure — `rest/`, `services/` (+ `services/impl/`), `repos/`, `entities/`, `dto/request|response/`, `mapper/`, `configs/`, `exception_handling/`. Do not introduce a new layout without a plan.
- **Controller→Service pattern**: controllers stay thin. They call a service impl, wrap the result with `MainResponseMapper` (`success(...)`/`failed(...)`), and return `ResponseEntity<MainResponse>`. Follow `rest/FileUpload.java` as the template.
- **Interfaces + impls**: services have an interface in `services/` and an implementation in `services/impl/`. Keep that split when adding service methods.
- **Lombok**: use Lombok (`@RequiredArgsConstructor` for constructor injection with `private final` fields, `@Getter/@Setter/@Builder` on entities/DTOs, `@Slf4j` for logging). Constructor injection via Lombok is the established DI style.
- **Mappers**: do not map manually in controllers/services. Add a mapper component under `mapper/` (or `mapper/uploadProccess/`) and use it, matching existing patterns.
- **Response envelope**: all success and error responses flow through `MainResponse` via `MainResponseMapper`. New endpoints must return this envelope (except streaming download).
- **Errors**: domain failures are thrown as custom runtime exceptions under `exception_handling/costumeErrors/` and mapped to HTTP responses in `GlobalHandler`. Add a new exception class + a `@ExceptionHandler` in `GlobalHandler` rather than returning error bodies directly from services.
- **Validation**: input DTOs use Jakarta Bean Validation annotations (`@NotBlank`, `@Size`, `@Min`, `@Max`, ...) and `@Valid` at the controller boundary (`UploadSessionRequest` is the reference).
- **Comments**: the codebase is minimally commented; keep new code self-explanatory. Do not add comments unless they clarify non-obvious behavior.

## Conventions to match exactly

- Upload IDs: `upload_` + 12-char NanoId (`UploadSessionMapper.uploadIdGenerator`).
- Final file naming: `{uploadId}_{fileName}{extension}`; encrypted form appends `.enc`.
- Redis key prefixes come from config (`app.redis.*`); always resolve them via `@Value`, never hardcode.
- Status enum: `UploadSession.Status { IN_PROGRESS, COMPLETED, CANCELED }`.
- File type allow-list lives in `FileTypeConfig.ALLOWED_MIME_TYPES`; MIME→extension mapping in `uploadCompletionServiceImpl.extensionFromMime` — keep the two consistent.
- Exception "codes" (`"code u1"`, `"code : 1"`, `"code 6"`, ...) are ad-hoc strings; keep the same style for new ones but prefer clearer messages.

## Behavior rules

- **Never break the crypto format**: the IV (first 16 bytes) + AES/CBC/PKCS5Padding layout is load-bearing for already-stored `.enc` files (see constraints).
- **Never break the Redis progress contract**: `progress : {id}` hash fields (`totalChunks`, `receivedChunks`, `createdAt`) and the `Chunk : {id}` set are read by status, completion, and the sweeper. Change all three together.
- **Order of finalization matters**: merge → MIME check → extension fix → ClamAV scan → encrypt → persist metadata. A completed file without a `FileEntity` row is unreachable by listing/download; a row without the file is a 500 on download.
- **Do not swallow exceptions**: existing code converts checked exceptions into domain exceptions with meaningful messages. Follow suit.

## Change workflow

1. Read `agent.md`, `pipeline.md`, and `architecture.md` first.
2. Locate the exact code path you are changing (file map in `architecture.md`).
3. Prefer small, focused changes that reuse existing mappers/services.
4. Run the build and tests after the change (see `commands.md`).
5. Review your own diff before finishing.

## Testing

- Only a `@SpringBootTest contextLoads` smoke test exists. When adding behavior, add focused unit tests (JUnit 5 via `spring-boot-starter-test`). Do not require a running PostgreSQL/Redis for unit tests; integration tests that do can be gated separately.
- If your change touches the file pipeline, verify the full flow manually (requires PostgreSQL, Redis, and ClamAV per `commands.md`) or with an integration test.
