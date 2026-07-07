package io.github.cyonecoder.prayertime.audit.release;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The reviewed correction/override layer that sits on top of the pure global
 * formula.
 *
 * <p>Two kinds of entry exist, both loaded from bundled JSON resources and both
 * <strong>empty by design</strong> for the pure-formula reconstruction:
 * <ul>
 *   <li>{@link CityCorrection} — a per-city, per-prayer whole-minute offset that
 *       is applied only when {@code approved} and its {@code sourceChecksum}
 *       still matches the city's current dataset checksum;</li>
 *   <li>{@link OfficialOverride} — an isolated official value for one city/date/
 *       prayer, applied only when {@code reviewed} and its {@code sourceChecksum}
 *       still matches.</li>
 * </ul>
 * Unapproved/unreviewed entries, and entries whose source checksum has drifted,
 * are never applied; they are surfaced so the Habous-compatible mode can fail
 * closed rather than silently ignore them.
 */
public final class CorrectionsLayer {

    private static final String CORRECTIONS_RESOURCE = "/research/habous-corrections.json";
    private static final String OVERRIDES_RESOURCE = "/research/habous-overrides.json";

    /** A per-city, per-prayer whole-minute correction. */
    public record CityCorrection(
            String city, Prayer prayer, int deltaMinutes,
            boolean approved, String reviewer, String sourceChecksum, String note) {
    }

    /** An isolated reviewed official value for one city/date/prayer. */
    public record OfficialOverride(
            String city, LocalDate date, Prayer prayer, int officialMinuteOfDay,
            boolean reviewed, String source, String sourceChecksum, String note) {
    }

    private final List<CityCorrection> corrections;
    private final List<OfficialOverride> overrides;

    public CorrectionsLayer(List<CityCorrection> corrections, List<OfficialOverride> overrides) {
        this.corrections = List.copyOf(corrections);
        this.overrides = List.copyOf(overrides);
    }

    /** @return the layer loaded from the bundled resources. */
    public static CorrectionsLayer load() {
        return new CorrectionsLayer(
                readCorrections(CORRECTIONS_RESOURCE), readOverrides(OVERRIDES_RESOURCE));
    }

    public List<CityCorrection> corrections() {
        return corrections;
    }

    public List<OfficialOverride> overrides() {
        return overrides;
    }

    /**
     * The approved correction for a city/prayer whose source checksum still
     * matches the supplied per-city checksum, if any.
     *
     * @param city             application city slug
     * @param prayer           the prayer
     * @param cityChecksumById resolves a city's current dataset checksum
     * @return the applicable delta, or empty
     */
    public Optional<CityCorrection> approvedCorrection(
            String city, Prayer prayer, java.util.function.Function<String, String> cityChecksumById) {
        String slug = normalize(city);
        return corrections.stream()
                .filter(c -> normalize(c.city()).equals(slug) && c.prayer() == prayer)
                .filter(CityCorrection::approved)
                .filter(c -> Objects.equals(c.sourceChecksum(), cityChecksumById.apply(slug)))
                .findFirst();
    }

    /** The reviewed, checksum-current override for a city/date/prayer, if any. */
    public Optional<OfficialOverride> reviewedOverride(
            String city, LocalDate date, Prayer prayer,
            java.util.function.Function<String, String> cityChecksumById) {
        String slug = normalize(city);
        return overrides.stream()
                .filter(o -> normalize(o.city()).equals(slug) && o.date().equals(date)
                        && o.prayer() == prayer)
                .filter(OfficialOverride::reviewed)
                .filter(o -> Objects.equals(o.sourceChecksum(), cityChecksumById.apply(slug)))
                .findFirst();
    }

    /** @return corrections that exist but are not approved. */
    public List<CityCorrection> unapprovedCorrections() {
        return corrections.stream().filter(c -> !c.approved()).toList();
    }

    /** @return overrides that exist but are not reviewed. */
    public List<OfficialOverride> unreviewedOverrides() {
        return overrides.stream().filter(o -> !o.reviewed()).toList();
    }

    /** @return approved corrections whose recorded source checksum no longer matches. */
    public List<CityCorrection> staleCorrections(
            java.util.function.Function<String, String> cityChecksumById) {
        return corrections.stream()
                .filter(CityCorrection::approved)
                .filter(c -> !Objects.equals(c.sourceChecksum(), cityChecksumById.apply(normalize(c.city()))))
                .toList();
    }

    /** @return reviewed overrides whose recorded source checksum no longer matches. */
    public List<OfficialOverride> staleOverrides(
            java.util.function.Function<String, String> cityChecksumById) {
        return overrides.stream()
                .filter(OfficialOverride::reviewed)
                .filter(o -> !Objects.equals(o.sourceChecksum(), cityChecksumById.apply(normalize(o.city()))))
                .toList();
    }

    private static String normalize(String city) {
        return city == null ? "" : city.trim().toLowerCase(Locale.ROOT);
    }

    // --- Loading -------------------------------------------------------------

    private static List<CityCorrection> readCorrections(String resource) {
        CorrectionsFile file = read(resource, CorrectionsFile.class);
        return file == null || file.corrections == null ? List.of() : file.corrections;
    }

    private static List<OfficialOverride> readOverrides(String resource) {
        OverridesFile file = read(resource, OverridesFile.class);
        return file == null || file.overrides == null ? List.of() : file.overrides;
    }

    private static Gson gson() {
        JsonDeserializer<LocalDate> localDate =
                (json, typeOfT, context) -> LocalDate.parse(json.getAsString());
        return new GsonBuilder().registerTypeAdapter(LocalDate.class, localDate).create();
    }

    private static <T> T read(String resource, Class<T> type) {
        try (InputStream in = CorrectionsLayer.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found: " + resource);
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return gson().fromJson(reader, type);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read resource: " + resource, e);
        }
    }

    private static final class CorrectionsFile {
        List<CityCorrection> corrections;
    }

    private static final class OverridesFile {
        List<OfficialOverride> overrides;
    }
}
