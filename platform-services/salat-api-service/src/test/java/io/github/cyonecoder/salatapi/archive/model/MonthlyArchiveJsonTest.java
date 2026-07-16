package io.github.cyonecoder.salatapi.archive.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The serialized shape of a monthly archive (spec 17).
 *
 * <p>The model being right is not the same as the wire format being right: field
 * names, time formatting and null handling are all decided by serialization. This
 * asserts the actual JSON, since that is what consumers parse.
 */
class MonthlyArchiveJsonTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static ArchivedDay day() {
        return new ArchivedDay(
                LocalDate.of(2026, 7, 1),
                LocalTime.of(4, 28), LocalTime.of(6, 17), LocalTime.of(13, 36),
                LocalTime.of(17, 17), LocalTime.of(20, 47), LocalTime.of(22, 20),
                "abc123", Instant.parse("2026-07-01T03:30:00Z"), Instant.parse("2026-07-15T03:30:00Z"));
    }

    private static GregorianMonthlyArchive archive(List<MonthlyCitySchedule> cities, long complete,
            long partial, long failed) {
        return new GregorianMonthlyArchive(
                GregorianMonthlyArchive.SCHEMA_VERSION,
                GregorianMonthlyArchive.SOURCE_OFFICIAL,
                "2026-07",
                GregorianMonthlyArchive.PERIOD_TYPE_GREGORIAN_MONTH,
                GregorianMonthlyArchive.TIMEZONE,
                MonthlyArchiveStatus.PARTIAL,
                Instant.parse("2026-07-15T03:30:00Z"),
                Instant.parse("2026-07-15T03:30:00Z"),
                191, cities.size(), complete, partial, failed, cities);
    }

    @Test
    void theEnvelopeSerializesExactlyAsDocumented() throws Exception {
        MonthlyCitySchedule rabat = new MonthlyCitySchedule(
                "1", "الرباط", "rabat", true, CityArchiveStatus.PARTIAL,
                31, 15, List.of(day()), null, List.of("abc123"));

        ObjectNode json = (ObjectNode) mapper.readTree(
                mapper.writeValueAsString(archive(List.of(rabat), 0, 1, 0)));

        assertThat(json.get("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(json.get("source").asText()).isEqualTo("HABOUS_OFFICIAL");
        assertThat(json.get("period").asText()).isEqualTo("2026-07");
        assertThat(json.get("periodType").asText()).isEqualTo("GREGORIAN_MONTH");
        assertThat(json.get("timezone").asText()).isEqualTo("Africa/Casablanca");
        assertThat(json.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(json.get("generatedAt").asText()).isEqualTo("2026-07-15T03:30:00Z");
        assertThat(json.get("lastUpdatedAt").asText()).isEqualTo("2026-07-15T03:30:00Z");
        assertThat(json.get("officialCatalogCityCount").asInt()).isEqualTo(191);
        assertThat(json.get("cityCount").asInt()).isEqualTo(1);
        assertThat(json.get("completeCityCount").asInt()).isZero();
        assertThat(json.get("partialCityCount").asInt()).isEqualTo(1);
        assertThat(json.get("failedCityCount").asInt()).isZero();
        assertThat(json.get("cities").isArray()).isTrue();
    }

    @Test
    void aCityAndItsDaysSerializeWithHhMmTimes() throws Exception {
        MonthlyCitySchedule rabat = new MonthlyCitySchedule(
                "1", "الرباط", "rabat", true, CityArchiveStatus.PARTIAL,
                31, 15, List.of(day()), null, List.of("abc123"));

        ObjectNode city = (ObjectNode) mapper.readTree(
                mapper.writeValueAsString(archive(List.of(rabat), 0, 1, 0))).get("cities").get(0);

        assertThat(city.get("habousCityId").asText()).isEqualTo("1");
        assertThat(city.get("officialNameAr").asText()).isEqualTo("الرباط");
        assertThat(city.get("applicationCityId").asText()).isEqualTo("rabat");
        assertThat(city.get("secApproachSupported").asBoolean()).isTrue();
        assertThat(city.get("status").asText()).isEqualTo("PARTIAL");
        assertThat(city.get("daysExpected").asInt()).isEqualTo(31);
        assertThat(city.get("daysAvailable").asInt()).isEqualTo(15);

        ObjectNode first = (ObjectNode) city.get("days").get(0);
        assertThat(first.get("date").asText()).isEqualTo("2026-07-01");
        // Times must be HH:mm strings, not arrays or second-precision.
        assertThat(first.get("fajr").asText()).isEqualTo("04:28");
        assertThat(first.get("sunrise").asText()).isEqualTo("06:17");
        assertThat(first.get("dhuhr").asText()).isEqualTo("13:36");
        assertThat(first.get("asr").asText()).isEqualTo("17:17");
        assertThat(first.get("maghrib").asText()).isEqualTo("20:47");
        assertThat(first.get("isha").asText()).isEqualTo("22:20");
        assertThat(first.get("sourceChecksum").asText()).isEqualTo("abc123");
        assertThat(first.get("firstObservedAt").asText()).isEqualTo("2026-07-01T03:30:00Z");
        assertThat(first.get("lastObservedAt").asText()).isEqualTo("2026-07-15T03:30:00Z");
    }

    @Test
    void aFailedCitySerializesWithItsFailureCodeAndNoDays() throws Exception {
        MonthlyCitySchedule failed = new MonthlyCitySchedule(
                "4", "الرماني", null, false, CityArchiveStatus.UNAVAILABLE,
                31, 0, List.of(), ArchiveFailureCode.TIMETABLE_UNAVAILABLE, List.of());

        ObjectNode city = (ObjectNode) mapper.readTree(
                mapper.writeValueAsString(archive(List.of(failed), 0, 0, 1))).get("cities").get(0);

        assertThat(city.get("habousCityId").asText()).isEqualTo("4");
        assertThat(city.get("officialNameAr").asText()).isEqualTo("الرماني");
        assertThat(city.get("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(city.get("daysExpected").asInt()).isEqualTo(31);
        assertThat(city.get("daysAvailable").asInt()).isZero();
        assertThat(city.get("failureCode").asText()).isEqualTo("TIMETABLE_UNAVAILABLE");
        assertThat(city.get("days")).isEmpty();

        // An official-only city has no application slug; the field is omitted
        // rather than carrying a misleading empty string.
        assertThat(city.has("applicationCityId")).isFalse();
        assertThat(city.get("secApproachSupported").asBoolean()).isFalse();
    }

    @Test
    void aSuccessfulCityCarriesNoFailureCode() throws Exception {
        MonthlyCitySchedule rabat = new MonthlyCitySchedule(
                "1", "الرباط", "rabat", true, CityArchiveStatus.COMPLETE,
                31, 31, List.of(day()), null, List.of("abc123"));

        ObjectNode city = (ObjectNode) mapper.readTree(
                mapper.writeValueAsString(archive(List.of(rabat), 1, 0, 0))).get("cities").get(0);

        assertThat(city.has("failureCode")).isFalse();
    }

    @Test
    void theArchiveRoundTrips() throws Exception {
        MonthlyCitySchedule rabat = new MonthlyCitySchedule(
                "1", "الرباط", "rabat", true, CityArchiveStatus.PARTIAL,
                31, 15, List.of(day()), null, List.of("abc123"));
        MonthlyCitySchedule failed = new MonthlyCitySchedule(
                "4", "الرماني", null, false, CityArchiveStatus.UNAVAILABLE,
                31, 0, List.of(), ArchiveFailureCode.TIMETABLE_UNAVAILABLE, List.of());
        GregorianMonthlyArchive original = archive(List.of(rabat, failed), 0, 1, 1);

        GregorianMonthlyArchive parsed = mapper.readValue(
                mapper.writeValueAsString(original), GregorianMonthlyArchive.class);

        assertThat(parsed).isEqualTo(original);
        assertThat(parsed.city("4").orElseThrow().failureCode())
                .isEqualTo(ArchiveFailureCode.TIMETABLE_UNAVAILABLE);
        assertThat(parsed.city("1").orElseThrow().days().get(0).fajr())
                .isEqualTo(LocalTime.of(4, 28));
    }
}
