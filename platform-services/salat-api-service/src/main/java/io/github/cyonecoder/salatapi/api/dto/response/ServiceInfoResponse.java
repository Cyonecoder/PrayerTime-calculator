package io.github.cyonecoder.salatapi.api.dto.response;

import java.time.LocalDate;

/** Service information (spec 23.1). */
public record ServiceInfoResponse(
        String applicationVersion,
        int officialCatalogueCount,
        int registryCityCount,
        int verifiedMappingCount,
        int secApproachSupportCount,
        String parameterVersion,
        LocalDate latestSuccessfulSync,
        LocalDate latestOfficialDate,
        String latestArchiveStatus) {
}
