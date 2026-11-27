package cf.explorer.keystore;

import cf.explorer.catalog.ApplicationCatalog;
import cf.explorer.export.EnvExportService;
import cf.explorer.export.EnvFileWriter;
import cf.explorer.io.ClipboardSupport;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches an app's configured JKS keystore and password variables, writes the decoded JKS file
 * without embedding secrets in the filename, and inspects X.509 certificate metadata.
 */
public final class KeystoreService {
  private static final Logger LOG = LoggerFactory.getLogger(KeystoreService.class);
  private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  /** Safe domain error whose message never contains secret values. */
  public static final class SafeKeystoreException extends RuntimeException {
    public SafeKeystoreException(String safeMessage) {
      super(safeMessage);
    }
  }

  public record InspectResult(
      String appName,
      Path jksPath,
      List<KeystoreEntry> entries,
      boolean inspectionFailed,
      boolean clipboardRequested,
      boolean clipboardCopied) {
    public InspectResult {
      Objects.requireNonNull(appName);
      Objects.requireNonNull(jksPath);
      entries = List.copyOf(Objects.requireNonNull(entries));
    }

    public boolean inspected() {
      return !inspectionFailed;
    }
  }

  private final EnvExportService.EnvVarFetcher fetcher;
  private final String keystoreVar;
  private final String keystorePasswordVar;
  private final Path jksDir;
  private final boolean copyToClipboard;
  private final ClipboardSupport clipboard;

  public KeystoreService(EnvExportService.EnvVarFetcher fetcher, String keystoreVar,
      String keystorePasswordVar, Path jksDir, boolean copyToClipboard,
      ClipboardSupport clipboard) {
    this.fetcher = Objects.requireNonNull(fetcher);
    this.keystoreVar = Objects.requireNonNull(keystoreVar);
    this.keystorePasswordVar = Objects.requireNonNull(keystorePasswordVar);
    this.jksDir = Objects.requireNonNull(jksDir);
    this.copyToClipboard = copyToClipboard;
    this.clipboard = Objects.requireNonNull(clipboard);
  }

  public InspectResult exportAndInspect(ApplicationCatalog.App app)
      throws IOException, InterruptedException {
    return exportAndInspect(app, LocalDateTime.now());
  }

  public InspectResult exportAndInspect(ApplicationCatalog.App app, LocalDateTime timestamp)
      throws IOException, InterruptedException {
    Objects.requireNonNull(app);
    Objects.requireNonNull(timestamp);

    Map<String, String> vars = fetcher.fetchEnvironmentVariables(app.guid());
    byte[] keystoreBytes = decodeBase64(vars, keystoreVar, app.name());
    byte[] passwordBytes = decodeBase64(vars, keystorePasswordVar, app.name());
    char[] passwordChars = new String(passwordBytes, StandardCharsets.UTF_8).toCharArray();
    Arrays.fill(passwordBytes, (byte) 0);

    try {
      Path jksPath = writeKeystoreFile(app, keystoreBytes, timestamp);
      boolean copied = copyToClipboard && clipboard.copy(jksPath.toAbsolutePath().toString());
      try {
        List<KeystoreEntry> entries = inspectJks(keystoreBytes, passwordChars);
        LOG.info("Exported and inspected keystore for app {} ({}) at {}: entries={}",
            app.name(), app.guid(), jksPath, entries.size());
        return new InspectResult(app.name(), jksPath, entries, false, copyToClipboard, copied);
      } catch (Exception ex) {
        LOG.warn("Keystore inspection failed for app {} ({}) at {}",
            app.name(), app.guid(), jksPath, ex);
        return new InspectResult(app.name(), jksPath, List.of(), true, copyToClipboard, copied);
      }
    } finally {
      Arrays.fill(passwordChars, '\0');
    }
  }

  private Path writeKeystoreFile(ApplicationCatalog.App app, byte[] bytes, LocalDateTime timestamp)
      throws IOException {
    String safeName = EnvFileWriter.sanitizeFilename(app.name());
    Files.createDirectories(jksDir);
    Path path = jksDir.resolve(safeName + "-" + TIMESTAMP.format(timestamp) + ".jks");
    Files.write(path, bytes);
    return path;
  }

  private static byte[] decodeBase64(Map<String, String> vars, String varName, String appName) {
    String encoded = vars.get(varName);
    if (encoded == null || encoded.isBlank()) {
      throw new SafeKeystoreException(
          "Environment variable '" + varName + "' was not found for app '" + appName + "'.");
    }
    try {
      return Base64.getDecoder().decode(encoded.strip());
    } catch (IllegalArgumentException ex) {
      throw new SafeKeystoreException(
          "Environment variable '" + varName + "' is not valid Base64.");
    }
  }

  private static List<KeystoreEntry> inspectJks(byte[] keystoreBytes, char[] password)
      throws Exception {
    KeyStore keyStore = KeyStore.getInstance("JKS");
    try (ByteArrayInputStream input = new ByteArrayInputStream(keystoreBytes)) {
      keyStore.load(input, password);
    }
    List<KeystoreEntry> entries = new ArrayList<>();
    for (String alias : Collections.list(keyStore.aliases())) {
      Certificate certificate = keyStore.getCertificate(alias);
      if (certificate instanceof X509Certificate x509) {
        entries.add(new KeystoreEntry(
            alias,
            x509.getSubjectX500Principal().getName(),
            x509.getIssuerX500Principal().getName(),
            x509.getNotBefore().toInstant(),
            x509.getNotAfter().toInstant()));
      }
    }
    entries.sort(Comparator.comparing(KeystoreEntry::alias));
    return List.copyOf(entries);
  }
}
