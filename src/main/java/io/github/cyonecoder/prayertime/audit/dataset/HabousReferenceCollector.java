package io.github.cyonecoder.prayertime.audit.dataset;

import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient.Fetcher;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient.HabousUnavailableException;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousHtmlParser;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousHtmlParser.HabousDay;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousHtmlParser.HabousParseException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Collects and normalizes official Habous monthly schedules into immutable
 * snapshots. Reuses the production {@link HabousClient.Fetcher} and
 * {@link HabousHtmlParser}; it never falls back to calculated times and never
 * changes production behaviour.
 *
 * <p>Per city and month it applies: a configurable delay between requests, a
 * bounded retry, the client's request timeout and explicit User-Agent, reuse of
 * any existing valid snapshot (no duplicate request), rejection of invalid HTML
 * and incomplete tables, verification of the mapped city and the requested
 * period, preservation of the Arabic name, and storage of the source checksum.
 */
public final class HabousReferenceCollector {

    private static final String PARSER_VERSION = "habous-html-1";

    /** Abstraction over sleeping, so the request delay is testable without waiting. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(java.time.Duration duration) throws InterruptedException;
    }

    /** The outcome category for one city/month. */
    public enum Status {
        CREATED,
        DUPLICATE,
        REVISION_SAVED,
        CACHED,
        UNMAPPED,
        FETCH_FAILED,
        INVALID_PAGE,
        WRONG_CITY,
        WRONG_PERIOD,
        INCOMPLETE_TABLE;

        /** @return whether this status corresponds to a validated, saved (or cached) month. */
        public boolean isSavedOrCached() {
            return this == CREATED || this == DUPLICATE || this == REVISION_SAVED || this == CACHED;
        }

        /** @return whether this status is an invalid/rejected page. */
        public boolean isInvalid() {
            return this == INVALID_PAGE || this == WRONG_CITY || this == WRONG_PERIOD
                    || this == INCOMPLETE_TABLE;
        }
    }

    /**
     * The result for a single city/month.
     *
     * @param applicationCityId application city slug
     * @param habousCityId      external Habous id (null when unmapped)
     * @param year              the year
     * @param month             the month
     * @param status            the outcome
     * @param message           a human explanation
     * @param checksum          the source checksum (null when none)
     * @param dayCount          the number of day records saved (0 when none)
     * @param attempts          the number of fetch attempts made
     */
    public record Record(
            String applicationCityId,
            String habousCityId,
            int year,
            int month,
            Status status,
            String message,
            String checksum,
            int dayCount,
            int attempts) {
    }

    /**
     * The full collection outcome.
     *
     * @param year     the collected year
     * @param settings the settings used
     * @param records  the per-city/month records, in request order
     */
    public record Outcome(int year, CollectionSettings settings, List<Record> records) {
    }

    private final Fetcher fetcher;
    private final HabousHtmlParser parser;
    private final HabousSnapshotStore store;
    private final HabousCityMapping mapping;
    private final Clock clock;
    private final Sleeper sleeper;
    private final CollectionSettings settings;

    public HabousReferenceCollector(
            Fetcher fetcher,
            HabousHtmlParser parser,
            HabousSnapshotStore store,
            HabousCityMapping mapping,
            Clock clock,
            Sleeper sleeper,
            CollectionSettings settings) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.store = Objects.requireNonNull(store, "store");
        this.mapping = Objects.requireNonNull(mapping, "mapping");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Collects the given cities and months for one year.
     *
     * @param applicationCityIds the application city slugs to collect
     * @param year               the Gregorian year
     * @param months             the months (1..12) to collect
     * @return the collection outcome
     */
    public Outcome collect(List<String> applicationCityIds, int year, List<Integer> months) {
        Objects.requireNonNull(applicationCityIds, "applicationCityIds");
        Objects.requireNonNull(months, "months");
        List<Record> records = new ArrayList<>();
        for (String cityId : applicationCityIds) {
            Optional<HabousCityMapping.Entry> mapped = mapping.forCity(cityId);
            for (int month : months) {
                if (mapped.isEmpty()) {
                    records.add(new Record(cityId, null, year, month, Status.UNMAPPED,
                            "no verified Habous mapping for application city", null, 0, 0));
                    continue;
                }
                records.add(collectMonth(mapped.get(), year, month));
            }
        }
        return new Outcome(year, settings, List.copyOf(records));
    }

    private Record collectMonth(HabousCityMapping.Entry entry, int year, int month) {
        String cityId = entry.applicationCityId();

        // Cache reuse: never issue a duplicate request for an existing valid month.
        if (!settings.refresh() && store.primaryExists(cityId, year, month)) {
            HabousSnapshotStore.StoredSnapshot snapshot = store.readPrimary(cityId, year, month).orElseThrow();
            return new Record(cityId, entry.habousCityId(), year, month, Status.CACHED,
                    "reused existing snapshot", snapshot.checksum(),
                    snapshot.month().dailyRecords().size(), 0);
        }

        LocalDate probe = LocalDate.of(year, month, 1);
        String html;
        int attempts = 0;
        try {
            FetchResult fetch = fetchWithRetry(entry.habousCityId(), probe);
            attempts = fetch.attempts();
            html = fetch.html();
        } catch (HabousUnavailableException e) {
            return new Record(cityId, entry.habousCityId(), year, month, Status.FETCH_FAILED,
                    e.getMessage(), null, 0, settings.maxRetries() + 1);
        }

        // Verify the correct city and period before trusting the table.
        if (!html.contains(entry.habousArabicName())) {
            return new Record(cityId, entry.habousCityId(), year, month, Status.WRONG_CITY,
                    "source did not contain expected Arabic name " + entry.habousArabicName(),
                    null, 0, attempts);
        }
        if (!html.contains(Integer.toString(year))) {
            return new Record(cityId, entry.habousCityId(), year, month, Status.WRONG_PERIOD,
                    "source did not contain expected year " + year, null, 0, attempts);
        }

        List<HabousDay> parsed;
        try {
            parsed = parser.parse(html);
        } catch (HabousParseException e) {
            return new Record(cityId, entry.habousCityId(), year, month, Status.INVALID_PAGE,
                    e.getMessage(), null, 0, attempts);
        }

        List<HabousReferenceDay> days;
        try {
            days = toDays(parsed, year, month);
        } catch (DateTimeException e) {
            return new Record(cityId, entry.habousCityId(), year, month, Status.INCOMPLETE_TABLE,
                    "table contained an invalid day for the month: " + e.getMessage(), null, 0, attempts);
        }

        HabousReferenceMonth reference = new HabousReferenceMonth(
                cityId, entry.habousCityId(), entry.habousArabicName(), year, month,
                clock.instant(), HabousClient.monthlyUri(entry.habousCityId()).toString(),
                HabousSnapshotStore.sha256Hex(html), PARSER_VERSION, days);

        if (!reference.isComplete()) {
            return new Record(cityId, entry.habousCityId(), year, month, Status.INCOMPLETE_TABLE,
                    "table missing days: " + reference.missingDays(), reference.sourceChecksum(),
                    days.size(), attempts);
        }

        HabousSnapshotStore.SaveResult saved = store.save(reference, html);
        Status status = switch (saved.status()) {
            case CREATED -> Status.CREATED;
            case DUPLICATE -> Status.DUPLICATE;
            case REVISION_SAVED -> Status.REVISION_SAVED;
        };
        return new Record(cityId, entry.habousCityId(), year, month, status,
                saved.status().name().toLowerCase(java.util.Locale.ROOT) + " at " + saved.location(),
                saved.checksum(), days.size(), attempts);
    }

    private record FetchResult(String html, int attempts) {
    }

    private FetchResult fetchWithRetry(String habousCityId, LocalDate probe) {
        int maxAttempts = settings.maxRetries() + 1;
        HabousUnavailableException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            applyDelay();
            try {
                return new FetchResult(fetcher.fetchMonthlyHtml(habousCityId, probe), attempt);
            } catch (HabousUnavailableException e) {
                last = e;
            }
        }
        throw last != null ? last
                : new HabousUnavailableException("fetch failed for " + habousCityId);
    }

    private void applyDelay() {
        if (settings.requestDelay().isZero()) {
            return;
        }
        try {
            sleeper.sleep(settings.requestDelay());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HabousUnavailableException("interrupted during request delay", e);
        }
    }

    private List<HabousReferenceDay> toDays(List<HabousDay> parsed, int year, int month) {
        List<HabousReferenceDay> days = new ArrayList<>(parsed.size());
        for (HabousDay d : parsed) {
            LocalDate date = LocalDate.of(year, month, d.gregorianDay());
            days.add(new HabousReferenceDay(
                    date, d.fajr(), d.sunrise(), d.dhuhr(), d.asr(), d.maghrib(), d.isha()));
        }
        return days;
    }
}
