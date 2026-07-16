package io.github.cyonecoder.salatapi.archive.service;

import io.github.cyonecoder.salatapi.archive.model.ArchiveFailureCode;
import io.github.cyonecoder.salatapi.archive.model.ArchivedDay;
import io.github.cyonecoder.salatapi.archive.model.CityArchiveStatus;
import io.github.cyonecoder.salatapi.archive.model.GregorianMonthlyArchive;
import io.github.cyonecoder.salatapi.archive.model.MonthlyArchiveStatus;
import io.github.cyonecoder.salatapi.archive.model.MonthlyCitySchedule;
import io.github.cyonecoder.salatapi.city.CitySupportService;
import io.github.cyonecoder.salatapi.habous.model.CityCatalogueEntry;
import io.github.cyonecoder.salatapi.habous.model.HabousPageSnapshot;
import io.github.cyonecoder.salatapi.habous.model.HabousPublishedDay;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Assembles Gregorian monthly archives from official pages (spec 16 and 17).
 *
 * <p>The official timetable is <strong>Hijri</strong>-month based, so one page
 * routinely carries dates from two Gregorian months (e.g. 2026-06-17 through
 * 2026-07-16). Every row is filed by <em>its own resolved Gregorian date</em>, so
 * one source page is split across two archives — {@code 2026-06} and
 * {@code 2026-07}.
 *
 * <p><strong>Rows are never assigned to the month the page was fetched in.</strong>
 * A page fetched on 2026-07-01 contributes fourteen days to June and sixteen to
 * July; filing it wholesale under July would misdate half the month by weeks.
 *
 * <p>Deduplication is by {@code habousCityId + Gregorian date}. When observations
 * disagree, the more recently fetched one wins — it is the source's latest word —
 * while {@code firstObservedAt} still records when the row was first seen.
 *
 * <p>The archive is built from the <strong>official catalogue</strong>, not from
 * whatever happened to be collected: every catalogue city appears, and the ones
 * with no usable days are listed as {@link CityArchiveStatus#UNAVAILABLE} with a
 * failure code rather than being silently omitted.
 */
@Service
public class GregorianArchiveAssembler {

    private static final Logger log = LoggerFactory.getLogger(GregorianArchiveAssembler.class);

    /**
     * One observed page: its provenance and the days parsed from it.
     *
     * @param metadata the page's provenance, including the fetch instant
     * @param days     the days parsed from the page, each with a resolved date
     */
    public record ObservedPage(HabousPageSnapshot metadata, List<HabousPublishedDay> days) {
        public ObservedPage {
            Objects.requireNonNull(metadata, "metadata");
            days = days == null ? List.of() : List.copyOf(days);
        }
    }

    private final CitySupportService citySupport;
    private final Clock clock;

    public GregorianArchiveAssembler(CitySupportService citySupport, Clock clock) {
        this.citySupport = citySupport;
        this.clock = clock;
    }

    /**
     * @param pages every observed page to consider
     * @return the Gregorian months those pages contribute to, ascending
     */
    public List<YearMonth> monthsCovered(List<ObservedPage> pages) {
        Set<YearMonth> months = new TreeSet<>();
        for (ObservedPage page : pages) {
            page.days().forEach(day -> months.add(YearMonth.from(day.date())));
        }
        return List.copyOf(months);
    }

    /**
     * Assembles one Gregorian month.
     *
     * @param month              the Gregorian month to assemble
     * @param pages              every observed page; only rows falling inside {@code month} are taken
     * @param officialCities     the current official catalogue — every one of these appears in the result
     * @param failures           why particular cities have no data, keyed by ville id
     * @param originalGeneratedAt when the archive was first assembled, if it already exists
     * @return the assembled archive, typically partial
     */
    public GregorianMonthlyArchive assemble(
            YearMonth month,
            List<ObservedPage> pages,
            List<CityCatalogueEntry> officialCities,
            Map<String, ArchiveFailureCode> failures,
            Instant originalGeneratedAt) {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(officialCities, "officialCities");

        Map<String, Map<LocalDate, Observation>> observedByCity = collectRows(month, pages);
        Map<String, Set<String>> checksumsByCity = collectChecksums(month, pages);

        Instant now = clock.instant();
        int daysExpected = month.lengthOfMonth();
        List<MonthlyCitySchedule> cities = new ArrayList<>();

        for (CityCatalogueEntry official : officialCities) {
            String villeId = official.habousCityId();
            CitySupportService.CitySupport support = citySupport.supportFor(villeId);
            String applicationCityId = support.citySlug().orElse(null);
            boolean secApproachSupported = support.secApproachSupported();

            Map<LocalDate, Observation> rows = observedByCity.getOrDefault(villeId, Map.of());
            if (rows.isEmpty()) {
                // Listed, not omitted: an absent city is indistinguishable from one
                // that was never official, whereas a declared failure is auditable.
                cities.add(new MonthlyCitySchedule(
                        villeId, official.exactArabicName(), applicationCityId, secApproachSupported,
                        CityArchiveStatus.UNAVAILABLE, daysExpected, 0, List.of(),
                        failures.getOrDefault(villeId, ArchiveFailureCode.NOT_YET_COLLECTED),
                        List.of()));
                continue;
            }

            List<ArchivedDay> days = rows.values().stream()
                    .map(Observation::toArchivedDay)
                    .sorted(Comparator.comparing(ArchivedDay::date))
                    .toList();
            CityArchiveStatus status = days.size() == daysExpected
                    ? CityArchiveStatus.COMPLETE
                    : CityArchiveStatus.PARTIAL;
            cities.add(new MonthlyCitySchedule(
                    villeId, official.exactArabicName(), applicationCityId, secApproachSupported,
                    status, daysExpected, days.size(), days, null,
                    checksumsByCity.getOrDefault(villeId, Set.of()).stream().sorted().toList()));
        }

        cities.sort(Comparator.comparing(c -> numericOrMax(c.habousCityId())));

        long complete = cities.stream().filter(MonthlyCitySchedule::isComplete).count();
        long failed = cities.stream().filter(MonthlyCitySchedule::isFailed).count();
        long partial = cities.size() - complete - failed;

        MonthlyArchiveStatus status;
        if (cities.isEmpty()) {
            status = MonthlyArchiveStatus.EMPTY;
        } else if (complete == cities.size()) {
            status = MonthlyArchiveStatus.COMPLETE;
        } else {
            status = MonthlyArchiveStatus.PARTIAL;
        }

        return new GregorianMonthlyArchive(
                GregorianMonthlyArchive.SCHEMA_VERSION,
                GregorianMonthlyArchive.SOURCE_OFFICIAL,
                String.format("%04d-%02d", month.getYear(), month.getMonthValue()),
                GregorianMonthlyArchive.PERIOD_TYPE_GREGORIAN_MONTH,
                GregorianMonthlyArchive.TIMEZONE,
                status,
                originalGeneratedAt == null ? now : originalGeneratedAt,
                now,
                officialCities.size(),
                cities.size(),
                complete,
                partial,
                failed,
                cities);
    }

    /** Groups every row falling inside {@code month} by city and date, newest observation winning. */
    private Map<String, Map<LocalDate, Observation>> collectRows(
            YearMonth month, List<ObservedPage> pages) {
        Map<String, Map<LocalDate, Observation>> byCity = new LinkedHashMap<>();
        for (ObservedPage page : pages) {
            String cityId = page.metadata().habousCityId();
            Instant fetchedAt = page.metadata().fetchedAt();
            String checksum = page.metadata().sourceChecksum();
            for (HabousPublishedDay day : page.days()) {
                // The row's own date decides its month — not the fetch date.
                if (!YearMonth.from(day.date()).equals(month)) {
                    continue;
                }
                Map<LocalDate, Observation> days = byCity.computeIfAbsent(cityId, k -> new TreeMap<>());
                Observation existing = days.get(day.date());
                if (existing == null) {
                    days.put(day.date(), new Observation(day, checksum, fetchedAt, fetchedAt));
                } else {
                    days.put(day.date(), existing.merge(day, checksum, fetchedAt, cityId, log));
                }
            }
        }
        return byCity;
    }

    private Map<String, Set<String>> collectChecksums(YearMonth month, List<ObservedPage> pages) {
        Map<String, Set<String>> byCity = new LinkedHashMap<>();
        for (ObservedPage page : pages) {
            boolean contributes = page.days().stream()
                    .anyMatch(day -> YearMonth.from(day.date()).equals(month));
            if (contributes) {
                byCity.computeIfAbsent(page.metadata().habousCityId(), k -> new LinkedHashSet<>())
                        .add(page.metadata().sourceChecksum());
            }
        }
        return byCity;
    }

    private static long numericOrMax(String villeId) {
        try {
            return Long.parseLong(villeId);
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    /** One city/date row, carrying the winning times and the observation window. */
    private record Observation(
            HabousPublishedDay day, String checksum, Instant firstObservedAt, Instant lastObservedAt) {

        /** Folds a new sighting of the same city/date into this one. */
        Observation merge(HabousPublishedDay incoming, String incomingChecksum, Instant fetchedAt,
                String cityId, Logger log) {
            Instant first = fetchedAt.isBefore(firstObservedAt) ? fetchedAt : firstObservedAt;
            Instant last = fetchedAt.isAfter(lastObservedAt) ? fetchedAt : lastObservedAt;
            boolean newer = fetchedAt.isAfter(lastObservedAt);
            if (newer && !incoming.equals(day)) {
                // A silent official correction is worth noticing, not hiding.
                log.warn("City {} {}: official times changed between observations; "
                        + "taking the newer one fetched at {}", cityId, incoming.date(), fetchedAt);
            }
            return newer
                    ? new Observation(incoming, incomingChecksum, first, last)
                    : new Observation(day, checksum, first, last);
        }

        ArchivedDay toArchivedDay() {
            return new ArchivedDay(
                    day.date(), day.fajr(), day.sunrise(), day.dhuhr(), day.asr(),
                    day.maghrib(), day.isha(), checksum, firstObservedAt, lastObservedAt);
        }
    }
}
