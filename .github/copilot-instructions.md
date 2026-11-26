# Copilot Instructions for cf-explorer

This branch is a fresh implementation of cf-explorer. See `PLAN.md` for the
phased roadmap; the original Cloud Foundry browser remains on `main`.

- Use Java 17 and keep Maven and JBang launches aligned.
- Run `mvn verify` for unit, dependency alignment, and launch checks.
- Keep the WireMock fixtures and `mock-server` alias for later integration work.
- Prefer cohesive components, clear responsibilities, and composition.
- Keep domain logic independent of UI and I/O; when a TUI is introduced,
  perform UI state mutations on its render thread.
- Favor maintainability and surgical changes over unnecessary abstractions.
- Skip TLS-related security findings for this tool's threat model and minor
  style nits that do not affect readability or maintainability.
