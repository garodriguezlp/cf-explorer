# cf-explorer: next version

This branch starts a new version of cf-explorer. Phase 1 is a minimal Java
application; Cloud Foundry browsing and the other original features are not
implemented yet. The original application remains available on `main`.

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
open the same minimal Tambo UI using the JLine terminal backend, showing the
configured message and count. Press `q` to quit; pass `--help` for CLI help.
Run the app in a terminal. The executable jar includes its runtime dependencies.

## Configuration

Picocli reads option defaults from `.cf-explorer.properties` in the user home
directory, or from the bundled file if no user file exists. For example:

```properties
the.message=Hello from cf-explorer
count=${CF_EXPLORER_COUNT:-1}
```

The required `--message` option uses `the.message` as its properties key;
picocli also accepts `message` if `the.message` is absent. The option may be
supplied on the command line, overriding the configured default. If a
user-supplied file omits both keys, pass `--message` explicitly.
The `--count` option is an integer printed after the message. Its default uses
`CF_EXPLORER_COUNT` (a Java system
property takes precedence over the environment variable), falling back to 1.
Set `count` in the properties file or pass `--count` to override it.
To use a different file, set
the Java system property `picocli.defaults.cf-explorer.path` to its path. A
user-supplied file replaces the bundled defaults rather than merging with them.

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
  -> model/WelcomeContent.java
  -> ui/WelcomeView.java
  -> ui/components/WelcomeBody.java
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
and resource are fetched. GitHub catalog behavior still needs verification after
publishing this branch. In an
isolated environment with a fresh JBang home and cache, no checkout, and no
Maven build or compiled classes, run:

```sh
jbang catalog add --name cf-next https://raw.githubusercontent.com/garodriguezlp/cf-explorer/plan/next-version/jbang-catalog.json
jbang cf-explorer@cf-next
```

Confirm that the screen matches the local launches and `q` exits. Do not treat GitHub catalog
support as complete until that check succeeds.
