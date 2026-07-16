package io.github.cyonecoder.salatapi.config;

import io.github.cyonecoder.salatapi.habous.client.HabousTrustStore;
import java.net.http.HttpClient;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the trust-augmented {@link HttpClient} used to reach the live Habous
 * endpoint, which serves an incomplete certificate chain (see
 * {@link HabousTrustStore} and {@code docs/HABOUS_SOURCE.md}).
 *
 * <p>The client keeps hostname verification, certificate validation, the
 * configured connect timeout and normal redirect handling; it only adds the
 * bundled Sectigo intermediate as an extra chain-building source. It is exposed
 * under the {@code habousHttpClient} qualifier so it can never be confused with
 * any other {@link HttpClient} bean.
 */
@Configuration
public class HabousTlsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(HabousTlsConfiguration.class);

    /**
     * @param properties the Habous connection settings (for the connect timeout)
     * @return an HTTP client trusting the platform defaults plus the bundled intermediate
     */
    @Bean
    @Qualifier("habousHttpClient")
    public HttpClient habousHttpClient(HabousProperties properties) {
        SSLContext sslContext = HabousTrustStore.sslContext();
        var intermediate = HabousTrustStore.bundledIntermediate();
        log.info("Habous HTTP client trust augmented with intermediate '{}' (expires {})",
                intermediate.getSubjectX500Principal().getName(), intermediate.getNotAfter());
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .sslContext(sslContext)
                .build();
    }
}
