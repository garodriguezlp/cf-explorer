# Copilot Instructions for cf-explorer

## Project context

`cf-explorer` is a JBang-based interactive terminal browser for Cloud Foundry, written in Java 17.
It lets users navigate orgs, spaces, and apps in a TUI and export app environment variables to `.env` files.

### Key technologies

- **JBang** — runs the whole project as a script; no build tool or project structure required
- **TamboUI** — terminal UI toolkit (render-thread event loop, layout, widgets)
- **Feign** — declarative HTTP client for the CF v3 API and UAA
- **PicoCLI** — CLI option parsing with properties-file and env-var fallback
- **Jackson** — JSON serialization for API responses and local cache

### Source layout (JBang constraint: one class/layer per file)

| File | Responsibility |
|---|---|
| `CfExplorer.java` | Entry point, CLI wiring, config assembly |
| `Domain.java` | Pure domain types — no I/O, no framework dependencies |
| `Model.java` | UI state, mutated only on the render thread |
| `View.java` | Pure render function over `Model` state |
| `Controller.java` | Coordinates use-cases and dispatches UI state updates |
| `UseCases.java` | Application logic — orchestrates domain and infra |
| `Infra.java` | HTTP clients, file I/O, caching, external integrations |
| `KeyHandler.java` | Keyboard input routing and key-binding logic |

---

## Clean code standards (non-negotiable)

### Method design

- Methods must be **small and single-purpose**.
- Every method must operate at a **single level of abstraction** — push implementation details down to private helpers or extracted abstractions.
- Names must be **expressive enough** to make the method's intent obvious without needing comments.
- If a method name requires a comment to clarify what it does, rename the method or extract a helper.

### Naming

- Class, method, and variable names must be **intention-revealing**.
- Avoid abbreviations unless they are universally understood in the domain (e.g., `CF` for Cloud Foundry, `UAA` for User Account and Authentication).
- Boolean method names should read as predicates: `isLoaded()`, `hasEnvVars()`, `canExport()`.
- Constants should be named for their **meaning**, not their value.

### Structure

- Keep methods short — if a method does more than one thing, split it.
- Prefer **private helper methods** to inline complexity.
- Avoid deep nesting; extract conditions and loops into well-named methods.
- No magic numbers or magic strings — extract them as named constants.

---

## Architecture and layering

- **Respect layer boundaries**: `Domain` is pure (no I/O, no frameworks), `Infra` handles I/O and external calls, `View` handles rendering, `Controller`/`UseCases` coordinate.
- **Do not let infrastructure concerns leak** into domain or use-case logic.
- All UI state mutations must happen **on the render thread** via `UiDispatcher` / `runner().runOnRenderThread()`.
- `View` must remain a **pure function** over `Model` state — no side effects.
- **Prefer composition over inheritance**.

---

## What to skip

- **TLS-related security findings** — not relevant for this tool's threat model.
- **Minor style nits** that don't affect readability or maintainability.

---

## General guidance for agents

- Favor **long-term maintainability and clarity** over micro-optimizations.
- When proposing refactors, explain **why** — what principle or problem drives the change.
- Prioritize **high-impact improvements**; don't pad PRs with low-value changes.
- Keep changes **surgical** — touch only what is necessary to address the issue.
- When adding new behavior, place it in the layer that owns that concern; do not reach across layer boundaries.
