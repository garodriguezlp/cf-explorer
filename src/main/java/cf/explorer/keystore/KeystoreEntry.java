package cf.explorer.keystore;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** A single X.509 certificate entry extracted from a JKS keystore. */
public record KeystoreEntry(
    String alias,
    String subject,
    String issuer,
    Instant notBefore,
    Instant notAfter) {

  private static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  public KeystoreEntry {
    Objects.requireNonNull(alias);
    Objects.requireNonNull(subject);
    Objects.requireNonNull(issuer);
    Objects.requireNonNull(notBefore);
    Objects.requireNonNull(notAfter);
  }

  public String formattedNotBefore() {
    return format(notBefore);
  }

  public String formattedNotAfter() {
    return format(notAfter);
  }

  private static String format(Instant instant) {
    return instant.atZone(ZoneId.systemDefault()).toLocalDateTime().format(DATE_FORMAT);
  }
}
