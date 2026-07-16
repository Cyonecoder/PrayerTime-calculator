package io.github.cyonecoder.salatapi.habous.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.cyonecoder.salatapi.config.HabousProperties;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.time.Duration;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

/**
 * The failure diagnostics of the Habous client (TLS fix): the real cause must be
 * named, and failures must be classified so a whole-run summary can group them.
 */
class HabousClientDiagnosticsTest {

    private static final HabousProperties PROPS = HabousProperties.forTests(
            "https://habous.test/horaire", Duration.ZERO,
            Duration.ofSeconds(5), Duration.ofSeconds(5), 1, "agent");

    /** An HttpClient stub that fails every send with the supplied cause. */
    private static HabousClient clientThatFailsWith(IOException cause) {
        HttpClient failing = new HttpClient() {
            @Override
            public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> handler) throws IOException {
                throw cause;
            }
            // Unused abstract methods.
            @Override public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<Duration> connectTimeout() { return java.util.Optional.empty(); }
            @Override public Redirect followRedirects() { return Redirect.NORMAL; }
            @Override public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
            @Override public javax.net.ssl.SSLContext sslContext() { return null; }
            @Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
            @Override public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
            @Override public Version version() { return Version.HTTP_1_1; }
            @Override public java.util.Optional<java.util.concurrent.Executor> executor() { return java.util.Optional.empty(); }
            @Override public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                    HttpRequest r, BodyHandler<T> h) { throw new UnsupportedOperationException(); }
            @Override public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                    HttpRequest r, BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) { throw new UnsupportedOperationException(); }
        };
        return new HabousClient(failing, new HabousRequestFactory(PROPS),
                new HabousRetryPolicy(PROPS), d -> { }, PROPS);
    }

    @Test
    void aTlsHandshakeFailureNamesTheRealCauseAndCityAndHost() {
        HabousClient client = clientThatFailsWith(
                new SSLHandshakeException("PKIX path building failed: unable to find valid certification path"));

        assertThatThrownBy(() -> client.fetchCurrentPublishedTimetable("1"))
                .isInstanceOfSatisfying(HabousUnavailableException.class, e -> {
                    assertThat(e.getMessage())
                            .contains("city 1")
                            .contains("habous.test")
                            .contains("SSLHandshakeException")
                            .contains("PKIX path building failed");
                    assertThat(e.category()).isEqualTo(NetworkFailureCategory.TLS_HANDSHAKE_FAILED);
                });
    }

    @Test
    void networkCausesAreClassified() {
        assertThat(classifyCauseOf(new SSLHandshakeException("x")))
                .isEqualTo(NetworkFailureCategory.TLS_HANDSHAKE_FAILED);
        assertThat(classifyCauseOf(new UnknownHostException("habous.test")))
                .isEqualTo(NetworkFailureCategory.DNS_FAILED);
        assertThat(classifyCauseOf(new ConnectException("Connection refused")))
                .isEqualTo(NetworkFailureCategory.CONNECT_TIMEOUT);
        assertThat(classifyCauseOf(new IOException("boom")))
                .isEqualTo(NetworkFailureCategory.UNKNOWN_NETWORK_ERROR);
    }

    @Test
    void httpStatusesAreClassifiedFromTheStatusCode() {
        assertThat(NetworkFailureCategory.classify(403, null)).isEqualTo(NetworkFailureCategory.HTTP_403);
        assertThat(NetworkFailureCategory.classify(429, null)).isEqualTo(NetworkFailureCategory.HTTP_429);
        assertThat(NetworkFailureCategory.classify(503, null)).isEqualTo(NetworkFailureCategory.HTTP_5XX);
    }

    @Test
    void theExceptionExposesItsRootCause() {
        IOException root = new SSLHandshakeException("PKIX");
        HabousUnavailableException wrapped = new HabousUnavailableException("wrap", null, root);
        assertThat(wrapped.rootCause()).isSameAs(root);
    }

    private static NetworkFailureCategory classifyCauseOf(IOException cause) {
        HabousClient client = clientThatFailsWith(cause);
        try {
            client.fetchCurrentPublishedTimetable("1");
            throw new AssertionError("expected failure");
        } catch (HabousUnavailableException e) {
            return e.category();
        }
    }
}
