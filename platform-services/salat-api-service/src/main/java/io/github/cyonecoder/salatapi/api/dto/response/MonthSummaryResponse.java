package io.github.cyonecoder.salatapi.api.dto.response;

import io.github.cyonecoder.salatapi.archive.model.MonthlyArchiveStatus;
import java.time.Instant;

/** A monthly archive summary (spec 23.3), drawn from its manifest. */
public record MonthSummaryResponse(
        String period,
        MonthlyArchiveStatus status,
        int officialCatalogCityCount,
        int archiveCityCount,
        long completeCityCount,
        long partialCityCount,
        long unavailableCityCount,
        int expectedDaysPerCompleteCity,
        Instant lastSuccessfulSyncAt) {
}
