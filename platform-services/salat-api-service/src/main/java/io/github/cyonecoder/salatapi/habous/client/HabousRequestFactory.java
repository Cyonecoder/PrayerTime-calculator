package io.github.cyonecoder.salatapi.habous.client;

import io.github.cyonecoder.salatapi.config.HabousProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Builds the request URIs for the official Habous source. Pure; performs no I/O.
 *
 * <p><strong>The official page takes exactly one parameter: {@code ville}.</strong>
 * Inspection of the live form (captured in
 * {@code fixtures/habous/horaire-rabat-live-2026-muharram.html}) shows a single
 * {@code <form>} carrying one {@code <select name="ville">} that navigates via
 * {@code location.href}, and no {@code <input>} elements at all. There is no
 * month, year or Hijri parameter — the only other query parameters on the page
 * belong to Google Fonts and ShareThis.
 *
 * <p>Consequently there is <strong>no supported way to request a historical
 * month</strong>, and this factory deliberately offers no method that would imply
 * one. See {@code docs/HABOUS_SOURCE.md}.
 */
@Component
public class HabousRequestFactory {

    private final HabousProperties properties;

    public HabousRequestFactory(HabousProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * The URL of the currently published timetable for a city.
     *
     * <p>The response is whatever the official site publishes <em>right now</em>
     * — the current Hijri month. The caller cannot choose the period.
     *
     * @param habousCityId the city's official {@code ville} identifier
     * @return the fully-formed request URI
     */
    public URI currentPublishedTimetableUri(String habousCityId) {
        Objects.requireNonNull(habousCityId, "habousCityId");
        if (habousCityId.isBlank()) {
            throw new IllegalArgumentException("habousCityId must not be blank");
        }
        String query = "ville=" + URLEncoder.encode(habousCityId, StandardCharsets.UTF_8);
        return URI.create(properties.baseUri() + "?" + query);
    }

    /** @return the URL of the page listing every official ville */
    public URI villeDirectoryUri() {
        return URI.create(properties.villeDirectoryUrl());
    }
}
