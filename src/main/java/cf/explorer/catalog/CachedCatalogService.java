package cf.explorer.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Coordinates cached and live catalog loading for a profile. */
public final class CachedCatalogService {
  private static final Logger LOG = LoggerFactory.getLogger(CachedCatalogService.class);

  @FunctionalInterface
  public interface SnapshotLoader {
    CloudFoundryCatalogClient.CatalogSnapshot loadSnapshot()
        throws IOException, InterruptedException;

    /** Loads while reporting progress. Loaders that cannot report progress ignore it. */
    default CloudFoundryCatalogClient.CatalogSnapshot loadSnapshot(CatalogProgress progress)
        throws IOException, InterruptedException {
      return loadSnapshot();
    }
  }

  private final SnapshotLoader liveLoader;
  private final CatalogCacheStore cacheStore;

  public CachedCatalogService(CloudFoundryCatalogClient client, CatalogCacheStore cacheStore) {
    this(new SnapshotLoader() {
      @Override
      public CloudFoundryCatalogClient.CatalogSnapshot loadSnapshot()
          throws IOException, InterruptedException {
        return client.loadSnapshot();
      }

      @Override
      public CloudFoundryCatalogClient.CatalogSnapshot loadSnapshot(CatalogProgress progress)
          throws IOException, InterruptedException {
        return client.loadSnapshot(progress);
      }
    }, cacheStore);
  }

  public CachedCatalogService(SnapshotLoader liveLoader, CatalogCacheStore cacheStore) {
    this.liveLoader = Objects.requireNonNull(liveLoader);
    this.cacheStore = Objects.requireNonNull(cacheStore);
  }

  public ApplicationCatalog load(boolean fresh) throws IOException, InterruptedException {
    return load(fresh, CatalogProgress.NONE);
  }

  /** Loads from the cache unless {@code fresh}; a cache hit reports every resource complete. */
  public ApplicationCatalog load(boolean fresh, CatalogProgress progress)
      throws IOException, InterruptedException {
    Objects.requireNonNull(progress);
    if (!fresh) {
      long startNanos = System.nanoTime();
      Optional<ApplicationCatalog> cached = cacheStore.readIfPresent();
      if (cached.isPresent()) {
        ApplicationCatalog catalog = cached.get();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        LOG.info("Loaded catalog from cache: orgs={} spaces={} apps={} ({} ms)",
            catalog.organizations(), catalog.spaces(), catalog.apps().size(), elapsedMs);
        progress.completed(CatalogProgress.Resource.ORGANIZATIONS, catalog.organizations());
        progress.completed(CatalogProgress.Resource.SPACES, catalog.spaces());
        progress.completed(CatalogProgress.Resource.APPS, catalog.apps().size());
        return catalog;
      }
    }
    return fetchLiveAndUpdateCache(progress);
  }

  public ApplicationCatalog refresh() throws IOException, InterruptedException {
    return refresh(CatalogProgress.NONE);
  }

  public ApplicationCatalog refresh(CatalogProgress progress)
      throws IOException, InterruptedException {
    return fetchLiveAndUpdateCache(Objects.requireNonNull(progress));
  }

  /** Updates a single cached app entry on disk if a cache is present; failures are non-fatal. */
  public void updateCachedApp(JsonNode updatedApp) {
    if (updatedApp == null) {
      return;
    }
    try {
      cacheStore.updateApp(updatedApp);
    } catch (IOException | RuntimeException ex) {
      LOG.warn("Catalog cache app update failed", ex);
    }
  }

  private ApplicationCatalog fetchLiveAndUpdateCache(CatalogProgress progress)
      throws IOException, InterruptedException {
    CloudFoundryCatalogClient.CatalogSnapshot snapshot = liveLoader.loadSnapshot(progress);
    ApplicationCatalog catalog = snapshot.toCatalog();
    try {
      cacheStore.write(snapshot);
    } catch (IOException ex) {
      LOG.warn("Catalog cache write failed", ex);
    }
    return catalog;
  }
}
