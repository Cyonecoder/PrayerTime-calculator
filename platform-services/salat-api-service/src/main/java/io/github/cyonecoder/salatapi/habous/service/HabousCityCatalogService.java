package io.github.cyonecoder.salatapi.habous.service;

import io.github.cyonecoder.salatapi.archive.io.AtomicJsonFileWriter;
import io.github.cyonecoder.salatapi.city.OfficialHabousCityDirectory;
import io.github.cyonecoder.salatapi.config.SalatApiProperties;
import io.github.cyonecoder.salatapi.habous.model.CatalogueDiff;
import io.github.cyonecoder.salatapi.habous.model.CityCatalogue;
import io.github.cyonecoder.salatapi.habous.model.CityCatalogueEntry;
import io.github.cyonecoder.salatapi.habous.model.HabousVille;
import io.github.cyonecoder.salatapi.habous.parser.ArabicTextNormalizer;
import io.github.cyonecoder.salatapi.habous.parser.HabousVilleDirectoryParser;
import io.github.cyonecoder.salatapi.habous.parser.HabousVilleDirectoryParser.Directory;
import io.github.cyonecoder.salatapi.habous.parser.HabousVilleDirectoryParser.Option;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Maintains the runtime catalogue of official Habous cities (spec 12).
 *
 * <p>On every daily synchronization the official dropdown is re-parsed and
 * compared against what is already known — the bundled 191-city bootstrap index
 * on the first run, the stored runtime catalogue thereafter. New, removed,
 * renamed and duplicated cities are all detected and reported.
 *
 * <p><strong>Historical city data is never silently deleted.</strong> A city that
 * disappears from the dropdown is marked absent and retained, because raw
 * snapshots collected under its id still exist and must stay explicable.
 *
 * <p>The catalogue's count is observed, not assumed: it may legitimately differ
 * from 191 once the official source changes.
 */
@Service
public class HabousCityCatalogService {

    private static final Logger log = LoggerFactory.getLogger(HabousCityCatalogService.class);
    private static final DateTimeFormatter HISTORY_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC);

    /** The outcome of one refresh attempt. */
    public record RefreshResult(CityCatalogue catalogue, CatalogueDiff diff, boolean stored) {}

    private final OfficialHabousCityDirectory bootstrap;
    private final HabousVilleDirectoryParser parser;
    private final AtomicJsonFileWriter jsonFiles;
    private final SalatApiProperties properties;
    private final Clock clock;

    private volatile CityCatalogue current;

    public HabousCityCatalogService(
            OfficialHabousCityDirectory bootstrap,
            HabousVilleDirectoryParser parser,
            AtomicJsonFileWriter jsonFiles,
            SalatApiProperties properties,
            Clock clock) {
        this.bootstrap = bootstrap;
        this.parser = parser;
        this.jsonFiles = jsonFiles;
        this.properties = properties;
        this.clock = clock;
        this.current = loadStoredOrBootstrap();
    }

    /**
     * @return the catalogue as currently known: the stored runtime catalogue if a
     *         refresh has ever succeeded, otherwise the bundled bootstrap index
     */
    public CityCatalogue current() {
        return current;
    }

    /** @return the number of cities the official source currently publishes; never hard-coded */
    public int observedCount() {
        return current.observedCount();
    }

    /** @return the ville ids eligible for official collection right now */
    public List<String> collectableVilleIds() {
        return current.collectableVilleIds();
    }

    /**
     * Refreshes the catalogue from a freshly fetched official page.
     *
     * @param html a Habous page carrying {@code <select name="ville">}
     * @return the refreshed catalogue and what changed
     * @throws io.github.cyonecoder.salatapi.habous.parser.HabousParseException
     *         if the page carries no usable dropdown — the previous catalogue is
     *         then kept rather than being replaced by an empty one
     */
    public RefreshResult refresh(String html) {
        Directory observed = parser.parse(html);
        Instant now = clock.instant();

        CatalogueDiff diff = diff(current, observed);
        CityCatalogue refreshed = merge(current, observed, now);

        // Archive the outgoing catalogue before replacing it. The official source
        // publishes only "now", so what it said yesterday is unrecoverable unless
        // it was kept.
        archivePrevious(diff);
        jsonFiles.write(properties.currentCatalogFile(), refreshed);
        this.current = refreshed;

        if (diff.hasChanges()) {
            log.warn(
                    "Official city catalogue changed: {} new, {} removed, {} renamed, {} duplicate ids, {} duplicate labels (now {} cities)",
                    diff.newCities().size(), diff.removedCities().size(), diff.renamedCities().size(),
                    diff.duplicateIds().size(), diff.duplicateLabels().size(), refreshed.observedCount());
        } else {
            log.info("Official city catalogue unchanged: {} cities", refreshed.observedCount());
        }
        return new RefreshResult(refreshed, diff, true);
    }

    /** Compares a fresh observation against what is already known. */
    CatalogueDiff diff(CityCatalogue known, Directory observed) {
        Map<String, CityCatalogueEntry> knownById = new LinkedHashMap<>();
        known.presentEntries().forEach(e -> knownById.put(e.habousCityId(), e));

        List<String> duplicateIds = new ArrayList<>();
        Map<String, List<String>> idsByLabel = new TreeMap<>();
        Set<String> seenIds = new LinkedHashSet<>();
        List<String> newCities = new ArrayList<>();
        List<CatalogueDiff.Rename> renamed = new ArrayList<>();

        for (Option option : observed.options()) {
            if (!seenIds.add(option.habousCityId())) {
                duplicateIds.add(option.habousCityId());
            }
            idsByLabel.computeIfAbsent(option.exactArabicName(), k -> new ArrayList<>())
                    .add(option.habousCityId());

            CityCatalogueEntry existing = knownById.get(option.habousCityId());
            if (existing == null) {
                newCities.add(option.habousCityId());
            } else if (!existing.exactArabicName().equals(option.exactArabicName())) {
                renamed.add(new CatalogueDiff.Rename(
                        option.habousCityId(), existing.exactArabicName(), option.exactArabicName()));
            }
        }

        List<String> removed = knownById.keySet().stream()
                .filter(id -> !seenIds.contains(id))
                .toList();

        List<CatalogueDiff.DuplicateLabel> duplicateLabels = idsByLabel.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> new CatalogueDiff.DuplicateLabel(e.getKey(), List.copyOf(e.getValue())))
                .toList();

        return new CatalogueDiff(
                List.copyOf(newCities), removed, List.copyOf(renamed),
                List.copyOf(duplicateIds), duplicateLabels);
    }

    /** Merges an observation into the known catalogue, retaining every historical entry. */
    private CityCatalogue merge(CityCatalogue known, Directory observed, Instant now) {
        Map<String, CityCatalogueEntry> merged = new LinkedHashMap<>();
        // Start from everything ever known, marked absent; the observation revives
        // whatever it still contains. Nothing is dropped.
        for (CityCatalogueEntry entry : known.entries()) {
            merged.put(entry.habousCityId(), entry.markedAbsent());
        }
        for (Option option : observed.options()) {
            CityCatalogueEntry existing = merged.get(option.habousCityId());
            if (existing == null) {
                merged.put(option.habousCityId(), new CityCatalogueEntry(
                        option.habousCityId(), option.exactArabicName(), option.normalizedArabic(),
                        observed.sourceChecksum(), now, now, true, List.of()));
            } else {
                merged.put(option.habousCityId(), existing.seenAgain(
                        option.exactArabicName(), option.normalizedArabic(),
                        observed.sourceChecksum(), now));
            }
        }
        return new CityCatalogue(
                now, observed.sourceChecksum(), HabousVilleDirectoryParser.PARSER_VERSION,
                List.copyOf(merged.values()));
    }

    /**
     * Copies the current catalogue into {@code catalog/history/} before it is
     * replaced, but only when something actually changed — an unchanged daily
     * refresh would otherwise add a near-identical file every single day.
     */
    private void archivePrevious(CatalogueDiff diff) {
        if (!diff.hasChanges() || "bootstrap".equals(current.sourceChecksum())) {
            return;
        }
        Path stamped = properties.catalogHistoryDirectory()
                .resolve("habous-villes-" + HISTORY_STAMP.format(current.refreshedAt()) + ".json");
        jsonFiles.write(stamped, current);
        log.info("Archived the superseded city catalogue to {}", stamped);
    }

    private CityCatalogue loadStoredOrBootstrap() {
        Path file = properties.currentCatalogFile();
        if (Files.isRegularFile(file)) {
            try {
                CityCatalogue stored = jsonFiles.read(file, CityCatalogue.class);
                log.info("Loaded runtime city catalogue: {} cities observed (refreshed {})",
                        stored.observedCount(), stored.refreshedAt());
                return stored;
            } catch (RuntimeException e) {
                // A corrupt catalogue must not take the service down: fall back to
                // the bundled index and let the next refresh rewrite it.
                log.error("Runtime city catalogue at {} is unreadable; falling back to the bundled "
                        + "bootstrap index. It will be rewritten on the next refresh.", file, e);
            }
        }
        return fromBootstrap();
    }

    private CityCatalogue fromBootstrap() {
        Instant now = clock.instant();
        List<CityCatalogueEntry> entries = new ArrayList<>();
        for (HabousVille ville : bootstrap.all()) {
            entries.add(new CityCatalogueEntry(
                    ville.habousCityId(),
                    ville.habousArabicName(),
                    ArabicTextNormalizer.normalize(ville.habousArabicName()),
                    "bootstrap",
                    now,
                    now,
                    true,
                    List.of()));
        }
        log.info("No runtime city catalogue yet; bootstrapping from the bundled index ({} cities)",
                entries.size());
        return new CityCatalogue(now, "bootstrap", HabousVilleDirectoryParser.PARSER_VERSION, entries);
    }

    /** @param habousCityId a numeric ville id */
    public Optional<CityCatalogueEntry> findById(String habousCityId) {
        return current.byId(habousCityId);
    }
}
