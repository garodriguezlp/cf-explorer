package cf.explorer.ui;

import cf.explorer.catalog.CloudFoundryException;
import cf.explorer.keystore.KeystoreService;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import javax.net.ssl.SSLException;

/**
 * Turns failures into short, user-facing explanations without echoing arbitrary exception
 * messages, which may contain secrets, response bodies, or file contents.
 */
public final class ErrorMessages {
  private ErrorMessages() {}

  public static String describe(Throwable error) {
    Throwable cause = unwrap(error);
    for (Throwable current = cause; current != null; current = current.getCause()) {
      if (current instanceof CloudFoundryException
          || current instanceof KeystoreService.SafeKeystoreException) {
        return current.getMessage();
      }
      if (current instanceof SSLException) {
        return "TLS handshake failed. If the foundation uses self-signed certificates, "
            + "run with --skip-ssl-validation.";
      }
      if (current instanceof UnknownHostException) {
        return "Unknown host. Check the Cloud Foundry API and UAA URLs.";
      }
      if (current instanceof ConnectException || current instanceof HttpConnectTimeoutException) {
        return "Could not connect. Check the Cloud Foundry API and UAA URLs and your network.";
      }
      if (current instanceof HttpTimeoutException) {
        return "The request timed out.";
      }
      if (current instanceof AccessDeniedException) {
        return "Permission denied writing to the local cf-explorer directory.";
      }
      if (current instanceof FileSystemException) {
        return "Could not write to the local cf-explorer directory.";
      }
    }
    return "Unexpected error (" + cause.getClass().getSimpleName() + ").";
  }

  private static Throwable unwrap(Throwable error) {
    Throwable current = error;
    while ((current instanceof CompletionException || current instanceof ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }
}
