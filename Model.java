package cf.explorer;

import java.util.List;

/** Immutable snapshot of org/space/app totals displayed in the title bar on every screen. */
sealed interface AppState
    permits AppState.CatalogLoading,
        AppState.CatalogLoadFailed,
        AppState.Browsing,
        AppState.EnvExporting,
        AppState.ExportDone,
        AppState.ExportFailed {

  /** Immutable snapshot of org/space/app totals displayed in the title bar on every screen. */
  record HeaderCounts(int orgs, int spaces, int apps) {}

  /**
   * The application is fetching orgs, spaces, and apps from the CF API. Tracks per-resource load
   * completion and the running app count for the progress indicator.
   */
  record CatalogLoading(
      boolean orgsLoaded,
      boolean spacesLoaded,
      boolean appsLoaded,
      int orgsCount,
      int spacesCount,
      int loadingAppsCount)
      implements AppState {
    /** Returns the starting state with nothing loaded yet. */
    static CatalogLoading initial() {
      return new CatalogLoading(false, false, false, 0, 0, 0);
    }

    int appsCount() {
      return loadingAppsCount;
    }

    /**
     * Returns a value in {@code [0.0, 1.0]} reflecting how many of the three resource types (orgs,
     * spaces, apps) have finished loading.
     */
    double progress() {
      int done = (orgsLoaded ? 1 : 0) + (spacesLoaded ? 1 : 0) + (appsLoaded ? 1 : 0);
      return done / 3.0;
    }

    CatalogLoading withOrgsLoaded(int count) {
      return new CatalogLoading(
          true, spacesLoaded, appsLoaded, count, spacesCount, loadingAppsCount);
    }

    CatalogLoading withSpacesLoaded(int count) {
      return new CatalogLoading(orgsLoaded, true, appsLoaded, orgsCount, count, loadingAppsCount);
    }

    CatalogLoading withAppsLoaded(int count) {
      return new CatalogLoading(orgsLoaded, spacesLoaded, true, orgsCount, spacesCount, count);
    }

    /** Transitions to {@link Browsing} once all resources have loaded successfully. */
    AppState.Browsing toBrowsing(List<App> apps) {
      return AppState.Browsing.of(
          apps, new AppState.HeaderCounts(orgsCount, spacesCount, apps.size()));
    }

    /** Transitions to {@link CatalogLoadFailed} when a CF API call fails during loading. */
    AppState.CatalogLoadFailed toLoadFailed(String errorMessage) {
      return new AppState.CatalogLoadFailed(
          new AppState.HeaderCounts(orgsCount, spacesCount, appsCount()), errorMessage);
    }
  }

  /**
   * A CF API call failed during the initial catalog load. The user can only quit from this state.
   */
  record CatalogLoadFailed(HeaderCounts header, String errorMessage) implements AppState {}

  /**
   * The catalog is fully loaded and the user is browsing the app list. Supports live token-based
   * filtering and cursor navigation.
   */
  record Browsing(
      HeaderCounts header,
      List<App> allApps,
      List<App> filteredApps,
      int selectedIndex,
      String filterQuery,
      List<String> filterTokens)
      implements AppState {
    static Browsing of(List<App> apps, HeaderCounts header) {
      return new Browsing(header, apps, apps, 0, "", List.of());
    }

    Browsing moveUp() {
      return withIndex(Math.max(0, selectedIndex - 1));
    }

    Browsing moveDown() {
      return withIndex(Math.min(filteredApps.size() - 1, selectedIndex + 1));
    }

    Browsing appendFilter(char c) {
      return withFilter(filterQuery + c);
    }

    Browsing backspaceFilter() {
      if (filterQuery.isEmpty()) return this;
      return withFilter(filterQuery.substring(0, filterQuery.length() - 1));
    }

    Browsing clearFilter() {
      return new Browsing(
          header, allApps, allApps, clamp(selectedIndex, allApps.size()), "", List.of());
    }

    /** Returns the currently highlighted app, or {@code null} when the filtered list is empty. */
    App selectedApp() {
      return filteredApps.isEmpty() ? null : filteredApps.get(selectedIndex);
    }

    boolean hasFilter() {
      return !filterQuery.isEmpty();
    }

    /**
     * Returns one pre-formatted display string per filtered app, suitable for rendering directly in
     * a list widget (name, state, last-updated date, org › space).
     */
    List<String> displayLines() {
      return filteredApps.stream()
          .map(
              app ->
                  String.format(
                      "%-55s  %-8s  %-10s  %s",
                      truncate(app.name(), 55),
                      app.state(),
                      app.updatedAt() != null ? app.updatedAt().substring(0, 10) : "\u2014",
                      app.orgName() + " \u203a " + app.spaceName()))
          .toList();
    }

    private Browsing withIndex(int i) {
      return new Browsing(header, allApps, filteredApps, i, filterQuery, filterTokens);
    }

    private Browsing withFilter(String q) {
      var tokens = TokenFilter.tokenize(q);
      var filtered = TokenFilter.apply(allApps, q);
      return new Browsing(
          header, allApps, filtered, clamp(selectedIndex, filtered.size()), q, tokens);
    }

    private static int clamp(int i, int size) {
      return size == 0 ? 0 : Math.max(0, Math.min(i, size - 1));
    }

    private static String truncate(String s, int max) {
      return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
    }

    /** Transitions to {@link EnvExporting} while the env-var export runs for the given app. */
    AppState.EnvExporting toExporting(String appName) {
      return new AppState.EnvExporting(this, appName);
    }
  }

  /**
   * An env-var export is in progress for {@code appName}. Retains the preceding {@link Browsing}
   * state so that back-navigation restores it exactly.
   */
  record EnvExporting(Browsing previous, String appName) implements AppState {
    HeaderCounts header() {
      return previous.header();
    }

    /** Transitions to {@link ExportDone} after a successful export. */
    AppState.ExportDone toDone(
        String filePath, List<String> excludedKeys, List<String> postProcessedKeys) {
      return new AppState.ExportDone(previous, appName, filePath, excludedKeys, postProcessedKeys);
    }

    /** Transitions to {@link ExportFailed} when the export operation throws. */
    AppState.ExportFailed toFailed(String errorMessage) {
      return new AppState.ExportFailed(previous, errorMessage);
    }
  }

  /**
   * The env-var export completed successfully. Carries the output file path and the lists of keys
   * that were excluded or post-processed.
   */
  record ExportDone(
      Browsing previous,
      String appName,
      String filePath,
      List<String> excludedKeys,
      List<String> postProcessedKeys)
      implements AppState {
    HeaderCounts header() {
      return previous.header();
    }

    Browsing back() {
      return previous;
    }
  }

  /**
   * The env-var export failed. Retains the preceding {@link Browsing} state so the user can ESC
   * back to the app list.
   */
  record ExportFailed(Browsing previous, String errorMessage) implements AppState {
    HeaderCounts header() {
      return previous.header();
    }

    Browsing back() {
      return previous;
    }
  }
}
