///usr/bin/env jbang "$0" "$@" ; exit $?

//JAVA 17
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.0

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collections;

/**
 * Reads the KEYSTORE value from wiremock/mappings/v3-app-env-vars.json, base64-decodes it,
 * loads it as a JKS keystore, and prints each certificate's alias, subject DN, and expiry.
 *
 * Usage from the repo root:
 *   jbang wiremock/InspectKeystore.java
 */
public class InspectKeystore {

  public static void main(String[] args) throws Exception {
    var mappingFile = Path.of("wiremock/mappings/v3-app-env-vars.json");
    if (!Files.exists(mappingFile)) {
      System.err.println("ERROR: " + mappingFile + " not found. Run from the repo root.");
      System.exit(1);
    }

    // ── 1. Extract the KEYSTORE value from the JSON fixture ──────────────────
    var mapper = new ObjectMapper();
    var root = mapper.readTree(mappingFile.toFile());
    JsonNode keystoreNode = root.at("/response/jsonBody/var/KEYSTORE");
    if (keystoreNode.isMissingNode() || keystoreNode.isNull()) {
      System.err.println("ERROR: KEYSTORE key not found in " + mappingFile);
      System.exit(1);
    }
    var encoded = keystoreNode.asText().strip();
    System.out.println("✓  Found KEYSTORE value (" + encoded.length() + " Base64 chars)");

    // ── 2. Base64 decode ─────────────────────────────────────────────────────
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException ex) {
      System.err.println("ERROR: Base64 decoding failed: " + ex.getMessage());
      System.exit(1);
      return;
    }
    System.out.println("✓  Decoded to " + bytes.length + " bytes");

    // ── 3. Load as JKS ───────────────────────────────────────────────────────
    var password = "changeit".toCharArray();
    KeyStore ks;
    try {
      ks = KeyStore.getInstance("JKS");
      ks.load(new ByteArrayInputStream(bytes), password);
    } catch (Exception ex) {
      System.err.println("ERROR: Failed to load as JKS: " + ex.getMessage());
      System.err.println("       The file was saved but is not a valid JKS or the password is wrong.");
      System.exit(1);
      return;
    }
    System.out.println("✓  Loaded JKS keystore (" + ks.size() + " entries)");

    // ── 4. Print certificate details ─────────────────────────────────────────
    var fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    var aliases = Collections.list(ks.aliases());
    aliases.sort(null);
    int count = 0;
    System.out.println();
    for (var alias : aliases) {
      var cert = ks.getCertificate(alias);
      if (cert instanceof X509Certificate x509) {
        count++;
        var validFrom = x509.getNotBefore().toInstant().atZone(ZoneId.systemDefault()).toLocalDate().format(fmt);
        var expiry    = x509.getNotAfter() .toInstant().atZone(ZoneId.systemDefault()).toLocalDate().format(fmt);
        System.out.println("Alias:       " + alias);
        System.out.println("  Subject:     " + x509.getSubjectX500Principal().getName());
        System.out.println("  Issuer:      " + x509.getIssuerX500Principal().getName());
        System.out.println("  Valid from:  " + validFrom);
        System.out.println("  Valid until: " + expiry);
        System.out.println();
      } else {
        System.out.println("Alias:       " + alias + "  (non-X509 entry)");
        System.out.println();
      }
    }
    System.out.println("Total entries with X509 certificate: " + count);
    System.out.println("✓  All checks passed.");
  }
}
