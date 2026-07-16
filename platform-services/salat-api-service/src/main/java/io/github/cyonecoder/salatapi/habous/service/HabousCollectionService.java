package io.github.cyonecoder.salatapi.habous.service;

import io.github.cyonecoder.salatapi.archive.model.ArchiveFailureCode;
import io.github.cyonecoder.salatapi.archive.repository.RawHabousSnapshotRepository;
import io.github.cyonecoder.salatapi.archive.service.GregorianArchiveAssembler.ObservedPage;
import io.github.cyonecoder.salatapi.city.CitySupportService;
import io.github.cyonecoder.salatapi.habous.client.HabousClient;
import io.github.cyonecoder.salatapi.habous.client.HabousUnavailableException;
import io.github.cyonecoder.salatapi.habous.client.NetworkFailureCategory;
import io.github.cyonecoder.salatapi.habous.model.CityCatalogueEntry;
import io.github.cyonecoder.salatapi.habous.model.HabousCitySyncResult;
import io.github.cyonecoder.salatapi.habous.model.HabousCitySyncResult.Status;
import io.github.cyonecoder.salatapi.habous.model.HabousPageSnapshot;
import io.github.cyonecoder.salatapi.habous.model.HabousPublishedDay;
import io.github.cyonecoder.salatapi.habous.parser.ArabicTextNormalizer;
import io.github.cyonecoder.salatapi.habous.parser.HabousLiveHtmlParser;
import io.github.cyonecoder.salatapi.habous.parser.HabousParseException;
import io.github.cyonecoder.salatapi.habous.parser.HabousVilleDirectoryParser;
import io.github.cyonecoder.salatapi.habous.validation.HabousTimetableValidator;
import io.github.cyonecoder.salatapi.habous.validation.HabousValidationReport;
import io.github.cyonecoder.salatapi.config.HabousProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Collects one official city at a time: fetch, validate the city, parse, validate
 * the timetable, and store the raw source (spec 20).
 *
 * <p><strong>Failures are isolated per city.</strong> Each city is collected in
 * its own try/catch; a fetch outage, a wrong-city response or an unparseable page
 * for one ville produces a failed {@link HabousCitySyncResult} and the run
 * continues. Nothing here erases another city's data.
 *
 * <p>A configurable delay is applied <em>between</em> cities to stay respectful of
 * the source; the delay is skipped after the last city.
 */
@Service
public class HabousCollectionService {

    private static final Logger log = LoggerFactory.getLogger(HabousCollectionService.class);

    /**
     * Everything one sync run produced: the observed pages (for assembly) and the
     * per-city outcomes (for the archive's failure list and the sync report).
     *
     * @param pages   the pages that parsed and validated, ready to assemble
     * @param results one outcome per attempted city
     */
    public record CollectionOutcome(List<ObservedPage> pages, List<HabousCitySyncResult> results) {
        public CollectionOutcome {
            pages = List.copyOf(pages);
            results = List.copyOf(results);
        }
    }

    private final HabousClient client;
    private final HabousLiveHtmlParser timetableParser;
    private final HabousVilleDirectoryParser villeParser;
    private final HabousTimetableValidator validator;
    private final RawHabousSnapshotRepository rawSnapshots;
    private final CitySupportService citySupport;
    private final HabousProperties properties;
    private final Clock clock;

    public HabousCollectionService(
            HabousClient client,
            HabousLiveHtmlParser timetableParser,
            HabousVilleDirectoryParser villeParser,
            HabousTimetableValidator validator,
            RawHabousSnapshotRepository rawSnapshots,
            CitySupportService citySupport,
            HabousProperties properties,
            Clock clock) {
        this.client = client;
        this.timetableParser = timetableParser;
        this.villeParser = villeParser;
        this.validator = validator;
        this.rawSnapshots = rawSnapshots;
        this.citySupport = citySupport;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Collects every enabled official city in turn.
     *
     * @param enabledCities the present catalogue entries to collect
     * @return the observed pages and per-city outcomes
     */
    public CollectionOutcome collectAll(List<CityCatalogueEntry> enabledCities) {
        Objects.requireNonNull(enabledCities, "enabledCities");
        List<ObservedPage> pages = new ArrayList<>();
        List<HabousCitySyncResult> results = new ArrayList<>();

        java.util.Map<NetworkFailureCategory, Integer> failureCounts =
                new java.util.EnumMap<>(NetworkFailureCategory.class);

        for (int i = 0; i < enabledCities.size(); i++) {
            CityCatalogueEntry city = enabledCities.get(i);
            CityCollection collection = collectOne(city);
            results.add(collection.result());
            collection.page().ifPresent(pages::add);
            collection.networkCategory().ifPresent(
                    category -> failureCounts.merge(category, 1, Integer::sum));

            if (i < enabledCities.size() - 1) {
                pause(properties.requestDelay());
            }
        }

        log.info("Collection complete: {} cities attempted, {} pages stored",
                results.size(), pages.size());
        logFailureSummary(failureCounts);
        return new CollectionOutcome(pages, results);
    }

    /** One grouped line per failure category, so a shared cause is obvious at a glance. */
    private void logFailureSummary(java.util.Map<NetworkFailureCategory, Integer> failureCounts) {
        int total = failureCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return;
        }
        StringBuilder summary = new StringBuilder(total + " cities failed");
        failureCounts.entrySet().stream()
                .sorted(java.util.Map.Entry.<NetworkFailureCategory, Integer>comparingByValue().reversed())
                .forEach(e -> summary.append("\n - ").append(e.getKey()).append(": ").append(e.getValue()));
        log.warn("{}", summary);
    }

    /** Collects one city, never throwing: any failure becomes a failed result. */
    CityCollection collectOne(CityCatalogueEntry city) {
        String villeId = city.habousCityId();
        String html;
        try {
            html = client.fetchCurrentPublishedTimetable(villeId);
        } catch (HabousUnavailableException e) {
            // Concise warning per city; the full stack goes to debug so one shared
            // cause across 191 cities does not print 191 stack traces.
            log.warn("Fetch failed for ville {} ({}) [{}]: {}",
                    villeId, city.exactArabicName(), e.category(), e.getMessage());
            log.debug("Fetch failure detail for ville {}", villeId, e);
            return CityCollection.failed(
                    HabousCitySyncResult.failed(villeId, Status.FETCH_FAILED,
                            ArchiveFailureCode.TIMETABLE_UNAVAILABLE, e.getMessage()),
                    e.category());
        }

        // Validate the city name: the official page marks the served city as the
        // selected option. A mismatch means the site returned a different city, and
        // storing it under the requested id would silently corrupt the archive.
        Optional<HabousVilleDirectoryParser.Option> selected = villeParser.selectedVille(html);
        if (selected.isPresent() && !matches(selected.get(), city)) {
            log.warn("Ville {} returned a different city (expected '{}', got '{}')",
                    villeId, city.exactArabicName(), selected.get().exactArabicName());
            return CityCollection.failed(HabousCitySyncResult.failed(
                    villeId, Status.WRONG_CITY, ArchiveFailureCode.PAGE_UNPARSEABLE,
                    "expected " + city.exactArabicName() + " but page was " + selected.get().exactArabicName()));
        }

        List<HabousPublishedDay> days;
        try {
            days = timetableParser.parse(html);
        } catch (HabousParseException e) {
            log.warn("Unparseable timetable for ville {}: {}", villeId, e.getMessage());
            return CityCollection.failed(HabousCitySyncResult.failed(
                    villeId, Status.INVALID_PAGE, ArchiveFailureCode.PAGE_UNPARSEABLE, e.getMessage()));
        }

        HabousValidationReport report = validator.validate(days);
        if (!report.valid()) {
            log.warn("Invalid timetable for ville {}: {}", villeId, report.problems());
            return CityCollection.failed(HabousCitySyncResult.failed(
                    villeId, Status.INVALID_PAGE, ArchiveFailureCode.PAGE_UNPARSEABLE,
                    String.join("; ", report.problems())));
        }

        HabousPageSnapshot metadata = metadataFor(city, villeId, days);
        RawHabousSnapshotRepository.SaveResult saved = rawSnapshots.save(html, days, metadata);
        Status status = switch (saved.status()) {
            case CREATED -> Status.COLLECTED;
            case DUPLICATE -> Status.UNCHANGED;
            case REVISION_SAVED -> Status.REVISED;
        };
        return new CityCollection(
                HabousCitySyncResult.collected(villeId, status),
                Optional.of(new ObservedPage(metadata, days)),
                Optional.empty());
    }

    private HabousPageSnapshot metadataFor(
            CityCatalogueEntry city, String villeId, List<HabousPublishedDay> days) {
        LocalDate today = LocalDate.now(clock);
        Instant now = clock.instant();
        CitySupportService.CitySupport support = citySupport.supportFor(villeId);

        List<LocalDate> dates = days.stream().map(HabousPublishedDay::date).sorted().toList();
        List<String> months = new ArrayList<>(new TreeSet<>(
                days.stream().map(d -> YearMonth.from(d.date()).toString()).toList()));

        return new HabousPageSnapshot(
                villeId,
                city.exactArabicName(),
                support.citySlug().orElse(null),
                support.secApproachSupported(),
                today,
                now,
                properties.baseUrl() + "?ville=" + villeId,
                "recomputed-on-save",
                HabousLiveHtmlParser.PARSER_VERSION,
                days.size(),
                dates.get(0),
                dates.get(dates.size() - 1),
                months);
    }

    private boolean matches(HabousVilleDirectoryParser.Option selected, CityCatalogueEntry city) {
        if (selected.habousCityId().equals(city.habousCityId())) {
            return true;
        }
        // Fall back to a diacritic-insensitive name match, in case the id form differs.
        return ArabicTextNormalizer.fold(selected.exactArabicName())
                .equals(ArabicTextNormalizer.fold(city.exactArabicName()));
    }

    private void pause(Duration delay) {
        if (delay == null || delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One city's collection: its result, and the page to assemble if it succeeded. */
    record CityCollection(
            HabousCitySyncResult result,
            Optional<ObservedPage> page,
            Optional<NetworkFailureCategory> networkCategory) {
        static CityCollection failed(HabousCitySyncResult result) {
            return new CityCollection(result, Optional.empty(), Optional.empty());
        }

        static CityCollection failed(HabousCitySyncResult result, NetworkFailureCategory category) {
            return new CityCollection(result, Optional.empty(), Optional.of(category));
        }
    }
}
