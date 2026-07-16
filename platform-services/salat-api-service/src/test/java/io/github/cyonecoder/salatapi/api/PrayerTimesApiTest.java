package io.github.cyonecoder.salatapi.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.cyonecoder.salatapi.scheduling.HabousDailySyncJob;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The REST surface end to end (spec 23), against a real Spring context. One sync
 * runs first (serving the captured Rabat page for every ville) so the archive,
 * verification and status endpoints have data to return.
 */
@SpringBootTest(properties = "salat.scheduling.enabled=false")
class PrayerTimesApiTest {

    private static final MockWebServer HABOUS = new MockWebServer();

    @DynamicPropertySource
    static void habousUrl(DynamicPropertyRegistry registry) throws Exception {
        HABOUS.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setBody(realTimetable());
            }
        });
        HABOUS.start();
        registry.add("habous.base-url", () -> HABOUS.url("/horaire").toString());
        registry.add("habous.ville-directory-url", () -> HABOUS.url("/index").toString());
        registry.add("habous.request-delay", () -> "0s");
        registry.add("salat.data-directory", () -> {
            try {
                return java.nio.file.Files.createTempDirectory("salat-api-test").toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static String realTimetable() {
        try (InputStream in = PrayerTimesApiTest.class.getResourceAsStream(
                "/fixtures/habous/horaire-rabat-live-2026-muharram.html")) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private HabousDailySyncJob syncJob;
    @Autowired
    private io.github.cyonecoder.salatapi.api.filter.CorrelationIdFilter correlationIdFilter;

    private MockMvc mvc;
    private static boolean synced;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(correlationIdFilter).build();
        if (!synced) {
            syncJob.runOnce();  // populate archive + verification for 2026-06/07
            synced = true;
        }
    }

    @Test
    void info() throws Exception {
        mvc.perform(get("/api/v1/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.officialCatalogueCount", is(191)))
                .andExpect(jsonPath("$.registryCityCount", is(67)))
                .andExpect(jsonPath("$.verifiedMappingCount", is(60)))
                .andExpect(jsonPath("$.secApproachSupportCount", is(48)))
                .andExpect(jsonPath("$.parameterVersion", is("morocco-habous-secaproach-v2")))
                .andExpect(jsonPath("$.latestOfficialDate", is("2026-07-16")));
    }

    @Test
    void secApproachInfoCarriesTheHonestDisclaimer() throws Exception {
        mvc.perform(get("/api/v1/sec-approach/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.supportedCityCount", is(48)))
                .andExpect(jsonPath("$.validatedWindow", is("2026-06-17 through 2026-07-16")))
                .andExpect(jsonPath("$.accuracyDisclaimer", containsString("Not an annual accuracy claim")));
    }

    @Test
    void secApproachCitiesAreExactlyThe48() throws Exception {
        mvc.perform(get("/api/v1/sec-approach/cities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(48)));
    }

    @Test
    void habousCityFiltersReturnTheExpectedCounts() throws Exception {
        mvc.perform(get("/api/v1/habous/cities")).andExpect(jsonPath("$.length()", is(191)));
        mvc.perform(get("/api/v1/habous/cities?mapped=true")).andExpect(jsonPath("$.length()", is(60)));
        mvc.perform(get("/api/v1/habous/cities?secApproachSupported=true"))
                .andExpect(jsonPath("$.length()", is(48)));
        // Only Rabat validated against the fixture, so exactly one is available.
        mvc.perform(get("/api/v1/habous/cities?available=true"))
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].habousCityId", is("1")));
    }

    @Test
    void monthsListAndFullArchive() throws Exception {
        mvc.perform(get("/api/v1/habous/months"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(2)));   // June and July

        mvc.perform(get("/api/v1/habous/months/2026/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period", is("2026-07")))
                .andExpect(jsonPath("$.cityCount", is(191)));   // includeUnavailable defaults true

        mvc.perform(get("/api/v1/habous/months/2026/7?includeUnavailable=false"))
                .andExpect(jsonPath("$.cities.length()", is(1)));   // only Rabat has data
    }

    @Test
    void monthlyFileDownloadHasTheAttachmentHeader() throws Exception {
        mvc.perform(get("/api/v1/habous/months/2026/7/file"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"habous-prayer-times-2026-07.json\""));
    }

    @Test
    void officialDayIsServedForACollectedCity() throws Exception {
        mvc.perform(get("/api/v1/habous/cities/1/prayer-times?date=2026-07-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source", is("HABOUS_OFFICIAL")))
                .andExpect(jsonPath("$.day.date", is("2026-07-01")))
                .andExpect(jsonPath("$.day.fajr", is("04:28")));
    }

    @Test
    void officialDayForAnUncollectedCityIs404() throws Exception {
        mvc.perform(get("/api/v1/habous/cities/999/prayer-times?date=2026-07-01"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unifiedBestPrefersOfficialWhenAvailableAndSaysSo() throws Exception {
        // Rabat has official data for 2026-07-01.
        mvc.perform(get("/api/v1/prayer-times/cities/rabat?date=2026-07-01&source=best"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedSource", is("HABOUS_OFFICIAL")))
                .andExpect(jsonPath("$.fallbackUsed", is(false)))
                .andExpect(jsonPath("$.times.fajr", is("04:28")));   // the official time
    }

    @Test
    void unifiedBestFallsBackToSecApproachAndNeverHidesIt() throws Exception {
        // No official data for this date; best falls back and must disclose it.
        mvc.perform(get("/api/v1/prayer-times/cities/rabat?date=2030-01-01&source=best"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedSource", is("SEC_APPROACH")))
                .andExpect(jsonPath("$.fallbackUsed", is(true)))
                .andExpect(jsonPath("$.fallbackReason", containsString("Official data is unavailable")));
    }

    @Test
    void aNonOffsetCityIs422NotAGuess() throws Exception {
        // berkane is mapped but untrained; sale is unmapped. Neither is guessed.
        mvc.perform(get("/api/v1/sec-approach/cities/berkane/prayer-times?date=2026-07-01"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("SEC_APPROACH_CITY_UNSUPPORTED")));
        mvc.perform(get("/api/v1/sec-approach/cities/sale/prayer-times?date=2026-07-01"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void errorsAreProblemDetailsWithStableCodes() throws Exception {
        mvc.perform(get("/api/v1/sec-approach/cities/casablanca/prayer-times?date=2026-07-01&precision=nano"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code", is("INVALID_PRECISION")))
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.correlationId").exists());

        mvc.perform(get("/api/v1/sec-approach/cities/casablanca/prayer-times?date=not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_DATE")));

        mvc.perform(get("/api/v1/prayer-times/cities/rabat?date=2026-07-01&source=frobnicate"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_SOURCE")));

        mvc.perform(get("/api/v1/sec-approach/cities/casablanca/prayer-times/range"
                        + "?from=2026-01-01&to=2027-12-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("DATE_RANGE_TOO_LARGE")));

        mvc.perform(get("/api/v1/habous/months/2026/13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("INVALID_MONTH")));

        mvc.perform(get("/api/v1/habous/months/2026/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("OFFICIAL_MONTH_NOT_AVAILABLE")));

        mvc.perform(get("/api/v1/habous/cities/by-application-id/nowhere"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("APPLICATION_CITY_NOT_MAPPED")));
    }

    @Test
    void errorBodiesNeverLeakInternals() throws Exception {
        String body = mvc.perform(get("/api/v1/habous/months/2026/9"))
                .andReturn().getResponse().getContentAsString();

        // No stack traces, filesystem paths, package names, or env values.
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("io.github.cyonecoder")
                .doesNotContain("Exception")
                .doesNotContain(".java")
                .doesNotContain(System.getProperty("user.dir"))
                .doesNotContain("/Users/")
                .doesNotContain("at org.springframework");
    }

    @Test
    void verificationEndpoints() throws Exception {
        mvc.perform(get("/api/v1/verification/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parameterVersion", is("morocco-habous-secaproach-v2")))
                .andExpect(jsonPath("$.comparedCityCount", greaterThanOrEqualTo(1)));

        mvc.perform(get("/api/v1/verification/dates/2026-07-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date", is("2026-07-01")));

        mvc.perform(get("/api/v1/verification/dates/2026-07-01/cities/rabat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationCityId", is("rabat")));

        mvc.perform(get("/api/v1/verification/months/2026/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThanOrEqualTo(1)));
    }

    @Test
    void dataStatusReflectsTheRun() throws Exception {
        mvc.perform(get("/api/v1/data-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.officialCatalogueCount", is(191)))
                .andExpect(jsonPath("$.secApproachAvailable", is(true)))
                .andExpect(jsonPath("$.monthlyArchiveStatuses['2026-07']").exists())
                .andExpect(jsonPath("$.latestVerification.date", is("2026-07-16")));
    }

    @Test
    void everyResponseCarriesACorrelationId() throws Exception {
        mvc.perform(get("/api/v1/info"))
                .andExpect(header().exists("X-Correlation-Id"));
    }
}
