package io.github.cyonecoder.salatapi.habous.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.cyonecoder.salatapi.config.HabousProperties;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which official-site failures may be retried, and how long we wait (spec 14).
 */
class HabousRetryPolicyTest {

    private final HabousProperties properties = HabousProperties.forTests(
            "https://example.test/horaire", Duration.ofMillis(500),
            Duration.ofSeconds(10), Duration.ofSeconds(15), 3, "agent");
    private final HabousRetryPolicy policy = new HabousRetryPolicy(properties);

    @ParameterizedTest
    @ValueSource(ints = {429, 502, 503, 504})
    void temporaryStatusesAreRetried(int status) {
        assertThat(policy.isRetryableStatus(status)).isTrue();
        assertThat(policy.isRetryable(
                new HabousUnavailableException("busy", status))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 410, 500})
    void permanentStatusesAreNotRetried(int status) {
        // 500 is deliberately absent from the retry list: the spec names only
        // 502/503/504 among 5xx, and retrying a genuine server error adds load
        // without changing the answer.
        assertThat(policy.isRetryableStatus(status)).isFalse();
        assertThat(policy.isRetryable(
                new HabousUnavailableException("nope", status))).isFalse();
    }

    @Test
    void timeoutsAreRetried() {
        assertThat(policy.isRetryable(new HabousUnavailableException(
                "timeout", null, new HttpTimeoutException("request timed out")))).isTrue();
    }

    @Test
    void connectionResetIsRetried() {
        assertThat(policy.isRetryable(new HabousUnavailableException(
                "reset", null, new SocketException("Connection reset")))).isTrue();
        assertThat(policy.isRetryable(new HabousUnavailableException(
                "reset", null, new IOException("Connection reset by peer")))).isTrue();
    }

    @Test
    void aRefusedConnectionIsRetriedAsATransientOutage() {
        assertThat(policy.isRetryable(new HabousUnavailableException(
                "refused", null, new ConnectException("Connection refused")))).isTrue();
    }

    @Test
    void aParsingFailureIsNeverRetried() {
        // Re-fetching an unchanged malformed page cannot make it parse. Retrying
        // here would hammer the site indefinitely for nothing.
        assertThat(policy.isRetryable(
                new io.github.cyonecoder.salatapi.habous.parser.HabousParseException("bad table")))
                .isFalse();
    }

    @Test
    void anArbitraryFailureIsNotRetried() {
        assertThat(policy.isRetryable(new IllegalStateException("bug"))).isFalse();
        assertThat(policy.isRetryable(new HabousUnavailableException("no cause"))).isFalse();
    }

    @Test
    void backoffGrowsExponentiallyWithTheAttempt() {
        // Full jitter: the ceiling doubles each attempt. jitterFactor = 1.0 asks
        // for the ceiling itself, making the growth observable.
        assertThat(policy.backoffFor(1, 1.0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.backoffFor(2, 1.0)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.backoffFor(3, 1.0)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.backoffFor(4, 1.0)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void backoffIsCappedSoAWideOutageCannotStallTheSyncForever() {
        assertThat(policy.backoffFor(20, 1.0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.backoffFor(100, 1.0)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void jitterSpreadsRetriesAcrossTheWholeWindow() {
        // Without jitter, a site-wide outage would resynchronize every retry into
        // a burst. Full jitter picks uniformly in [0, ceiling].
        assertThat(policy.backoffFor(3, 0.0)).isEqualTo(Duration.ZERO);
        assertThat(policy.backoffFor(3, 0.5)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.backoffFor(3, 1.0)).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void jitterFactorIsClampedRatherThanTrusted() {
        assertThat(policy.backoffFor(2, -5.0)).isEqualTo(Duration.ZERO);
        assertThat(policy.backoffFor(2, 99.0)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void theRandomBackoffStaysWithinItsCeiling() {
        for (int i = 0; i < 200; i++) {
            assertThat(policy.backoffFor(3))
                    .isBetween(Duration.ZERO, Duration.ofSeconds(4));
        }
    }

    @Test
    void defaultsMatchTheSpecifiedRespectfulLimits() {
        HabousProperties defaults = HabousProperties.forTests(
                "https://www.habous.gov.ma/prieres/horaire_hijri_2.php",
                Duration.ofMillis(500), Duration.ofSeconds(10), Duration.ofSeconds(15), 3, "agent");

        assertThat(defaults.maximumConcurrency()).isEqualTo(1);
        assertThat(defaults.requestDelay()).isEqualTo(Duration.ofMillis(500));
        assertThat(defaults.connectionTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.requestTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(defaults.maximumAttempts()).isEqualTo(3);
    }
}
