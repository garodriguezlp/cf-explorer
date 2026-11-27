# Phase 4: Feature-by-feature migration

## Approach

Port one end-to-end workflow at a time from `main` into the command-driven
shell. Keep `main` usable, and compare the old implementation and README before
each slice. Preserve behavior by default; record intentional compatibility
changes in the migration guidance.

Each slice should include its domain and I/O logic, shell wiring, focused tests,
and user-facing documentation. Avoid a separate framework-building phase:
extract shared components when a real workflow needs them.

## Shared design and completion rules

- Keep CF domain logic independent of the TUI and infrastructure. Commands use
  the active view's typed selection and are unavailable when their required
  context is absent.
- Run blocking network, filesystem, clipboard, and browser work off the render
  thread; capture inputs before starting and apply UI changes on the render
  thread. Show loading, success, and safe errors as appropriate.
- Define how each operation handles navigation, quit, repeated requests, and
  stale results. Navigating away does not imply cancellation or undo external
  side effects.
- Preserve filter and selection when possible, using app GUIDs rather than
  display names to identify apps across catalog replacements.
- Do not expose credentials, environment values, or keystore contents in
  messages or logs. Surface failures without leaking sensitive details.
- Test behavior and failure paths at the appropriate layer, including WireMock
  integration for CF interactions. Do not modify the mock server or its
  fixtures; use separate test-only stubs for additional scenarios.
- Keep Java 17 and Maven/JBang dependencies and source directives aligned.
  Run `mvn verify` for each completed slice and update launch and usage docs.

## Slices

### 1. Live catalog and typed selection

**Outcome:** Start the shell, authenticate, load the catalog, and browse real
apps without relying on the example string list.

- [x] Port the connection settings needed for authentication and catalog
  loading, including their documented precedence; compare the current
  placeholder configuration with `main`.
- [x] Load all pages of organizations, spaces, and apps; join them into typed
  app records with the fields needed by later commands.
- [x] Display useful catalog metadata and loading, empty, and error states.
  Support the legacy token filter across app, state, org, space, and dates,
  including match highlighting where useful.
- [x] Provide typed active selection and the smallest command/page changes
  needed to support it. Keep navigation and command handling independent of CF
  domain and I/O code.
- [x] Test authentication and pagination with WireMock, joins and filtering,
  loading and error transitions, and selection and navigation behavior.

This slice may load directly from CF; cache, refresh, and profile-scoped
storage follow in slice 2. Document this temporary limitation rather than
claiming full catalog compatibility.

### 2. Profiles, cache, and refresh

**Outcome:** The catalog can be reused or explicitly reloaded without losing
the user's context unnecessarily.

- [x] Port profile validation, profile-specific environment variables and
  local storage paths, and the documented configuration precedence.
- [x] Port catalog caching and `--fresh`, checking compatibility with existing
  cache files and documenting any change.
- [x] Add a discoverable refresh command that fetches fresh data, updates the
  cache, and preserves filter and selected GUID when possible.
- [x] Define and test behavior for failed refreshes, missing or corrupt cache,
  disappearing selections, and overlapping loads. Older completions must not
  overwrite newer catalog state.
- [x] Test profile isolation, cache hits and misses, fresh loads, refresh
  failures, and render-thread updates.

### 3. Environment export

**Outcome:** A command exports the selected app's environment without
displaying its values in the shell.

- [x] Port environment retrieval and `.env` generation, including output
  location, filename, ordering, quoting, exclusions, and post-processors.
- [x] Restore relevant flags, defaults, and clipboard behavior; report a
  clipboard failure separately from a successful file write.
- [x] Show progress and a safe result or failure. Decide and document what
  happens when navigation occurs while the write is in progress.
- [x] Test variable retrieval, formatting and edge cases, profile paths,
  exclusions, post-processing, clipboard outcomes, and write/API failures.

### 4. Open in Apps Manager

**Outcome:** A command opens the selected app in Apps Manager.

- [x] Port the web URL configuration and URL construction using the app,
  space, and organization identifiers.
- [x] Launch the browser without blocking the render thread; report failures
  clearly and offer an appropriate safe fallback.
- [x] Test URL construction, command availability, and launcher failures
  without opening a real browser.

### 5. Keystore inspection

**Outcome:** A command retrieves the selected app's configured keystore and
shows useful certificate details.

- [x] Port keystore variable configuration, decoding, file output, clipboard
  behavior, and certificate inspection. Preserve partial-success reporting
  when a file is saved but inspection fails.
- [x] Show certificate aliases, subjects, issuers, and validity dates in a
  view where useful; do not display or log secret values.
- [x] Resolve and document the legacy filename behavior before porting it:
  the old JKS filename embeds the decoded password. Do not silently carry
  that exposure into the new workflow.
- [x] Test missing variables, invalid encoding or passwords, output and
  clipboard outcomes, certificate details, and safe errors.

### 6. Compatibility and release readiness

- [x] Compare all migrated flags, environment variables, defaults,
  precedence, output formats and paths, profile behavior, exclusions,
  post-processors, and clipboard behavior against `main`. Document deliberate
  differences and any deferred capabilities.
- [ ] Complete a manual terminal walkthrough of loading, filtering, refresh,
  export, Apps Manager, keystore inspection, navigation, and failure states.
- [x] Run `mvn verify` and check Maven, the executable jar, direct JBang, and
  the local catalog. Verify the remote catalog from a clean environment after
  publication, as described in the README.
- [ ] Decide when to switch the published catalog alias and communicate the
  migration; retain the mock-server alias and fixtures.

## Resolved decisions

- **Command names & presentation:** `/export-env` (result view), `/open`
  (status message with URL fallback on failure), `/keystore` (certificate view
  with partial-success warning), `/refresh` (status message keeping catalog
  visible), `/back`, `/quit`.
- **Refresh failure and concurrency policy:** Keep the last successful catalog
  visible while refreshing and on failure; track load generations so stale
  completions never overwrite newer catalog state; preserve on-disk cache on
  failed refreshes.
- **Keystore filename:** Removed the legacy `-pass_<password>` segment; files
  are written as `<app>-<timestamp>.jks` and secrets are never shown or logged.
- **Compatibility changes:** Documented in `README.md` (command-driven shell,
  keystore filename security fix, refresh resiliency, separate clipboard
  reporting, standard TLS verification).
