package io.github.cyonecoder.prayertime.audit.secapproach;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code research evaluate-secapproach} scores parameters against a baseline, offline. */
class SecApproachEvaluationCommandTest {

    private static final String COMMITTED =
            "src/main/resources/research/morocco-habous-secaproach.json";

    private JsonObject run(Map<String, String> flags, Path out) {
        new SecApproachEvaluationCommand().run(flags,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            return new Gson().fromJson(Files.readString(out, StandardCharsets.UTF_8), JsonObject.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void evaluatesCommittedParametersAgainstTheGlobalBaseline(@TempDir Path tmp) {
        Path out = tmp.resolve("evaluation-report.json");
        JsonObject root = run(Map.of(
                "dataset", "research-data/habous", "parameters", COMMITTED,
                "baseline", "global", "output-file", out.toString()), out);

        JsonObject agg = root.getAsJsonObject("aggregate");
        assertThat(agg.get("exactPercentage").getAsDouble()).isGreaterThanOrEqualTo(95.0);
        assertThat(agg.get("withinOnePercentage").getAsDouble()).isGreaterThanOrEqualTo(99.5);
        assertThat(agg.get("maxAbsoluteError").getAsInt()).isLessThanOrEqualTo(2);
        assertThat(agg.get("improvementPercentagePoints").getAsDouble()).isGreaterThanOrEqualTo(10.0);
        assertThat(agg.get("gatesPassed").getAsBoolean()).isTrue();
        // One entry per committed city.
        assertThat(root.getAsJsonArray("cities")).hasSize(48);
    }

    @Test
    void cityAndDateSelectorsNarrowTheEvaluation(@TempDir Path tmp) {
        Path out = tmp.resolve("eval.json");
        JsonObject root = run(Map.of(
                "dataset", "research-data/habous", "parameters", COMMITTED,
                "cities", "casablanca,rabat,safi", "output-file", out.toString()), out);
        assertThat(root.getAsJsonArray("cities")).hasSize(3);
    }
}
