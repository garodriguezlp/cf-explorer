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
      Optional<ApplicationCatalog> cached = cacheStore.readIfPresent();
      if (cached.isPresent()) {
        ApplicationCatalog catalog = cached.get();
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

  private ApplicationCatalog fetchLiveAndUpdateCache(CatalogProgress progress)
      throws IOException, InterruptedException {
    CloudFoundryCatalogClient.CatalogSnapshot snapshot = liveLoader.loadSnapshot(progress);
    ApplicationCatalog catalog = snapshot.toCatalog();
    try {
      cacheStore.write(snapshot);
    } catch (IOException ex) {
      LOG.warning("Catalog cache write failed (" + ex.getClass().getName() + ")");
    }
    return catalog;
  }
}
