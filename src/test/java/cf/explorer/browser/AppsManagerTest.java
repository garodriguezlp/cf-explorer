package cf.explorer.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cf.explorer.catalog.ApplicationCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppsManagerTest {
  @Test
  void buildsAppsManagerUrlAndValidatesIdentifiers() {
    var app = new ApplicationCatalog.App("app-1", "demo", "STARTED", "space-1", "dev",
        "org-1", "team", "2024-01-01", "2024-01-02");
    assertTrue(AppsManagerUrl.canBuild(app));
    assertEquals(
        "https://apps.example.com/organizations/org-1/spaces/space-1/applications/app-1",
        AppsManagerUrl.build("https://apps.example.com///", app));

    var missingOrg = new ApplicationCatalog.App("app-1", "demo", "STARTED", "space-1", "dev",
        "", "unknown", "2024-01-01", "2024-01-02");
    assertFalse(AppsManagerUrl.canBuild(missingOrg));
    assertFalse(AppsManagerUrl.canBuild(null));
    assertThrows(IllegalArgumentException.class,
        () -> AppsManagerUrl.build("https://apps.example.com", missingOrg));
    assertThrows(IllegalArgumentException.class, () -> AppsManagerUrl.build("   ", app));
  }

  @Test
  void selectsOsSpecificBrowserCommand() {
    String url = "https://apps.example.com/organizations/o/spaces/s/applications/a";
    assertEquals(List.of("cmd", "/c", "start", "", url),
        BrowserLauncher.commandForOs("Windows 11", url));
    assertEquals(List.of("open", url), BrowserLauncher.commandForOs("Mac OS X", url));
    assertEquals(List.of("xdg-open", url), BrowserLauncher.commandForOs("Linux", url));
  }
}
