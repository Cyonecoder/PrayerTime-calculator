package io.github.cyonecoder.prayertime.domain.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class TimeConversionTest {

    private final TimeConversion conversion = new TimeConversion();

    @Test
    void decimal25_5NormalizesTo0130() {
        assertThat(conversion.toLocalTime(25.5)).isEqualTo(LocalTime.of(1, 30, 0));
    }

    @Test
    void decimalMinus0_5NormalizesTo2330() {
        assertThat(conversion.toLocalTime(-0.5)).isEqualTo(LocalTime.of(23, 30, 0));
    }

    @Test
    void roundsNotTruncates() {
        // 13.9999h is ~13:59:59.6; rounding (not truncation) reaches 14:00:00.
        assertThat(conversion.toLocalTime(13.9999)).isEqualTo(LocalTime.of(14, 0, 0));
    }

    @Test
    void decimal13_4999RoundsConsistently() {
        // Both second- and minute-rounding land on 13:30.
        assertThat(conversion.toLocalTime(13.4999)).isEqualTo(LocalTime.of(13, 30, 0));
        assertThat(conversion.toLocalTimeRoundedToMinute(13.4999)).isEqualTo(LocalTime.of(13, 30));
    }

    @Test
    void roundsToNearestMinute() {
        // 13:30:36 -> nearest minute 13:31.
        assertThat(conversion.toLocalTimeRoundedToMinute(13.51)).isEqualTo(LocalTime.of(13, 31));
    }

    @Test
    void supportsExplicitMinuteStepRounding() {
        // 13.46h = 13:27.6 -> nearest 5 minutes = 13:30.
        assertThat(conversion.toLocalTimeRoundedToMinutes(13.46, 5)).isEqualTo(LocalTime.of(13, 30));
        // Normalization also applies to the stepped variant.
        assertThat(conversion.toLocalTimeRoundedToMinutes(-0.5, 5)).isEqualTo(LocalTime.of(23, 30));
    }

    @Test
    void rejectsNonFiniteAndBadStep() {
        assertThatIllegalArgumentException().isThrownBy(() -> conversion.toLocalTime(Double.NaN));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> conversion.toLocalTime(Double.POSITIVE_INFINITY));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> conversion.toLocalTimeRoundedToMinutes(12.0, 0));
    }
}
