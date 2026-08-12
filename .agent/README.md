# .agent — Agent Knowledge System

This folder holds durable knowledge for AI agents (Codex, Claude Code, OpenCode, Cursor, ...) working on this repository. The root `agent.md` is the entry point; start there, then come here for depth.

## How this system is organized

| File | Contents |
|---|---|
| `README.md` | This index |
| `architecture.md` | Components, services, dependencies, data flow, boundaries, file map |
| `pipeline.md` | The complete file lifecycle, stage by stage |
| `development-rules.md` | Conventions and coding standards an agent must follow |
| `goals.md` | What this repository should become |
| `engineering-principles.md` | Principles to uphold (reliability, security, maintainability, ...) |
| `commands.md` | How to build, run, test, and deploy |
| `constraints.md` | Hard constraints that must never be broken casually |
| `troubleshooting.md` | Known problems and how to investigate them |
| `decisions.md` | Architecture decisions discovered from the implementation |

## How to use this system

1. Read `agent.md` (root) first — it is the operating manual.
2. For a specific task, read the relevant file(s) above before touching code.
3. Keep this system in sync with the code. When the architecture changes, update these files.

## Related skill folders

Installed project skills live in `.agents/skills/`:

- `java-springboot` — Spring Boot best practices
- `spring-boot-rest-api-standards` — REST API design, DTOs, error handling, pagination
- `springboot-security` — security, secrets, input validation

Their provenance and hashes are recorded in `skills-lock.json` at the repository root.

## Derived from code

Every claim in this folder was derived by reading the source at the time of writing (see `git log`). If the code changes and the documentation drifts, update the docs as part of the change.
