package io.github.cyonecoder.salatapi.habous.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.math.BigInteger;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Objects;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;

/**
 * The trust-augmentation material for the live Habous endpoint (TLS fix).
 *
 * <p>These are deterministic and offline: they prove the bundled intermediate is
 * present and valid, that platform trust is retained, and — crucially — that an
 * unrelated certificate is still rejected. Augmenting trust must never become
 * trusting everything.
 */
class HabousTrustStoreTest {

    @Test
    void theBundledCertificateResourceExists() {
        try (InputStream in = getClass().getResourceAsStream(HabousTrustStore.INTERMEDIATE_RESOURCE)) {
            assertThat(in).as("bundled intermediate resource").isNotNull();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theBundledCertificateParsesAsTheExpectedSectigoIntermediate() {
        X509Certificate cert = HabousTrustStore.bundledIntermediate();

        assertThat(cert.getSubjectX500Principal().getName())
                .contains("Sectigo Public Server Authentication CA DV R36");
        assertThat(cert.getIssuerX500Principal().getName())
                .contains("Sectigo Public Server Authentication Root R46");
        // A guard against silent rotation: this is the serial we reviewed and bundled.
        assertThat(cert.getSerialNumber())
                .isEqualTo(new BigInteger("397A66CC2756362E0DAA87CA6EABE3B1", 16));
    }

    @Test
    void theExtraKeyStoreContainsExactlyTheBundledIntermediate() throws Exception {
        var store = HabousTrustStore.intermediateKeyStore();

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.isCertificateEntry(HabousTrustStore.INTERMEDIATE_ALIAS)).isTrue();
        assertThat(store.getCertificate(HabousTrustStore.INTERMEDIATE_ALIAS))
                .isEqualTo(HabousTrustStore.bundledIntermediate());
    }

    @Test
    void platformTrustIsRetainedNotReplaced() {
        X509TrustManager composite = HabousTrustStore.compositeTrustManager();

        // The composite advertises the platform's CAs plus the one extra — so it is
        // strictly additive, never a narrowed or trust-all set.
        int accepted = composite.getAcceptedIssuers().length;
        assertThat(accepted)
                .as("composite accepted issuers = platform CAs + 1")
                .isGreaterThan(10);
    }

    @Test
    void anUnrelatedSelfSignedCertificateIsNotBlindlyAccepted() throws Exception {
        X509TrustManager composite = HabousTrustStore.compositeTrustManager();
        X509Certificate rogue = selfSigned();

        // Neither the platform store nor the bundled intermediate can build a path
        // for a random self-signed cert: it must still be rejected.
        assertThatThrownBy(() -> composite.checkServerTrusted(
                new X509Certificate[] {rogue}, "RSA"))
                .isInstanceOf(CertificateException.class);
    }

    @Test
    void anSslContextCanBeBuilt() {
        assertThat(HabousTrustStore.sslContext()).isNotNull();
        assertThat(HabousTrustStore.sslContext().getProtocol()).isEqualTo("TLS");
    }

    /** A real, unrelated self-signed certificate (bundled as a test fixture). */
    private static X509Certificate selfSigned() throws Exception {
        try (InputStream in = HabousTrustStoreTest.class.getResourceAsStream(
                "/fixtures/tls/unrelated-self-signed.pem")) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(Objects.requireNonNull(in));
        }
    }
}
