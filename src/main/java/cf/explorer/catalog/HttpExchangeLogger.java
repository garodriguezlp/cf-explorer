package cf.explorer.catalog;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Intercepts outbound HTTP requests and inbound responses for {@link CloudFoundryCatalogClient}.
 *
 * <p>When {@code DEBUG} logging is enabled ({@code --debug}), logs the full request and response
 * including headers, timing, and raw payloads. At default log levels, logs concise failure metadata
 * without request or response bodies.
 */
final class HttpExchangeLogger {
  private static final Logger LOG = LoggerFactory.getLogger(HttpExchangeLogger.class);

  @FunctionalInterface
  interface Transport {
    HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
  }

  private final Transport transport;
  private final AtomicLong sequence = new AtomicLong();

  HttpExchangeLogger(HttpClient http) {
    Objects.requireNonNull(http);
    this.transport = request -> http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  HttpExchangeLogger(Transport transport) {
    this.transport = Objects.requireNonNull(transport);
  }

  HttpResponse<String> send(HttpRequest request, String requestBody, String operation)
      throws IOException, InterruptedException {
    long id = sequence.incrementAndGet();
    boolean debug = LOG.isDebugEnabled();
    if (debug) {
      LOG.debug("--> #{} {} {} [op={}] headers={} body={}",
          id,
          request.method(),
          request.uri(),
          operation,
          request.headers().map(),
          formatBody(requestBody));
    }
    long startNanos = System.nanoTime();
    try {
      HttpResponse<String> response = transport.send(request);
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
      String responseBody = response.body();
      int bytes = responseBody == null ? 0 : responseBody.getBytes(StandardCharsets.UTF_8).length;
      if (debug) {
        LOG.debug("<-- #{} {} ({} ms, {} B) {} {} [op={}] headers={} body={}",
            id,
            response.statusCode(),
            elapsedMs,
            bytes,
            request.method(),
            request.uri(),
            operation,
            response.headers().map(),
            formatBody(responseBody));
      } else if (response.statusCode() >= 400 && response.statusCode() != 401) {
        String vcapId = response.headers().firstValue("x-vcap-request-id").orElse("-");
        LOG.warn("HTTP {} {} -> {} ({} ms, {} B, vcap-request-id={}) [op={}]",
            request.method(),
            request.uri().getPath(),
            response.statusCode(),
            elapsedMs,
            bytes,
            vcapId,
            operation);
      }
      return response;
    } catch (IOException | InterruptedException | RuntimeException ex) {
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
      if (debug) {
        LOG.debug("<-- #{} FAILED ({} ms) {} {} [op={}]",
            id, elapsedMs, request.method(), request.uri(), operation, ex);
      } else {
        LOG.warn("HTTP {} {} failed after {} ms [op={}]: {}",
            request.method(), request.uri().getPath(), elapsedMs, operation,
            ex.getClass().getSimpleName());
      }
      throw ex;
    }
  }

  private static String formatBody(String body) {
    return body == null || body.isEmpty() ? "<empty>" : body;
  }
}
