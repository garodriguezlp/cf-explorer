# cf-explorer: next version

## Goals

- Improve modularity, architecture, digestibility, and extensibility.
- Support Maven and JBang using the same application sources.
- Start with a filterable list and context-aware slash commands with
  autocomplete, inspired by modern Copilot and Claude Code.
- Use keys primarily for navigation and editing, not business operations.
- Make adding a command, with or without a view, low-cost.

## JBang experiment

Local prototype: Java 17, JBang 0.135.1, Maven, and `src/main/java`; the entry
point referenced a sibling class and classes in nested child packages.

| Check | Result |
|---|---|
| Maven offline compile | Passed: `mvn -o -B -q compile` |
| JBang without `//SOURCES` | Failed to find sibling and child-package classes |
| Explicit source paths | Passed for sibling and child-package classes |
| `//SOURCES *.java **/*.java` | Passed locally, including nested packages |
| `//SOURCES **/*.java` alone | Did not include the sibling source in this test |
| Local catalog alias | Passed with the combined wildcard entry point |

**Unverified:** Remote GitHub catalog behavior, including wildcard expansion.
Phase 1 must test it; if globs fail remotely, use a verified alternative such
as explicit source references or a catalog alias to a published artifact.

## Phase 1: Maven project with a JBang skin

**Goal:** One source tree, two supported launch paths; a minimal application is
enough for this phase.

**Requirements**

- Conventional Maven layout with packages, resources, tests, and a `pom.xml`.
- A shared entry point with the JBang source and dependency directives it needs;
  aligned dependency versions and resources across both paths.
- Maven compilation, tests, and an executable distribution with runtime
  dependencies included or resolved; document run commands and Java version.
- A JBang catalog alias for the chosen entry point or artifact. Preserve the
  mock-server alias and workflow.
- Keep the existing app usable on `main` while this branch starts from scratch.

**Acceptance**

- Automated Maven tests and launches through Maven, direct JBang, and the local
  catalog; a check detects dependency-version drift.
- Both launch paths start the same minimal application from a clean checkout.
- Verify the remote catalog from a clean environment without a Maven build or
  pre-existing compiled classes; if needed, verify an alternative.

## Phase 2: A small navigable shell

**Goal:** Prove the interaction model without a Cloud Foundry connection.

**Requirements**

| Area | Behavior |
|---|---|
| List | Selection, editable filter, empty/no-match states; keep selection stable when possible |
| Commands | Typing `/` opens context-aware suggestions with descriptions; typing narrows them |
| Completion | Navigate and accept suggestions or type a command directly, then execute it |
| Errors | Clearly handle no suggestions, unknown commands, and unavailable commands |
| Navigation | An example action and an example detail view; Back restores filter and selection |
| Input | Distinguish filtering from commands; cancelling command entry preserves list context |
| Keys | Navigation, editing, confirmation, cancellation, and Back; no primary business-operation shortcuts |

**Acceptance**

- Filter, select, type `/`, autocomplete and run a command, visit a view, and
  return without losing context.
- Focused tests cover filtering, selection, suggestions, completion, command
  availability, navigation, and errors.

## Phase 3: Modular command and view architecture

**Goal:** A clean, modular architecture that is easy to understand and evolve.

**Guidance**

- Favor cohesive, self-describing components and appropriate, familiar
  patterns over unnecessary abstraction.
- Apply SOLID principles pragmatically: clear responsibilities, loose coupling,
  and extension points for registering commands and adding views and their
  logic without modifying unrelated components.
- Explore feature-oriented organization and boundaries between UI, application
  behavior, and infrastructure; keep domain logic independent of UI and I/O.
- Consider command descriptions, availability, selection context, discovery,
  execution, and navigation restoration.
- Choose packages, contracts, and wiring based on the prototype; avoid a fixed
  blueprint or unnecessary framework.
- Async work shows loading and failures, updates UI state on the render thread,
  and avoids stale results after navigation.

**Acceptance**

- Demonstrate an action and a command that opens a view through the same
  mechanism. Adding either needs feature code, tests, and necessary wiring, not
  changes to unrelated features or the input router.
- Test dispatch, unavailable context, navigation restoration, failures, and
  async UI transitions. Document how to add a command and an optional view.

## Phase 4: Bring back Cloud Foundry capabilities

**Goal:** Restore useful Cloud Foundry workflows incrementally.

**Requirements**

- Catalog: authentication, paginated org/space/app loading, filtering, cache,
  refresh, and profile configuration.
- Commands: environment export, open Apps Manager, and keystore inspection.
  Use views only where useful, such as certificate details.
- Preserve or explicitly document changes to flags, environment variables,
  output locations and formats, exclusions, post-processors, and clipboard
  behavior. Avoid exposing secrets and keystore contents in errors or logs.

**Acceptance**

- Agreed existing workflows work through the command-driven experience.
- Test migrated behavior and errors, use WireMock for integration checks, and
  retain a manual TUI walkthrough.
- Check both launch paths and the catalog; update usage and migration guidance.

## Decisions to revisit

- Command arguments, completion behavior, and how `/` interacts with filtering.
- Package boundaries, command and view contracts, and registration or wiring.
- Whether a command result needs its own view or just a status message.
- When to switch the existing catalog alias to the new version and how to
  communicate compatibility changes.
- Whether external plugins are ever needed; built-in registration is enough
  until there is a concrete extension requirement.
