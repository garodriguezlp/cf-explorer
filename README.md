# cf-explorer

An interactive terminal browser for Cloud Foundry. Navigate your orgs, spaces, and apps; press Enter on any app and it exports a `.env` file containing all of its environment variables, ready to `source`.

## Usage

Requires [jbang](https://www.jbang.dev/).

**Without cloning — zero install (Linux/macOS):**

```bash
curl -Ls https://sh.jbang.dev | bash -s - run cf-explorer@garodriguezlp/cf-explorer
```

**Without cloning — zero install (Windows PowerShell):**

```powershell
iex "& { $(iwr -useb https://ps.jbang.dev) } run cf-explorer@garodriguezlp/cf-explorer"
```

**With jbang already installed:**

```bash
jbang run cf-explorer@garodriguezlp/cf-explorer
```

**From a local clone:**

```bash
./jbang cf-explorer
```

**Configuration** — pass flags or set environment variables:

| Flag | Env var | Default | Description |
|---|---|---|---|
| `--uaa-url` | `CF_UAA_URL` | `http://localhost:9090` | UAA base URL |
| `--cf-api-url` | `CF_API_URL` | `http://localhost:9090` | CF API base URL |
| `--cf-username` | `CF_USERNAME` | `admin` | CF username |
| `--cf-password` | `CF_PASSWORD` | `admin` | CF password |
| `--cf-web-url` | `CF_WEB_URL` | `http://localhost:9090` | Apps Manager URL (used by Ctrl+O) |
| `--fresh` | — | `false` | Bypass local cache and fetch fresh data from CF |
| `--exclude-key` | — | `TRUSTSTORE` | Keys to exclude from the exported `.env` (repeatable) |
| `--post-process` | — | `SPRING_APPLICATION_JSON=JSON` | Apply a named processor to a key (repeatable) |
| `--keystore-var` | `KEYSTORE_VAR` | `KEYSTORE` | CF env var holding the base64-encoded JKS keystore |
| `--keystore-password-var` | `KEYSTORE_PASSWORD_VAR` | `KEYSTORE_PASSWORD` | CF env var holding the base64-encoded keystore password |

You can also drop a `CfExplorer.properties` file next to the script and PicoCLI will pick it up automatically.

**Key bindings** (browsing screen):

| Key | Action |
|---|---|
| `↑` / `↓` | Navigate the app list |
| `Enter` | Export `.env` for the selected app |
| `Ctrl+O` | Open the selected app in Apps Manager (browser) |
| `Ctrl+K` | Inspect the JKS keystore for the selected app |
| type | Filter apps by name |
| `Esc` | Clear the filter |
| `Ctrl+C` / `q` | Quit |

**Output** — selecting an app writes `<app-name>-<timestamp>.env` under `~/.cf-explorer/envs/`, ready to `source`. Pressing `Ctrl+K` writes the decoded JKS to `~/.cf-explorer/jks/` and displays each certificate's alias, subject DN, issuer, and expiry (color-coded by freshness).

## Try it locally with WireMock

Start a simulated CF API:

```bash
jbang run mock-server@garodriguezlp/cf-explorer
# or, from a local clone:
./jbang mock-server
```

The `mock-server` alias starts the WireMock stub server defined in `wiremock/`. Then, in a second terminal:

```bash
jbang run cf-explorer@garodriguezlp/cf-explorer
# or, from a local clone:
./jbang cf-explorer
```

The stubs under `wiremock/mappings/` cover OAuth token exchange, orgs, spaces, apps, and environment variables.

## How it works

Powered by:

- **[TamboUI](https://tamboui.dev)** — terminal UI toolkit (layout, widgets, render-thread event loop)
- **[Feign](https://github.com/OpenFeign/feign)** — declarative HTTP client for the CF v3 API and UAA
- **[PicoCLI](https://picocli.info)** — CLI option parsing with properties-file and env-var fallback
- **[Jackson](https://github.com/FasterXML/jackson)** — JSON serialization for API responses and local cache
- **[jbang](https://www.jbang.dev/)** — runs the whole thing as a script with zero build setup

The architecture follows a strict render-thread model: all state lives in `Model`, mutated only via `runner().runOnRenderThread()`; `View` is a pure function over that state; background fetches are plain `CompletableFuture`s. The source is split across self-contained files — `Domain`, `Model`, `View`, `Infra`, `UseCases`, `Controller`, and `KeyHandler` — each with a single clear responsibility.
