package io.github.cyonecoder.salatapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed configuration for the REST API (spec 26), bound from {@code salat.api.*}.
 *
 * @param maximumRangeDays the hard cap on the sec-approach range endpoint (spec 23.11)
 */
@ConfigurationProperties(prefix = "salat.api")
public record ApiProperties(@DefaultValue("366") int maximumRangeDays) {

    public ApiProperties {
        if (maximumRangeDays < 1) {
            throw new IllegalArgumentException("maximumRangeDays must be >= 1 but was " + maximumRangeDays);
        }
    }
}
