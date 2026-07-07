package io.github.cyonecoder.prayertime.audit.dataset;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Builds an {@link HttpClient} that can reach the live Habous endpoint.
 *
 * <p>The Habous server presents an <em>incomplete</em> certificate chain (the
 * leaf only, omitting its Sectigo intermediate), so the JDK's default trust
 * store cannot build a path and the handshake fails with {@code PKIX path
 * building failed}. This helper supplies the missing, publicly-issued Sectigo
 * intermediate — bundled at {@code /research/truststore/} — as an additional
 * trust anchor <strong>on top of</strong> the platform defaults. Certificate
 * validation is never disabled: an unrelated or forged certificate still fails.
 */
public final class HabousResearchTls {

    private static final String INTERMEDIATE_RESOURCE =
            "/research/truststore/sectigo-public-server-auth-ca-dv-r36.pem";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private HabousResearchTls() {
    }

    /** @return an HTTP client trusting the platform defaults plus the bundled intermediate. */
    public static HttpClient trustingHttpClient() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {compositeTrustManager()}, null);
            return HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .sslContext(context)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build trust-augmented HTTP client", e);
        }
    }

    private static X509TrustManager compositeTrustManager() throws Exception {
        X509TrustManager platform = defaultTrustManager(null);
        X509TrustManager extra = defaultTrustManager(bundledIntermediateStore());
        return new CompositeX509TrustManager(platform, extra);
    }

    private static X509TrustManager defaultTrustManager(KeyStore store) throws Exception {
        TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store); // null store => platform default CAs
        for (TrustManager tm : factory.getTrustManagers()) {
            if (tm instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new IllegalStateException("No X509TrustManager available");
    }

    private static KeyStore bundledIntermediateStore() throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        try (InputStream in = HabousResearchTls.class.getResourceAsStream(INTERMEDIATE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Bundled intermediate not found: " + INTERMEDIATE_RESOURCE);
            }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(in);
            store.setCertificateEntry("sectigo-public-server-auth-ca-dv-r36", cert);
        }
        return store;
    }

    /**
     * Trusts a certificate if <em>either</em> the platform trust manager or the
     * bundled-intermediate trust manager accepts it. Client-side checks delegate
     * to the platform manager.
     */
    private static final class CompositeX509TrustManager implements X509TrustManager {
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
