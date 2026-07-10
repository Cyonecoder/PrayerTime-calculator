package io.github.cyonecoder.prayertime.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRulesProvider;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class MoroccoCivilTimeServiceTest {

    private static final ZoneOffset PLUS_ONE = ZoneOffset.ofHours(1);
    private static final ZoneOffset ZERO = ZoneOffset.ofHours(0);

    private final MoroccoCivilTimeService service = new MoroccoCivilTimeService();

    @Test
    void usesTheFixedMoroccoZone() {
        assertThat(service.zoneId()).isEqualTo(ZoneId.of("Africa/Casablanca"));
    }

    // --- Offset regression cases (all resolved at local noon) --------------

    @Test
    void feb14_2026_isUtcPlusOne() {
        LocalDate d = LocalDate.of(2026, 2, 14);
        assertThat(service.offsetFor(d)).isEqualTo(PLUS_ONE);
        assertThat(service.clockInfoFor(d).mode()).isEqualTo(MoroccoClockMode.UTC_PLUS_ONE);
    }

    @Test
    void feb15_2026_isUtcZeroRamadanSuspension() {
        LocalDate d = LocalDate.of(2026, 2, 15);
        assertThat(service.offsetFor(d)).isEqualTo(ZERO);
        assertThat(service.clockInfoFor(d).mode())
                .isEqualTo(MoroccoClockMode.UTC_ZERO_RAMADAN_SUSPENSION);
    }

    @Test
    void mar21_2026_isUtcZeroRamadanSuspension() {
        LocalDate d = LocalDate.of(2026, 3, 21);
        assertThat(service.offsetFor(d)).isEqualTo(ZERO);
        assertThat(service.clockInfoFor(d).mode())
                .isEqualTo(MoroccoClockMode.UTC_ZERO_RAMADAN_SUSPENSION);
    }

    @Test
    void mar22_2026_isUtcPlusOne() {
        LocalDate d = LocalDate.of(2026, 3, 22);
        assertThat(service.offsetFor(d)).isEqualTo(PLUS_ONE);
        assertThat(service.clockInfoFor(d).mode()).isEqualTo(MoroccoClockMode.UTC_PLUS_ONE);
    }

    @Test
    void july3_2026_isUtcPlusOne() {
        LocalDate d = LocalDate.of(2026, 7, 3);
        assertThat(service.offsetFor(d)).isEqualTo(PLUS_ONE);
        assertThat(service.clockInfoFor(d).mode()).isEqualTo(MoroccoClockMode.UTC_PLUS_ONE);
    }

    @Test
    void localNoonIsMiddayInMoroccoZone() {
        var noon = service.localNoon(LocalDate.of(2026, 7, 3));
        assertThat(noon.getHour()).isEqualTo(12);
        assertThat(noon.getZone()).isEqualTo(ZoneId.of("Africa/Casablanca"));
        assertThat(noon.getOffset()).isEqualTo(PLUS_ONE);
    }

    // --- Transitions -------------------------------------------------------

    @Test
    void transitionsForYear2026ContainTheRamadanSuspensionPeriod() {
        List<ZoneOffsetTransition> transitions = service.transitionsForYear(2026);

        // Exactly two transitions in 2026: into the suspension and back out.
        assertThat(transitions).hasSize(2);

        ZoneOffsetTransition intoSuspension = transitions.get(0);
        ZoneOffsetTransition backToSummer = transitions.get(1);

        assertThat(intoSuspension.getOffsetAfter()).isEqualTo(ZERO);
        assertThat(intoSuspension.getInstant().atZone(ZoneOffset.UTC).getMonth())
                .isEqualTo(Month.FEBRUARY);

        assertThat(backToSummer.getOffsetAfter()).isEqualTo(PLUS_ONE);
        assertThat(backToSummer.getInstant().atZone(ZoneOffset.UTC).getMonth())
                .isEqualTo(Month.MARCH);
    }

    // --- Independence from the JVM default zone ----------------------------

    @Test
    void systemDefaultTimeZoneDoesNotAffectResults() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati")); // UTC+14
            assertThat(service.offsetFor(LocalDate.of(2026, 7, 3))).isEqualTo(PLUS_ONE);
            assertThat(service.offsetFor(LocalDate.of(2026, 3, 1))).isEqualTo(ZERO);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    // --- Null handling -----------------------------------------------------

    @Test
    void rejectsNullDates() {
        assertThatNullPointerException().isThrownBy(() -> service.offsetFor(null));
        assertThatNullPointerException().isThrownBy(() -> service.clockInfoFor(null));
        assertThatNullPointerException().isThrownBy(() -> service.localNoon(null));
        assertThatNullPointerException().isThrownBy(() -> service.modeFor(null));
    }

    // --- Diagnostic --------------------------------------------------------

    @Test
    void printsTzdbVersionWhenAvailable() {
        String versions;
        try {
            versions = ZoneRulesProvider.getVersions("Africa/Casablanca").keySet().toString();
        } catch (RuntimeException e) {
            versions = "(TZDB version unavailable: " + e.getMessage() + ")";
        }
        System.out.println("[diagnostic] Africa/Casablanca TZDB versions: " + versions);
        assertThat(versions).isNotNull();
    }

    // --- Unexpected offsets ------------------------------------------------

    @Test
    void classifiesUnexpectedOffsetsAsOther() {
        assertThat(service.modeFor(ZoneOffset.ofHours(3))).isEqualTo(MoroccoClockMode.OTHER);
        assertThat(service.modeFor(ZoneOffset.ofHoursMinutes(0, 30)))
                .isEqualTo(MoroccoClockMode.OTHER);
    }

    @Test
    void clockInfoRejectsUnexpectedHistoricalOffset() {
        // Before 1913 Casablanca used LMT (-00:30:20), which is neither +01:00
        // nor +00:00, so the service refuses to guess a mode.
        LocalDate historical = LocalDate.of(1900, 1, 1);
        ZoneOffset offset = service.offsetFor(historical);
        assertThat(service.modeFor(offset)).isEqualTo(MoroccoClockMode.OTHER);

        assertThatExceptionOfType(MoroccoCivilTimeService.UnsupportedOffsetException.class)
                .isThrownBy(() -> service.clockInfoFor(historical))
                .satisfies(ex -> assertThat(ex.offset()).isEqualTo(offset));
    }
}
