package cf.explorer;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Core domain types for the cf-explorer application.
 *
 * <p>Contains the primary domain record ({@link App}), the filter utility ({@link TokenFilter}),
 * and the catalog joiner ({@link CatalogJoiner}) that assembles raw CF API data into domain
 * objects. None of these types carry any I/O or framework dependencies.
 */

/**
 * Fully-joined Cloud Foundry application record, assembled from org, space, and app API responses.
 */
record App(
    String guid,
    String name,
    String state,
    String spaceGuid,
    String spaceName,
    String orgGuid,
    String orgName,
    String createdAt,
    String updatedAt) {}

/**
 * Joins raw CF organizations, spaces, and apps into {@link App} domain records.
 *
 * <p>All inputs are assumed to come from a single consistent {@link CatalogSnapshot}.
 */
final class CatalogJoiner {

  /**
   * Produces one {@link App} per raw app by resolving its space and org names. Apps whose space or
   * org cannot be resolved are annotated with {@code "unknown"}.
   */
  List<App> join(List<Organization> orgs, List<Space> spaces, List<AppResource> rawApps) {
    var orgNameByGuid = new java.util.HashMap<String, String>();
    for (var org : orgs) orgNameByGuid.put(org.guid(), org.name());

    var spaceByGuid = new java.util.HashMap<String, Space>();
    for (var space : spaces) spaceByGuid.put(space.guid(), space);

    return rawApps.stream()
        .map(
            app -> {
              var spaceGuid = app.relationships().space().data().guid();
              var space = spaceByGuid.get(spaceGuid);
              var spaceName = space != null ? space.name() : "unknown";
              var orgGuid = space != null ? space.relationships().organization().data().guid() : "";
              var orgName =
                  space != null ? orgNameByGuid.getOrDefault(orgGuid, "unknown") : "unknown";
              return new App(
                  app.guid(),
                  app.name(),
                  app.state(),
                  spaceGuid,
                  spaceName,
                  orgGuid,
                  orgName,
                  app.createdAt(),
                  app.updatedAt());
            })
        .toList();
  }
}

/**
 * Filters a list of {@link App} records using a whitespace-delimited query string.
 *
 * <p>Each token must match at least one of an app's filterable fields (name, state, space, org,
 * created/updated date). All tokens must match the same record (AND semantics across tokens, OR
 * semantics across fields).
 */
final class TokenFilter {

  private TokenFilter() {
    throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
  }

  static List<App> apply(List<App> apps, String query) {
    if (query == null || query.isBlank()) {
      return apps;
    }
    var tokens = tokenize(query);
    return apps.stream().filter(app -> allTokensMatch(app, tokens)).toList();
  }

  static List<String> tokenize(String query) {
    if (query == null || query.isBlank()) return List.of();
    return Arrays.stream(query.trim().split("\\s+")).map(t -> t.toLowerCase(Locale.ROOT)).toList();
  }

  private static boolean allTokensMatch(App app, List<String> tokens) {
    return tokens.stream().allMatch(token -> matchesAnyField(app, token));
  }

  private static boolean matchesAnyField(App app, String token) {
    return containsIgnoreCase(app.name(), token)
        || containsIgnoreCase(app.state(), token)
        || containsIgnoreCase(app.spaceName(), token)
        || containsIgnoreCase(app.orgName(), token)
        || containsIgnoreCase(dateOnly(app.updatedAt()), token)
        || containsIgnoreCase(dateOnly(app.createdAt()), token);
  }

  private static String dateOnly(String iso) {
    return iso != null && iso.length() >= 10 ? iso.substring(0, 10) : "";
  }

  private static boolean containsIgnoreCase(String field, String token) {
    return field != null && field.toLowerCase(Locale.ROOT).contains(token);
  }
}
