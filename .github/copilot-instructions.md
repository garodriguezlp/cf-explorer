# Copilot Instructions for cf-explorer

Follow [AGENTS.md](../AGENTS.md). When reviewing changes, focus on:

- JBang and Maven alignment: new sources listed in `//SOURCES`, dependencies identical in
  `//DEPS` and `pom.xml`.
- Render-thread safety: UI state changes only on the render thread; blocking work on the
  worker.
- Secret handling: no secrets, environment values, or raw response bodies in UI text,
  logs, or file names.
- TLS: trust-all must stay opt-in behind `--skip-ssl-validation`.

Skip minor style nits that do not affect readability or maintainability.
