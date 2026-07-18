package io.github.cyonecoder.salatapi.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.cyonecoder.salatapi.archive.model.ArchivedDay;

/**
 * One official published day for one city (spec 23.7). Official data only — never
 * a calculated fallback.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OfficialDayResponse(
        String habousCityId,
        String officialNameAr,
        String applicationCityId,
        String source,
        ArchivedDay day) {

    /** These are official published times, never calculated ones. */
    public static final String SOURCE = "HABOUS_OFFICIAL";
}
