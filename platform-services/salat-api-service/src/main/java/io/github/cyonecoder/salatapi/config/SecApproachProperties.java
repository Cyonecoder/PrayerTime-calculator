package io.github.cyonecoder.salatapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed configuration for the released sec-approach parameters (spec 26), bound
 * from {@code salat.sec-approach.*}.
 *
 * <p>The resource locations are configurable so a deployment can point at a
 * relocated bundle, but the <strong>expected version and city count are hard
 * invariants</strong> (spec 7): they default to the released values and are
 * cross-checked against the loaded resource, so a wrong parameter set fails
 * startup rather than being served silently.
 *
 * @param parameterResource        classpath location of the offsets resource
 * @param reconstructedResource     classpath location of the frozen global formula
 * @param expectedParameterVersion  the only accepted parameter version
 * @param expectedSupportedCityCount the number of cities that must have offsets
 */
@ConfigurationProperties(prefix = "salat.sec-approach")
public record SecApproachProperties(
        @DefaultValue("classpath:secapproach/morocco-habous-secaproach.json") String parameterResource,
        @DefaultValue("classpath:secapproach/morocco-habous-reconstructed.json") String reconstructedResource,
        @DefaultValue("morocco-habous-secaproach-v2") String expectedParameterVersion,
        @DefaultValue("48") int expectedSupportedCityCount) {

    /** @return the {@code parameterResource} as a plain classpath path (no {@code classpath:} prefix) */
    public String parameterClasspath() {
        return stripClasspath(parameterResource);
    }

    /** @return the {@code reconstructedResource} as a plain classpath path */
    public String reconstructedClasspath() {
        return stripClasspath(reconstructedResource);
    }

    private static String stripClasspath(String location) {
        String path = location.startsWith("classpath:") ? location.substring("classpath:".length()) : location;
        return path.startsWith("/") ? path : "/" + path;
    }
}
