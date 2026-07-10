package io.github.cyonecoder.prayertime.audit.dataset;

import com.google.gson.GsonBuilder;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient.Fetcher;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient.HabousUnavailableException;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousLiveHtmlParser;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousLiveHtmlParser.HabousLiveDay;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousLiveHtmlParser.HabousParseException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Collects the current live Habous month for a set of cities and writes each as a
 * dataset JSON file (the flat {@code {days:[…]}} form that {@code ReferenceDataset}
 * consumes) together with source provenance (URL, fetch time, SHA-256).
 *
 * <p>Unlike {@link HabousReferenceCollector} (which stores Gregorian-month
 * snapshots parsed from the seven-column layout), this collector uses {@link
 * HabousLiveHtmlParser} to read the real nine-column Hijri page and resolves each
 * row to an absolute Gregorian date. It never calculates or invents a time: a
 * city that cannot be fetched, verified and parsed is reported, not filled in.
 */
public final class LiveHabousCollector {

    /** Parser identifier recorded in each file's provenance. */
    public static final String PARSER_VERSION = "habous-live-1";

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    /** The outcome category for one city. */
    public enum Status {
        CREATED, UNMAPPED, FETCH_FAILED, WRONG_CITY, WRONG_YEAR, INVALID_PAGE, EMPTY
    }

    /**
     * The result for one city.
     *
     * @param city      application slug
     * @param villeId   Habous ville id (null when unmapped)
     * @param status    the outcome
     * @param message   human explanation
     * @param dayCount  number of days written
     * @param firstDate first collected date (null when none)
     * @param lastDate  last collected date (null when none)
     * @param checksum  source SHA-256 (null when none)
     * @param sourceUrl the fetched URL (null when unmapped)
     * @param file      the written dataset file (null when nothing written)
     */
    public record Result(
            String city, String villeId, Status status, String message,
            int dayCount, LocalDate firstDate, LocalDate lastDate,
            String checksum, String sourceUrl, Path file) {

        public boolean isSaved() {
            return status == Status.CREATED;
        }
    }

    private final Fetcher fetcher;
    private final HabousLiveHtmlParser parser;
    private final HabousCityMapping mapping;
    private final Clock clock;
    private final HabousReferenceCollector.Sleeper sleeper;
    private final Duration requestDelay;

    public LiveHabousCollector(
            Fetcher fetcher, HabousLiveHtmlParser parser, HabousCityMapping mapping,
            Clock clock, HabousReferenceCollector.Sleeper sleeper, Duration requestDelay) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.mapping = Objects.requireNonNull(mapping, "mapping");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.requestDelay = Objects.requireNonNull(requestDelay, "requestDelay");
    }

    /** @return a collector that fetches the live site over a trust-augmented client. */
    public static LiveHabousCollector live(Duration requestDelay) {
        Fetcher fetcher = HabousClient.withHttpClient(HabousResearchTls.trustingHttpClient())
                ::fetchMonthlyHtml;
        return new LiveHabousCollector(fetcher, new HabousLiveHtmlParser(), HabousCityMapping.load(),
                Clock.systemUTC(), duration -> Thread.sleep(duration.toMillis()), requestDelay);
    }

    /**
     * Collects each city into {@code datasetDir}.
     *
     * @param cities     application city slugs
     * @param datasetDir the dataset output directory (created if needed)
     * @return the per-city results, in request order
     */
    public List<Result> collect(List<String> cities, Path datasetDir) {
        Objects.requireNonNull(cities, "cities");
        Objects.requireNonNull(datasetDir, "datasetDir");
        try {
            Files.createDirectories(datasetDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create dataset directory: " + datasetDir, e);
        }
        List<Result> results = new ArrayList<>();
        for (String city : cities) {
            results.add(collectCity(city, datasetDir));
        }
        return results;
    }

    private Result collectCity(String city, Path datasetDir) {
        Optional<HabousCityMapping.Entry> mapped = mapping.forCity(city);
        if (mapped.isEmpty()) {
            return new Result(city, null, Status.UNMAPPED,
                    "no Habous mapping for application city", 0, null, null, null, null, null);
        }
        HabousCityMapping.Entry entry = mapped.get();
        String sourceUrl = HabousClient.monthlyUri(entry.habousCityId()).toString();

        applyDelay();
        String html;
        try {
            html = fetcher.fetchMonthlyHtml(entry.habousCityId(), LocalDate.now(clock));
        } catch (HabousUnavailableException e) {
            return new Result(city, entry.habousCityId(), Status.FETCH_FAILED,
                    e.getMessage(), 0, null, null, null, sourceUrl, null);
        }

        if (!html.contains(entry.habousArabicName())) {
            return new Result(city, entry.habousCityId(), Status.WRONG_CITY,
                    "page did not contain expected Arabic name " + entry.habousArabicName(),
                    0, null, null, null, sourceUrl, null);
        }

        List<HabousLiveDay> days;
        try {
            days = parser.parse(html);
        } catch (HabousParseException e) {
            return new Result(city, entry.habousCityId(), Status.INVALID_PAGE,
                    e.getMessage(), 0, null, null, null, sourceUrl, null);
        }
        if (days.isEmpty()) {
            return new Result(city, entry.habousCityId(), Status.EMPTY,
                    "no day rows parsed", 0, null, null, null, sourceUrl, null);
        }

        String checksum = HabousSnapshotStore.sha256Hex(html);
        LocalDate first = days.get(0).date();
        LocalDate last = days.get(days.size() - 1).date();
        Path file = datasetDir.resolve(city + "-live-" + first + ".json");
        writeDataset(file, city, entry, sourceUrl, checksum, days);
        return new Result(city, entry.habousCityId(), Status.CREATED,
                "saved " + days.size() + " days", days.size(), first, last, checksum, sourceUrl, file);
    }

    private void writeDataset(
            Path file, String city, HabousCityMapping.Entry entry,
            String sourceUrl, String checksum, List<HabousLiveDay> days) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("source", "habous-live");
        root.put("city", city);
        root.put("habousVilleId", entry.habousCityId());
        root.put("habousArabicName", entry.habousArabicName());
        root.put("sourceUrl", sourceUrl);
        root.put("fetchedAt", clock.instant().toString());
        root.put("sourceSha256", checksum);
        root.put("parserVersion", PARSER_VERSION);

        List<Map<String, Object>> dayList = new ArrayList<>(days.size());
        for (HabousLiveDay d : days) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("city", city);
            m.put("date", d.date().toString());
            m.put("fajr", d.fajr().format(HM));
            m.put("sunrise", d.sunrise().format(HM));
            m.put("dhuhr", d.dhuhr().format(HM));
            m.put("asr", d.asr().format(HM));
            m.put("maghrib", d.maghrib().format(HM));
            m.put("isha", d.isha().format(HM));
            dayList.add(m);
        }
        root.put("days", dayList);

        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root, w);
            w.write("\n");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write dataset file " + file, e);
        }
    }

    private void applyDelay() {
        if (requestDelay.isZero() || requestDelay.isNegative()) {
            return;
        }
        try {
            sleeper.sleep(requestDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HabousUnavailableException("interrupted during request delay", e);
        }
    }
}
