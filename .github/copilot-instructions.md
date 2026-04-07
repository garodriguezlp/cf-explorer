# Copilot Instructions for cf-explorer

## Project context

`cf-explorer` is a JBang-based interactive terminal browser for Cloud Foundry, written in Java 17.
It lets users navigate orgs, spaces, and apps in a TUI, export app environment variables to `.env` files, and inspect JKS keystores embedded in app environment variables (`Ctrl+K`).

### Key technologies

- **JBang** — runs the whole project as a script; no build tool or project structure required
- **TamboUI** — terminal UI toolkit (render-thread event loop, layout, widgets)
- **Feign** — declarative HTTP client for the CF v3 API and UAA
- **PicoCLI** — CLI option parsing with properties-file and env-var fallback
- **Jackson** — JSON serialization for API responses and local cache

### Key features

- **Catalog browsing** — navigates orgs, spaces, and apps with live filtering.
- **Env export** — writes a sourceable `.env` file with excluded keys and post-processors (e.g., `SPRING_APPLICATION_JSON=JSON`).
- **Keystore inspection** (`Ctrl+K`) — decodes a base64-encoded JKS from an app's env var, saves it locally, and displays certificate details (alias, subject DN, issuer, expiry color-coded by freshness).
- **Browser launch** (`Ctrl+O`) — opens the selected app in Apps Manager.

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

### How to run locally

There are no automated tests. Manual testing is done against a WireMock stub server.

```bash
# Terminal 1 — start the mock CF API
./jbang mock-server

# Terminal 2 — run the app against it
./jbang cf-explorer
```

The stubs in `wiremock/mappings/` cover OAuth, orgs, spaces, apps, and env vars.

---

## Code style

- Prefer small, single-purpose methods that operate at one level of abstraction.
- Names should be intention-revealing — avoid abbreviations outside the domain (`CF`, `UAA` are fine).
- Boolean method names should read as predicates: `isLoaded()`, `hasEnvVars()`, `canExport()`.
- Prefer private helper methods over inline complexity; avoid deep nesting.
- Extract magic strings and numbers as named constants.

---

## Architecture and layering

- `Domain` is pure — no I/O, no framework dependencies.
- `Infra` owns all HTTP calls and file I/O; those concerns should not leak into use-case or domain logic.
- All UI state mutations go through the render thread via `UiDispatcher` / `runner().runOnRenderThread()`.
- `View` is a pure render function over `Model` state. Display-formatting logic (truncation, column layout) belongs in `View`, not `Model`.
- `FeignCfPlatformGateway` is constructed once in `UseCases` and shared via injection — don't construct it separately in individual use-case classes.
- Prefer composition over inheritance.

---

## What to skip

- TLS-related security findings — not relevant for this tool's threat model.
- Minor style nits that don't affect readability or maintainability.

---

## General guidance for agents

- Favor long-term maintainability and clarity over micro-optimizations.
- When proposing refactors, explain *why* — what principle or problem drives the change.
- Prioritize high-impact improvements; don't pad PRs with low-value changes.
- Keep changes surgical — touch only what is necessary to address the issue.
- When adding new behavior, place it in the layer that owns that concern.
