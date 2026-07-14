package io.github.cyonecoder.prayertime.audit.secapproach;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.cyonecoder.prayertime.application.provider.SecondsApproachPrayerTimesProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The new-city training catalog trains every candidate without offsets, assigns
 * one of six statuses, gives offsets only to ACCEPTED cities, and never lets a
 * non-accepted city be servable.
 */
class SecApproachNewCityTrainingCommandTest {

    private JsonObject run(Path output) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        new SecApproachNewCityTrainingCommand().run(
                Map.of("dataset", "research-data/habous", "output", output.toString()),
                new PrintStream(sink, true, StandardCharsets.UTF_8));
        try {
            return new Gson().fromJson(
                    Files.readString(output, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void catalogsEveryNewCityWithASingleStatusAndOffsetsOnlyWhenAccepted(@TempDir Path tmp) {
        JsonObject root = run(tmp.resolve("catalog.json"));

        var cities = root.getAsJsonArray("cities");
        assertThat(cities).hasSize(53);
        assertThat(root.getAsJsonObject("summary").get("ACCEPTED").getAsInt()).isEqualTo(34);
        assertThat(root.getAsJsonObject("summary").get("FAILED_ACCURACY_GATE").getAsInt()).isEqualTo(12);
        assertThat(root.getAsJsonObject("summary").get("MAPPING_UNVERIFIED").getAsInt()).isEqualTo(7);

        List<String> accepted = new ArrayList<>();
        for (var e : cities) {
            JsonObject c = e.getAsJsonObject();
            String status = c.get("status").getAsString();
            boolean hasOffsets = c.has("offsetsSeconds") && !c.get("offsetsSeconds").isJsonNull();
            if ("ACCEPTED".equals(status)) {
                assertThat(hasOffsets).as("%s ACCEPTED carries offsets", c.get("cityId")).isTrue();
                accepted.add(c.get("cityId").getAsString());
            } else {
                // A failed city must never receive (fabricated) offsets.
                assertThat(hasOffsets).as("%s must have no offsets", c.get("cityId")).isFalse();
            }
        }
        assertThat(accepted).hasSize(34);
    }

    @Test
    void acceptedOffsetsMatchTheCommittedProviderAndNonAcceptedFailClosed(@TempDir Path tmp) {
        JsonObject root = run(tmp.resolve("catalog.json"));
        SecondsApproachPrayerTimesProvider provider = new SecondsApproachPrayerTimesProvider();

        for (var e : root.getAsJsonArray("cities")) {
            JsonObject c = e.getAsJsonObject();
            String city = c.get("cityId").getAsString();
            if ("ACCEPTED".equals(c.get("status").getAsString())) {
                assertThat(provider.supports(city)).as("%s served", city).isTrue();
            } else {
                // FAILED_ACCURACY_GATE / MAPPING_UNVERIFIED etc.: provider fails closed.
                assertThat(provider.supports(city)).as("%s fails closed", city).isFalse();
            }
        }
    }

    @Test
    void datasetProvenanceIsRecordedForTrainedCities(@TempDir Path tmp) {
        JsonObject root = run(tmp.resolve("catalog.json"));
        for (var e : root.getAsJsonArray("cities")) {
            JsonObject c = e.getAsJsonObject();
            JsonObject ds = c.getAsJsonObject("dataset");
            if ("ACCEPTED".equals(c.get("status").getAsString())) {
                assertThat(ds.get("firstDate").getAsString()).isEqualTo("2026-06-17");
                assertThat(ds.get("lastDate").getAsString()).isEqualTo("2026-07-16");
                assertThat(ds.get("observationCount").getAsInt()).isEqualTo(180);
                assertThat(ds.getAsJsonArray("sourceChecksums")).isNotEmpty();
            }
        }
    }
}
