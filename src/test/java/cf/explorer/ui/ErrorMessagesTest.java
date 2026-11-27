package cf.explorer.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cf.explorer.catalog.CloudFoundryException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.file.AccessDeniedException;
import java.util.concurrent.CompletionException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class ErrorMessagesTest {
  @Test
  void explainsKnownFailuresWithoutEchoingArbitraryMessages() {
    assertEquals("UAA rejected", ErrorMessages.describe(
        new CompletionException(new CloudFoundryException("UAA rejected"))));
    assertTrue(ErrorMessages.describe(new SSLHandshakeException("cert"))
        .contains("--skip-ssl-validation"));
    assertTrue(ErrorMessages.describe(new UnknownHostException("api.secret.example"))
        .startsWith("Unknown host."));
    assertTrue(ErrorMessages.describe(new IOException(new ConnectException("refused")))
        .startsWith("Could not connect."));
    assertTrue(ErrorMessages.describe(new AccessDeniedException("/home/me/private"))
        .startsWith("Permission denied"));
    String unexpected = ErrorMessages.describe(new IllegalStateException("password=hunter2"));
    assertEquals("Unexpected error (IllegalStateException).", unexpected);
    assertFalse(ErrorMessages.describe(new UnknownHostException("api.secret.example"))
        .contains("secret"));
  }
}
