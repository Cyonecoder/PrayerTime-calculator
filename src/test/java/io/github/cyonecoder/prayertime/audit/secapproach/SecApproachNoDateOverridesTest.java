package io.github.cyonecoder.prayertime.audit.secapproach;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.cyonecoder.prayertime.application.provider.SecondsApproachPrayerTimesProvider;
import io.github.cyonecoder.prayertime.audit.optimization.HabousRawEngine;
import io.github.cyonecoder.prayertime.audit.release.ReconstructedMethod;
import io.github.cyonecoder.prayertime.domain.model.City;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.infrastructure.city.MoroccoCityRegistry;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The sec-approach is a single constant per (city, prayer) — never a per-date
 * override. This is guarded both structurally (the resource has only the six
 * prayer keys per city) and behaviourally (the applied offset is identical on
 * every date).
 */
class SecApproachNoDateOverridesTest {

    @Test
    void resourceHasExactlyTheSixPrayerKeysAndNoDateKeys() {
        JsonObject offsets = resource().getAsJsonObject("offsets");
        for (String city : offsets.keySet()) {
            assertThat(offsets.getAsJsonObject(city).keySet())
                    .as("%s offset keys", city)
                    .containsExactlyInAnyOrder("fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha");
        }
    }

    @Test
    void theAppliedOffsetIsConstantAcrossEveryDate() {
        SecondsApproachPrayerTimesProvider provider = new SecondsApproachPrayerTimesProvider();
        HabousRawEngine engine = new HabousRawEngine();
        City city = new MoroccoCityRegistry().requireById("casablanca");
        var method = ReconstructedMethod.frozen();

        for (Prayer prayer : Prayer.values()) {
            Long constant = null;
            for (LocalDate d : List.of(
                    LocalDate.parse("2026-06-17"), LocalDate.parse("2026-07-01"),
                    LocalDate.parse("2026-07-16"), LocalDate.parse("2026-12-25"))) {
                long raw = Math.round(
                        engine.rawSecondsOfDay(prayer, method, city.coordinates(), city.zone(), d));
                long precise = provider.secondPreciseTime(city.id(), d, prayer).toSecondOfDay();
                long offset = Math.floorMod(precise - raw, 86_400L);
                if (constant == null) {
                    constant = offset;
                } else {
                    assertThat(offset).as("%s offset on %s", prayer, d).isEqualTo(constant);
                }
            }
        }
    }

    private JsonObject resource() {
        try (InputStream in = getClass().getResourceAsStream("/research/morocco-habous-secaproach.json")) {
            return new Gson().fromJson(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
