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
print the same minimal application message and count; pass `--help` for CLI help.
The executable jar includes its runtime dependencies.

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

`mvn verify` runs unit tests and launch checks for Maven, the executable jar,
the direct JBang script and the local catalog. It also checks that the runtime
dependencies declared in the JBang entry point match those in `pom.xml`.

## Mock server

The existing WireMock fixtures and local workflow are retained for later
phases:

```sh
./jbang mock-server
```

The mock server listens on port 9090 by default. This minimal app does not
connect to it yet.

## Remote catalog

The catalog and resource loading have been exercised over local HTTP with an
empty JBang cache and without a checkout in the working directory. GitHub
catalog behavior still needs verification after publishing this branch. In an
isolated environment with a fresh JBang home and cache, no checkout, and no
Maven build or compiled classes, run:

```sh
jbang catalog add --name cf-next https://raw.githubusercontent.com/garodriguezlp/cf-explorer/plan/next-version/jbang-catalog.json
jbang cf-explorer@cf-next
```

Confirm that the output matches the local launches. Do not treat GitHub catalog
support as complete until that check succeeds.
