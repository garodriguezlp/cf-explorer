# cf-explorer: next version

An interactive, command-driven terminal browser for Cloud Foundry. Browse and
filter your organizations, spaces, and applications, refresh or cache the
catalog per profile, export `.env` files, open apps in Apps Manager, and
inspect JKS keystores without exposing secret values in the terminal.

The original application remains available on `main`.

## Requirements

Java 17 or newer and Maven are required for the Maven build. The included
JBang wrapper uses JBang 0.135.1 and can provision Java when necessary.

## Build and run

```sh
mvn verify
mvn compile exec:java
java -jar target/cf-explorer.jar
./jbang src/main/java/cf/explorer/CfExplorer.java
./jbang cf-explorer
```

On Windows, use `jbang.cmd` instead of `./jbang`. All four launch commands
open the same Tambo UI using the JLine terminal backend. Use `/quit` or Ctrl-C
to quit; pass `--help` for CLI help.
Run the app in a terminal. The executable jar includes its runtime dependencies.

## Shell and commands

Type to filter apps by name, state, organization, space, GUID, or
created/updated date. Matching is case-insensitive; whitespace-separated tokens
must all match some field. Matches are highlighted in the results. Use Up/Down
to select an app, indicated by the highlighted row and `>`. Left/Right,
Home/End, Backspace, and Delete edit the filter.

Type `/` to switch the same input to command mode without changing the filter;
type a command name to narrow the available suggestions. Up/Down selects a
suggestion, Tab completes it, and Enter executes an exact command or the
selected suggestion. An unknown or unavailable command is reported in place.
Esc cancels command entry, returns from a subpage, or clears the filter.

| Command | Availability | Action |
|---|---|---|
| `/export-env` | Selected app in catalog | Fetch app env vars, write `<app>-<timestamp>.env` under `envs/`, optionally copy path to clipboard |
| `/open` | Selected app with org/space/app GUIDs | Open the selected app in CF Apps Manager in the default browser |
| `/keystore` | Selected app in catalog | Decode configured base64 JKS & password vars, write `<app>-<timestamp>.jks` under `jks/`, and inspect X.509 certificates |
| `/refresh` | Root catalog view | Fetch fresh catalog data from Cloud Foundry, update cache, and preserve filter and selected app GUID |
| `/back` | Any subpage | Return to the previous view |
| `/quit` | Always | Exit cf-explorer |

Blocking network, filesystem, clipboard, and browser operations run off the
render thread and apply UI updates back on the render thread. Navigating away
while an async export or keystore operation is running discards its result view
but does **not** cancel the underlying write or undo side effects.

## Configuration and profiles

| Option | Environment variable | Default | Description |
|---|---|---|---|
| `--env` | — | — | Profile name (`[a-zA-Z0-9_-]+`); reads `CF_*_<ENV>` first and scopes storage to `~/.cf-explorer/<env>/` |
| `--uaa-url` | `CF_UAA_URL[_<ENV>]` | `http://localhost:9090` | UAA base URL |
| `--cf-api-url` | `CF_API_URL[_<ENV>]` | `http://localhost:9090` | Cloud Foundry API base URL |
| `--cf-username` | `CF_USERNAME[_<ENV>]` | `admin` | Cloud Foundry username |
| `--cf-password` | `CF_PASSWORD[_<ENV>]` | `admin` | Cloud Foundry password |
| `--cf-web-url` | `CF_WEB_URL[_<ENV>]` | `http://localhost:9090` | CF Apps Manager base URL |
| `--fresh` | — | `false` | Bypass local catalog cache on startup and fetch live data |
| `--exclude-key` | — | `TRUSTSTORE` | Keys to exclude from `.env` export (exact, case-sensitive; repeatable) |
| `--post-process` | — | `SPRING_APPLICATION_JSON=JSON` | Named post-processor per key (`KEY=PROCESSOR`; repeatable) |
| `--keystore-var` | `KEYSTORE_VAR` | `KEYSTORE` | CF env var holding the base64-encoded JKS keystore |
| `--keystore-password-var` | `KEYSTORE_PASSWORD_VAR` | `KEYSTORE_PASSWORD` | CF env var holding the base64-encoded keystore password |
| `--[no-]copy-to-clipboard` | — | `true` | Copy exported `.env` / `.jks` file path to system clipboard |

Precedence for connection options:
1. Explicit CLI flag (`--cf-api-url`, etc.)
2. Profile-specific Java system property (`CF_*_<ENV>`) when `--env` is set
3. Profile-specific environment variable (`CF_*_<ENV>`) when `--env` is set
4. Properties file (`~/.cf-explorer.properties` or `picocli.defaults.cf-explorer.path`) for `cf.api.url`
5. Base Java system property (`CF_*`)
6. Base environment variable (`CF_*`)
7. Built-in default

Local files are stored under `~/.cf-explorer/` (or `~/.cf-explorer/<env>/` when `--env` is set):
- `cache/` — `organizations.json`, `spaces.json`, `apps.json` (compatible with `main`)
- `envs/` — exported `<app-name>-<yyyyMMdd-HHmmss>.env` files
- `jks/` — exported `<app-name>-<yyyyMMdd-HHmmss>.jks` files

## Compatibility notes vs `main`

- **Command-driven shell**: Actions are invoked via `/` commands (`/export-env`,
  `/open`, `/keystore`, `/refresh`, `/back`, `/quit`) instead of `Enter`, `Ctrl+O`,
  `Ctrl+K`, `Ctrl+F`, and `q`, so typing always edits the filter cleanly.
- **Keystore filename security**: Decoded JKS files are written as
  `<app-name>-<timestamp>.jks` and **no longer embed the decoded password in the
  filename** (legacy `main` wrote `<app-name>-pass_<password>-<timestamp>.jks`).
- **Refresh resiliency**: `/refresh` keeps the previous catalog visible while
  reloading and on failure, and only overwrites the on-disk cache after a live
  fetch succeeds.
- **Clipboard reporting**: A clipboard failure is reported separately from a
  successful `.env` or `.jks` file write.
- **TLS**: Uses standard Java `HttpClient` TLS verification rather than a
  trust-all SSL client.

## Source organization

Maven discovers the production classes in the usual source tree. For JBang,
`CfExplorer.java` lists every other source file in a separate `//SOURCES`
directive, with paths relative to the entry point. Add a directive there when
adding a production source file.

- `cf.explorer.config` — CLI configuration model (`ExplorerConfig`, `EnvProcessor`)
- `cf.explorer.catalog` — catalog domain model (`ApplicationCatalog`), CF v3 API client (`CloudFoundryCatalogClient`), profile cache (`CatalogCacheStore`, `CachedCatalogService`)
- `cf.explorer.export` — `.env` formatting and export (`EnvFileWriter`, `EnvExportService`)
- `cf.explorer.browser` — Apps Manager URL builder and OS browser launcher (`AppsManagerUrl`, `BrowserLauncher`)
- `cf.explorer.keystore` — JKS decoding, safe file writing, and X.509 certificate inspection (`KeystoreService`, `KeystoreEntry`)
- `cf.explorer.io` — clipboard abstraction (`ClipboardSupport`)
- `cf.explorer.model` — connection-free shell state, commands, and page interface
- `cf.explorer.ui` — TamboUI views (`ShellView`, `ApplicationCatalogPage`, `EnvExportPage`, `KeystoreViewPage`)
- `cf.explorer` — entry point (`CfExplorer`), screen wiring (`ExplorerScreen`), and command registration (`ExplorerCommands`)

## Mock server

The existing WireMock fixtures and local workflow are retained unchanged:

```sh
./jbang mock-server
```

The mock server listens on port 9090 by default; the default connection
settings use it.

## Verification and remote catalog

`mvn verify` runs all unit and WireMock integration tests, checks JBang/Maven
dependency and `//SOURCES` alignment, and launches and exits the TUI through
Maven, the executable jar, direct JBang, local catalog, and a local HTTP
catalog with an empty JBang cache.

GitHub catalog behavior should be verified after publishing this branch in an
isolated environment with a fresh JBang home and cache:

```sh
jbang catalog add --name cf-next https://raw.githubusercontent.com/garodriguezlp/cf-explorer/plan/next-version/jbang-catalog.json
jbang cf-explorer@cf-next
```
