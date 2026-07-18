package io.github.cyonecoder.salatapi.habous.client;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Builds trust material for reaching the live Habous endpoint.
 *
 * <p>The Habous server presents an <strong>incomplete</strong> certificate chain
 * (its leaf only, omitting the Sectigo intermediate that issued it), so the JDK's
 * default trust store cannot build a path and the handshake fails with
 * {@code PKIX path building failed}. This class supplies the missing,
 * publicly-issued Sectigo intermediate — bundled at {@code /truststore/} — as an
 * additional trust anchor <strong>on top of</strong> the platform defaults.
 *
 * <p><strong>Trust is augmented, never disabled.</strong> The composite trust
 * manager still delegates to the platform manager first and only consults the
 * bundled intermediate as a fallback; an unrelated, forged or expired certificate
 * still fails, hostname verification still applies, and no trust-all manager is
 * ever installed. See {@code docs/HABOUS_SOURCE.md}. Ported from the research
 * branch's {@code HabousResearchTls}; see {@code docs/EXTRACTION_MAP.md}.
 */
public final class HabousTrustStore {

    /** The bundled Sectigo intermediate, inside this module's resources. */
    public static final String INTERMEDIATE_RESOURCE =
            "/truststore/sectigo-public-server-auth-ca-dv-r36.pem";
    /** The alias the intermediate is stored under. */
    public static final String INTERMEDIATE_ALIAS = "sectigo-public-server-auth-ca-dv-r36";

    private HabousTrustStore() {
    }

    /** @return the bundled Sectigo intermediate, parsed as X.509 */
    public static X509Certificate bundledIntermediate() {
        try (InputStream in = HabousTrustStore.class.getResourceAsStream(INTERMEDIATE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Bundled intermediate not found: " + INTERMEDIATE_RESOURCE);
            }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(in);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load bundled Habous intermediate", e);
        }
    }

    /** @return a keystore containing only the bundled intermediate */
    public static KeyStore intermediateKeyStore() {
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            store.setCertificateEntry(INTERMEDIATE_ALIAS, bundledIntermediate());
            return store;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build Habous intermediate keystore", e);
        }
    }

    /**
     * @return a trust manager that accepts a chain if <em>either</em> the platform
     *         defaults or the bundled intermediate can build a valid path
     */
    public static X509TrustManager compositeTrustManager() {
        try {
            X509TrustManager platform = trustManager(null); // null store => platform default CAs
            X509TrustManager extra = trustManager(intermediateKeyStore());
            return new CompositeX509TrustManager(platform, extra);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build composite Habous trust manager", e);
        }
    }

    /** @return an {@link SSLContext} using {@link #compositeTrustManager()} */
    public static SSLContext sslContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {compositeTrustManager()}, null);
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build Habous SSLContext", e);
        }
    }

    private static X509TrustManager trustManager(KeyStore store) throws Exception {
        TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager tm : factory.getTrustManagers()) {
            if (tm instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new IllegalStateException("No X509TrustManager available");
    }

    /**
     * Trusts a server certificate when <em>either</em> the platform trust manager
     * or the bundled-intermediate trust manager accepts it. The platform manager is
     * tried first, so nothing the JVM already trusts changes; the extra manager only
     * rescues the specific case of the missing Sectigo intermediate. Client-side
     * checks always delegate to the platform manager.
     */
    static final class CompositeX509TrustManager implements X509TrustManager {
        private final X509TrustManager platform;
        private final X509TrustManager extra;

        CompositeX509TrustManager(X509TrustManager platform, X509TrustManager extra) {
            this.platform = platform;
            this.extra = extra;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            try {
                platform.checkServerTrusted(chain, authType);
            } catch (CertificateException primary) {
                // Only the bundled intermediate may rescue the chain; a genuinely
                // untrusted, forged or expired certificate still throws here.
                extra.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            platform.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<>();
            for (X509Certificate c : platform.getAcceptedIssuers()) {
                all.add(c);
            }
            for (X509Certificate c : extra.getAcceptedIssuers()) {
                all.add(c);
            }
            return all.toArray(new X509Certificate[0]);
        }
    }
}
