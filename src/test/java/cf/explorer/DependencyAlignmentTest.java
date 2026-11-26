package cf.explorer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class DependencyAlignmentTest {
  @Test
  void jbangDirectivesAreDeclaredOnlyInTheEntryPoint() throws Exception {
    Pattern directives = Pattern.compile(
        "(?m)^\\s*//(?:CDS|COMPILE_OPTIONS|DEPS|DESCRIPTION|DOCS|FILES|GAV|JAVAAGENT"
            + "|JAVAC_OPTIONS|JAVA|JAVA_OPTIONS|MAIN|MANIFEST|MODULE|NATIVE_OPTIONS"
            + "|NOINTEGRATIONS|PREVIEW|REPOS|RUNTIME_OPTIONS|SOURCES|GROOVY|KOTLIN)\\b");
    try (Stream<Path> paths = Files.walk(Path.of("src/main/java"))) {
      for (Path path : paths.filter(p -> p.toString().endsWith(".java"))
          .filter(p -> !p.equals(Path.of("src/main/java/cf/explorer/CfExplorer.java"))).toList()) {
        assertTrue(!directives.matcher(Files.readString(path)).find(),
            "Declare JBang directives in CfExplorer.java, not " + path);
      }
    }
  }

  @Test
  void jbangListsEveryOtherSourceOnce() throws Exception {
    Path sourceRoot = Path.of("src/main/java");
    Path entryPoint = sourceRoot.resolve("cf/explorer/CfExplorer.java");
    Set<Path> declared = new TreeSet<>();
    for (String line : Files.readAllLines(entryPoint)) {
      if (line.startsWith("//SOURCES ")) {
        String path = line.substring("//SOURCES ".length()).trim();
        assertTrue(!path.isEmpty() && !path.matches(".*\\s+.*"),
            "Declare exactly one source per directive: " + line);
        assertTrue(declared.add(entryPoint.getParent().resolve(path).normalize()),
            "Duplicate source: " + path);
      }
    }
    try (Stream<Path> paths = Files.walk(sourceRoot)) {
      Set<Path> sources = new TreeSet<>(paths.filter(p -> p.toString().endsWith(".java"))
          .filter(p -> !p.equals(entryPoint)).toList());
      assertEquals(sources, declared);
    }
  }

  @Test
  void jbangDependenciesMatchMavenRuntimeDependencies() throws Exception {
    String source = Files.readString(Path.of("src/main/java/cf/explorer/CfExplorer.java"));
    Matcher directives = Pattern.compile("(?m)^//DEPS\\s+(\\S+)\\s*$").matcher(source);
    Set<String> jbangDependencies = new TreeSet<>();
    while (directives.find()) {
      jbangDependencies.add(directives.group(1));
    }

    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    Element project = factory.newDocumentBuilder().parse("pom.xml").getDocumentElement();
    NodeList dependencies = ((Element) project.getElementsByTagName("dependencies").item(0))
        .getElementsByTagName("dependency");
    Set<String> mavenDependencies = new TreeSet<>();
    for (int i = 0; i < dependencies.getLength(); i++) {
      Element dependency = (Element) dependencies.item(i);
      NodeList scopes = dependency.getElementsByTagName("scope");
      String scope = scopes.getLength() == 0 ? "compile" : scopes.item(0).getTextContent();
      if (scope.equals("compile") || scope.equals("runtime")) {
        mavenDependencies.add(value(dependency, "groupId") + ":" + value(dependency, "artifactId")
            + ":" + value(dependency, "version"));
      }
    }
    assertEquals(mavenDependencies, jbangDependencies);
  }

  private static String value(Element element, String name) {
    return element.getElementsByTagName(name).item(0).getTextContent().trim();
  }
}
