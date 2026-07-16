package io.github.cyonecoder.salatapi.habous.model;

import java.util.Objects;

/**
 * One entry of the official Habous ville directory: the numeric ville id used by
 * the official site and the exact Arabic name it publishes.
 *
 * <p>The Arabic name is preserved verbatim from the source and must never be
 * transliterated, normalized or "corrected" — it is the identity the official
 * page publishes, and mapping verification compares against it.
 *
 * @param habousCityId    the official numeric ville id (e.g. {@code "1"} for الرباط)
 * @param habousArabicName the exact Arabic name as published
 */
public record HabousVille(String habousCityId, String habousArabicName) {

    public HabousVille {
        Objects.requireNonNull(habousCityId, "habousCityId");
        Objects.requireNonNull(habousArabicName, "habousArabicName");
        if (habousCityId.isBlank()) {
            throw new IllegalArgumentException("habousCityId must not be blank");
        }
        if (habousArabicName.isBlank()) {
            throw new IllegalArgumentException("habousArabicName must not be blank for ville " + habousCityId);
        }
    }
}
