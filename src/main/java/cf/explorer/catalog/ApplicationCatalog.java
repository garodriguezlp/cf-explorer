package cf.explorer.catalog;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public record ApplicationCatalog(int organizations, int spaces, List<App> apps) {
  public ApplicationCatalog {
    apps = List.copyOf(apps);
  }

  public record App(String guid, String name, String state, String spaceGuid, String spaceName,
      String orgGuid, String orgName, String createdAt, String updatedAt) {}

  public List<App> filter(String query) {
    if (query.isBlank()) {
      return apps;
    }
    List<String> tokens = Arrays.stream(query.trim().split("\\s+"))
        .map(token -> token.toLowerCase(Locale.ROOT)).toList();
    return apps.stream().filter(app -> tokens.stream().allMatch(token ->
        contains(app.name(), token) || contains(app.state(), token)
            || contains(app.spaceName(), token) || contains(app.orgName(), token)
            || contains(date(app.createdAt()), token) || contains(date(app.updatedAt()), token)))
        .toList();
  }

  private static String date(String value) {
    return value != null && value.length() >= 10 ? value.substring(0, 10) : "";
  }

  private static boolean contains(String value, String token) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(token);
  }
}
