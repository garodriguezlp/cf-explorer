package cf.explorer.catalog;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/** Coordinates cached and live catalog loading for a profile. */
public final class CachedCatalogService {
  private static final Logger LOG = Logger.getLogger(CachedCatalogService.class.getName());

  @FunctionalInterface
  public interface SnapshotLoader {
    CloudFoundryCatalogClient.CatalogSnapshot loadSnapshot()
        throws IOException, InterruptedException;
  }

  private final SnapshotLoader liveLoader;
  private final CatalogCacheStore cacheStore;

  public CachedCatalogService(CloudFoundryCatalogClient client, CatalogCacheStore cacheStore) {
    this(client::loadSnapshot, cacheStore);
  }

  public CachedCatalogService(SnapshotLoader liveLoader, CatalogCacheStore cacheStore) {
    this.liveLoader = Objects.requireNonNull(liveLoader);
    this.cacheStore = Objects.requireNonNull(cacheStore);
  }

  public ApplicationCatalog load(boolean fresh) throws IOException, InterruptedException {
    if (!fresh) {
      Optional<ApplicationCatalog> cached = cacheStore.readIfPresent();
      if (cached.isPresent()) {
        return cached.get();
      }
    }
    return fetchLiveAndUpdateCache();
  }

  public ApplicationCatalog refresh() throws IOException, InterruptedException {
    return fetchLiveAndUpdateCache();
  }

  private ApplicationCatalog fetchLiveAndUpdateCache() throws IOException, InterruptedException {
    CloudFoundryCatalogClient.CatalogSnapshot snapshot = liveLoader.loadSnapshot();
    ApplicationCatalog catalog = snapshot.toCatalog();
    try {
      cacheStore.write(snapshot);
    } catch (IOException ex) {
      LOG.warning("Catalog cache write failed (" + ex.getClass().getName() + ")");
    }
    return catalog;
  }
}
