package io.github.cyonecoder.salatapi.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.LocalDate;
import java.util.Map;

/**
 * The unified application-city response (spec 23.13). Always states which source
 * was requested, which was resolved, and whether a fallback happened — fallback
 * is never hidden.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"applicationCityId", "date", "precision", "requestedSource",
        "resolvedSource", "fallbackUsed", "fallbackReason", "times"})
public record UnifiedPrayerTimesResponse(
        String applicationCityId,
        LocalDate date,
        String precision,
        String requestedSource,
        String resolvedSource,
        boolean fallbackUsed,
        String fallbackReason,
        Map<String, String> times) {
}
