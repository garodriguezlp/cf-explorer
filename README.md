# cf-explorer: next version

This branch starts a new version of cf-explorer. It currently provides a
connection-free shell with example items; Cloud Foundry browsing and the other
original features are not implemented yet. The original application remains
available on `main`.

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

## Shell

Type to filter the example list (case-insensitive substring matching), and use
Up/Down to select an item. Left/Right, Home/End, Backspace, and Delete edit
the current input. Type `/` to enter command mode without changing the filter;
type a command name to narrow the available suggestions. Up/Down selects a
suggestion, Tab completes it, and Enter executes an exact command or the
selected suggestion. An unknown or unavailable command is reported in place.

Use `/mark` to toggle a mark on the selected item, or `/inspect` to open its
detail view. In a detail view, Esc or `/back` returns to the list with its
filter and selection intact. Esc cancels command entry; in the list it clears
the filter when not entering a command. `/quit` is available in both views.
These example commands do not connect to Cloud Foundry.
The layout becomes more compact in shorter terminals, and the selected list
item and command suggestion scroll into view.

The phase 1 placeholder `--message` and `--count` options are no longer used.

## Configuration placeholder

The bundled `.cf-explorer.properties` supplies `cf.api.url` as the default for
`--cf-api-url`, inspired by the original application. Picocli reads
`.cf-explorer.properties` in the user home directory instead of the bundled
file when present; set the Java system property
`picocli.defaults.cf-explorer.path` to use another file. The command-line option
overrides the property; when no explicit property value is provided, the
`CF_API_URL` Java system property or environment variable is used, falling
back to `http://localhost:9090`. The system property takes precedence over the
environment variable. This is a placeholder: the shell does not connect to
the URL or use it yet. The resource is included in both launch paths.

`mvn verify` runs unit tests and starts and exits the TUI through Maven, the
executable jar, the direct JBang script and the local catalog. These automated
launch checks use redirected streams; verify screen rendering in an interactive
terminal too. The build also checks that JBang directives are declared in the
entry point and its dependencies match the runtime dependencies in `pom.xml`.

## Source organization

Maven discovers the production classes in the usual source tree. For JBang,
`CfExplorer.java` lists every other source file in a separate `//SOURCES`
directive, with paths relative to the entry point. Add a directive there when
adding a production source file.

```text
CfExplorer.java
  -> ExplorerScreen.java
  -> model/ShellState.java
  -> ui/ShellView.java
```

Experiments with JBang 0.135.1 and fresh caches:

| Approach | Local files | HTTP |
|---|---|---|
| Implicit discovery, as in the documentation's simple example | Worked when run from the source directory; failed from another directory | Failed |
| Explicit paths in the entry point or nested `//SOURCES` | Worked | Worked, including nested packages |
| `//SOURCES *.java **/*.java` or `//SOURCES **` | Worked for the test source tree | Did not fetch the sources |
| Directory paths in `//SOURCES` | Worked for the tested local directories | Could not download a directory from the test server |

These are results from small examples, not guarantees for every project or
server. In a separate experiment, a `//DEPS` directive in an included source
was resolved locally and over HTTP; the same example failed without it. This
differs from the documentation's statement that only the main file's
configuration is honored. We nevertheless keep dependencies in the entry
point and `pom.xml`, so the alignment test checks all of them. Individual
classes can use those dependencies without declaring them again.

In the local HTTP test, JBang fetches the explicitly listed files, not an
entire remote directory. Actual GitHub catalog behavior is still unverified.
See JBang's
[file organization documentation](https://www.jbang.dev/documentation/jbang/latest/organizing.html)
for details.

## Mock server

The existing WireMock fixtures and local workflow are retained for later
phases:

```sh
./jbang mock-server
```

The mock server listens on port 9090 by default. This minimal app does not
connect to it yet.

## Remote catalog

`mvn verify` also launches the catalog over local HTTP with an empty JBang
cache and no checkout in the working directory, checking that the source files
and placeholder resource are fetched. GitHub catalog behavior still needs
verification after publishing this branch. In an
isolated environment with a fresh JBang home and cache, no checkout, and no
Maven build or compiled classes, run:

```sh
jbang catalog add --name cf-next https://raw.githubusercontent.com/garodriguezlp/cf-explorer/plan/next-version/jbang-catalog.json
jbang cf-explorer@cf-next
```

Confirm that the screen matches the local launches and `/quit` exits. Do not treat GitHub catalog
support as complete until that check succeeds.
