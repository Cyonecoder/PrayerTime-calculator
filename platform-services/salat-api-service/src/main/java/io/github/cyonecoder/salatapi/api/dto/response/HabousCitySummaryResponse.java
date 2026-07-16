package io.github.cyonecoder.salatapi.api.dto.response;

/**
 * One official Habous city (spec 23.2).
 *
 * @param habousCityId        the official ville id
 * @param officialNameAr      the exact Arabic name
 * @param applicationCityId   the mapped application slug, or null
 * @param mapped              whether a verified application mapping exists
 * @param secApproachSupported whether the city has sec-approach offsets
 * @param available           whether official data is currently available
 */
public record HabousCitySummaryResponse(
        String habousCityId,
        String officialNameAr,
        String applicationCityId,
        boolean mapped,
        boolean secApproachSupported,
        boolean available) {
}
