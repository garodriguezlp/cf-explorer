package cf.explorer.catalog;

import java.io.IOException;

/**
 * A Cloud Foundry or UAA failure whose message is safe to show to the user: it never contains
 * credentials, tokens, environment values, or raw response bodies.
 */
public final class CloudFoundryException extends IOException {
  public CloudFoundryException(String safeMessage) {
    super(safeMessage);
  }
}
