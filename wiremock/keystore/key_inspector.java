///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.6.3

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.Callable;

@Command(name = "key_inspector", mixinStandardHelpOptions = true, version = "key_inspector 0.1",
        description = "Inspect a JKS keystore from base64-encoded values (as stored in CF env vars / wiremock mappings)")
class key_inspector implements Callable<Integer> {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Option(names = {"-k", "--keystore"}, required = true, description = "Base64-encoded JKS keystore")
    private String keystoreBase64;

    @Option(names = {"-p", "--password"}, required = true, description = "Base64-encoded keystore password")
    private String passwordBase64;

    public static void main(String... args) {
        int exitCode = new CommandLine(new key_inspector()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        byte[] keystoreBytes = Base64.getDecoder().decode(keystoreBase64.strip());
        char[] password = new String(Base64.getDecoder().decode(passwordBase64.strip())).toCharArray();

        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(new ByteArrayInputStream(keystoreBytes), password);

        var aliases = Collections.list(keyStore.aliases());
        aliases.sort(null);
        int count = 0;

        for (String alias : aliases) {
            X509Certificate leaf = getLeafCertificate(keyStore, alias);
            if (leaf == null) { continue; }

            count++;

            System.out.println("Alias: " + alias);
            System.out.println("  Subject:     " + leaf.getSubjectX500Principal().getName());
            System.out.println("  Issuer:      " + leaf.getIssuerX500Principal().getName());
            System.out.println("  Valid from:  " + DATE_FORMAT.format(leaf.getNotBefore().toInstant().atZone(ZoneId.systemDefault())));
            System.out.println("  Valid until: " + DATE_FORMAT.format(leaf.getNotAfter().toInstant().atZone(ZoneId.systemDefault())));
            System.out.println();
        }

        System.out.println("Total entries with X509 certificate: " + count);
        return 0;
    }

    private X509Certificate getLeafCertificate(KeyStore keyStore, String alias) throws Exception {
        Certificate cert;
        if (keyStore.isKeyEntry(alias)) {
            Certificate[] chain = keyStore.getCertificateChain(alias);
            if (chain == null || chain.length == 0) { return null; }
            cert = chain[0];
        } else {
            cert = keyStore.getCertificate(alias);
        }

        if (cert instanceof X509Certificate) {
            return (X509Certificate) cert;
        }

        return null;
    }
}