# AGENTS.md

Guidance for contributors and coding agents working on cf-explorer.

## Build and test

- Java 17. Maven 3.9+ for builds; the bundled `./jbang` wrapper for scripts.
- `mvn verify` is the gate. It runs unit and WireMock tests, `DependencyAlignmentTest`,
  and `LaunchIT`, which starts the TUI through the jar, `exec:java`, JBang, the local
  catalog alias, and an HTTP-served catalog with an empty JBang cache.
- `mvn test` skips `LaunchIT` for a faster loop.
- Manual check: `./jbang mock-server` in one terminal, `./jbang cf-explorer` in another.

## JBang is the product

Users run cf-explorer through JBang (`jbang cf-explorer@garodriguezlp/cf-explorer`).
Maven is the development build. Keep both working from the same sources:

- All JBang directives live only in `src/main/java/cf/explorer/CfExplorer.java`.
- Adding a production source file: add a `//SOURCES <path relative to cf/explorer/>` line
  to `CfExplorer.java`, kept alphabetical.
- Adding or upgrading a dependency: change both `pom.xml` (compile scope) and the
  matching `//DEPS` line, with the same version.
- Do not add resources under `src/main/resources`; JBang does not see them unless
  declared with `//FILES`.
- `DependencyAlignmentTest` fails the build when any of these drift.

## Architecture rules

- `model` is UI-independent: `ShellState` owns command input, page navigation, and status;
  each `ShellPage` owns its own selection and key handling.
- `ShellState` and every page are mutated only on the render thread. Blocking work
  (HTTP, files, clipboard, browser) goes through the worker executor, and results are
  applied through the render dispatcher. Use `ShellState.openAsyncPage` for page-producing
  work; stale results must be dropped when the user has navigated away or quit.
- Domain packages (`catalog`, `env`, `export`, `keystore`, `lifecycle`, `browser`, `config`) must not depend on
  `ui` or `model`.
- Commands are registered in `ExplorerCommands`; availability predicates decide when they
  appear in suggestions. Command names are single lowercase words, and top-level/frequent
  commands declare a unique, mnemonic `Ctrl+<letter>` shortcut. `ShellCommand.RESERVED_SHORTCUTS`
  lists the letters terminals or OSes reserve or alias (Ctrl+A/C/D/F/H/I/J/M/Q/S/V/W/Z), which
  commands cannot use.
- Every wait shows feedback. Pages expose running work through `ShellPage.activity()`,
  async pages use `ShellState.openAsyncPage`, and short background actions use
  `ShellState.showProgress`. `ui.LoadingView` derives animation frames from elapsed time;
  never sleep or block on the render thread to pace an animation.

## Security and errors

- Never log secrets, passwords, tokens, environment values, or raw HTTP bodies, and never
  expose them in `/export` or `/keystore` summaries. The interactive `/env` editor displays
  keys and values on screen so users can inspect and edit them directly.
  User-facing failure text comes from `ui.ErrorMessages.describe`, `CloudFoundryException`,
  or `KeystoreService.SafeKeystoreException`, never from an arbitrary exception message.
- Logs go to `~/.cf-explorer[/<env>]/cf-explorer.log` via `io.LogSetup`; nothing may write
  to stdout or stderr while the TUI is running.
- TLS verification is on by default. Trust-all is only allowed behind
  `--skip-ssl-validation`.
- Exported file names must not embed secrets.

## Conventions

- Prefer small, cohesive classes and composition over new abstractions.
- Tests use JUnit 5 and WireMock. Do not modify `wiremock/mappings` or
  `wiremock/keystore`; build per-test stubs instead.
- Keep `README.md` in sync with CLI options, commands, and file locations.
