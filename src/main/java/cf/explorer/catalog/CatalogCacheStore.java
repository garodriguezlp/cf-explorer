package cf.explorer.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes raw CF catalog JSON files ({@code organizations.json}, {@code spaces.json},
 * {@code apps.json}) in a profile-scoped cache directory, compatible with {@code main}.
 */
public final class CatalogCacheStore {
  private static final Logger LOG = LoggerFactory.getLogger(CatalogCacheStore.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<List<JsonNode>> NODE_LIST = new TypeReference<>() {};

  private final Path cacheDir;

  public CatalogCacheStore(Path cacheDir) {
    this.cacheDir = Objects.requireNonNull(cacheDir);
  }

  public Optional<ApplicationCatalog> readIfPresent() {
    Path orgsPath = organizationsPath();
    Path spacesPath = spacesPath();
    Path appsPath = appsPath();
    if (!Files.exists(orgsPath) || !Files.exists(spacesPath) || !Files.exists(appsPath)) {
      return Optional.empty();
    }
    try {
      List<JsonNode> orgs = JSON.readValue(orgsPath.toFile(), NODE_LIST);
      List<JsonNode> spaces = JSON.readValue(spacesPath.toFile(), NODE_LIST);
      List<JsonNode> apps = JSON.readValue(appsPath.toFile(), NODE_LIST);
      if (orgs == null || spaces == null || apps == null) {
        return Optional.empty();
      }
      return Optional.of(CloudFoundryCatalogClient.join(orgs, spaces, apps));
    } catch (IOException | RuntimeException ex) {
      LOG.warn("Catalog cache read failed in {}", cacheDir, ex);
      return Optional.empty();
    }
  }

  public void write(CloudFoundryCatalogClient.CatalogSnapshot snapshot) throws IOException {
    Objects.requireNonNull(snapshot);
    Files.createDirectories(cacheDir);
    writeAtomically(organizationsPath(), snapshot.organizations());
    writeAtomically(spacesPath(), snapshot.spaces());
    writeAtomically(appsPath(), snapshot.apps());
  }

  /**
   * Patches a single application entry in {@code apps.json} when a full catalog cache exists on
   * disk, preserving relationships and fields omitted by partial responses.
   */
  public synchronized void updateApp(JsonNode updatedApp) throws IOException {
    Objects.requireNonNull(updatedApp);
    Path orgsPath = organizationsPath();
    Path spacesPath = spacesPath();
    Path appsPath = appsPath();
    if (!Files.exists(orgsPath) || !Files.exists(spacesPath) || !Files.exists(appsPath)) {
      return;
    }
    String guid = updatedApp.path("guid").asText("");
    if (guid.isBlank()) {
      return;
    }
    List<JsonNode> apps = JSON.readValue(appsPath.toFile(), NODE_LIST);
    if (apps == null) {
      return;
    }
    List<JsonNode> next = new ArrayList<>(apps.size());
    boolean modified = false;
    for (JsonNode existing : apps) {
      if (guid.equals(existing.path("guid").asText()) && existing.isObject()) {
        ObjectNode merged = ((ObjectNode) existing).deepCopy();
        if (updatedApp.path("state").isTextual()) {
          merged.put("state", updatedApp.get("state").asText());
        }
        if (updatedApp.path("updated_at").isTextual()) {
          merged.put("updated_at", updatedApp.get("updated_at").asText());
        }
        if (updatedApp.path("name").isTextual()) {
          merged.put("name", updatedApp.get("name").asText());
        }
        next.add(merged);
        modified = true;
      } else {
        next.add(existing);
      }
    }
    if (modified) {
      writeAtomically(appsPath, next);
    }
  }

  /** Removes a deleted application from {@code apps.json} when a full catalog cache exists. */
  public synchronized void removeApp(String appGuid) throws IOException {
    Objects.requireNonNull(appGuid);
    Path appsPath = appsPath();
    if (appGuid.isBlank() || !Files.exists(organizationsPath()) || !Files.exists(spacesPath())
        || !Files.exists(appsPath)) {
      return;
    }
    List<JsonNode> apps = JSON.readValue(appsPath.toFile(), NODE_LIST);
    if (apps == null) {
      return;
    }
    List<JsonNode> next = apps.stream()
        .filter(existing -> !appGuid.equals(existing.path("guid").asText()))
        .toList();
    if (next.size() != apps.size()) {
      writeAtomically(appsPath, next);
    }
  }

  public void clear() throws IOException {
    Files.deleteIfExists(organizationsPath());
    Files.deleteIfExists(spacesPath());
    Files.deleteIfExists(appsPath());
  }

  private void writeAtomically(Path target, List<JsonNode> nodes) throws IOException {
    Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
    try {
      JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), nodes);
      try {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException | UnsupportedOperationException ex) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException ex) {
      Files.deleteIfExists(temp);
      throw ex;
    }
  }

  private Path organizationsPath() {
    return cacheDir.resolve("organizations.json");
  }

  private Path spacesPath() {
    return cacheDir.resolve("spaces.json");
  }

  private Path appsPath() {
    return cacheDir.resolve("apps.json");
  }
}
