package io.github.cyonecoder.salatapi.api.dto.response;

import java.util.List;

/** Sec-approach information (spec 23.8). */
public record SecApproachInfoResponse(
        String parameterVersion,
        int supportedCityCount,
        String formula,
        String timezone,
        List<String> precisionModes,
        String validatedWindow,
        String accuracyDisclaimer) {
}
