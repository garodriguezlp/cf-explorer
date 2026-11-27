package cf.explorer.catalog;

/**
 * Receives progress while a catalog loads. Callbacks arrive on fetch threads, possibly
 * concurrently, so implementations must hand updates to their own thread.
 */
public interface CatalogProgress {
  /** Ignores all progress. */
  CatalogProgress NONE = new CatalogProgress() {};

  /** The catalog resources fetched from Cloud Foundry, in display order. */
  enum Resource {
    ORGANIZATIONS("Organizations"),
    SPACES("Spaces"),
    APPS("Apps");

    private final String label;

    Resource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** UAA issued a token; resource fetches start next. */
  default void authenticated() {}

  /**
   * A page of {@code resource} arrived. {@code total} is the server-reported total, or
   * {@code -1} when unknown.
   */
  default void fetched(Resource resource, int fetched, int total) {}

  /** Every page of {@code resource} arrived; {@code count} items in total. */
  default void completed(Resource resource, int count) {}
}
