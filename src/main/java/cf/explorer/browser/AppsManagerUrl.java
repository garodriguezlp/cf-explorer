package cf.explorer.browser;

import cf.explorer.catalog.ApplicationCatalog;
import java.util.Objects;

/** Builds Cloud Foundry Apps Manager URLs from web base URL and app identifiers. */
public final class AppsManagerUrl {
  private AppsManagerUrl() {}

  public static boolean canBuild(ApplicationCatalog.App app) {
    return app != null && isNonBlank(app.orgGuid()) && isNonBlank(app.spaceGuid())
        && isNonBlank(app.guid());
  }

  public static String build(String cfWebUrl, ApplicationCatalog.App app) {
    Objects.requireNonNull(cfWebUrl);
    Objects.requireNonNull(app);
    String base = cfWebUrl.trim().replaceAll("/+$", "");
    if (base.isEmpty()) {
      throw new IllegalArgumentException("Apps Manager base URL must not be blank");
    }
    if (!canBuild(app)) {
      throw new IllegalArgumentException("App is missing organization, space, or app GUID");
    }
    return base + "/organizations/" + app.orgGuid() + "/spaces/" + app.spaceGuid()
        + "/applications/" + app.guid();
  }

  private static boolean isNonBlank(String value) {
    return value != null && !value.isBlank();
  }
}
