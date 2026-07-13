package io.github.cyonecoder.prayertime.audit.dataset;

import io.github.cyonecoder.prayertime.audit.dataset.HabousReferenceCollector.Sleeper;
import io.github.cyonecoder.prayertime.audit.dataset.HabousVilleDirectory.Option;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient.Fetcher;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousClient.HabousUnavailableException;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousLiveHtmlParser;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousLiveHtmlParser.HabousLiveDay;
import io.github.cyonecoder.prayertime.infrastructure.habous.HabousLiveHtmlParser.HabousParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Independently re-verifies each candidate city against the <em>live</em> Habous
 * source, following the fixed matching priority and assigning exactly one
 * {@link Status}. It approves a mapping ({@link Status#VERIFIED}) only when a
 * unique option is found, its numeric id is extracted, its Arabic name matches
 * under conservative normalization, its timetable fetches, contains the expected
 * Arabic name, parses, has coherent contiguous dates with all six time columns,
 * and a second independent fetch confirms the same table.
 *
 * <p>It never invents an id, never copies a nearby city's id, and never approves
 * on a fuzzy match. A candidate whose name does not match any option exactly is
 * {@link Status#NOT_LISTED_BY_HABOUS} (nothing to review) or
 * {@link Status#MANUAL_REVIEW_REQUIRED} (a prior proposal exists under a
 * different spelling) — never silently mapped.
 */
public final class HabousCityVerifier {

    /** The verification method recorded on an approved mapping. */
    public static final String METHOD = "live-select-and-timetable";

    /** The single status assigned to each candidate. */
    public enum Status {
        VERIFIED,
        NOT_LISTED_BY_HABOUS,
        AMBIGUOUS_NAME,
        TIMETABLE_UNAVAILABLE,
        INVALID_TIMETABLE,
        MANUAL_REVIEW_REQUIRED
    }

    /** A candidate to verify. */
    public record Candidate(
            String canonicalSlug, String sourceValue, String arabicName, List<String> aliases) {
        public Candidate {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    /**
     * The verification outcome for one candidate.
     *
     * @param candidate        the candidate
     * @param status           the assigned status
     * @param matchMethod      how the option was matched (or why not)
     * @param habousCityId     the matched ville id (null unless a unique option matched)
     * @param habousArabicName the matched option's exact Arabic name (null if unmatched)
     * @param dayCount         parsed timetable days (0 if not verified)
     * @param firstDate        first timetable date (null if none)
     * @param lastDate         last timetable date (null if none)
     * @param sourceChecksum   SHA-256 of the first timetable fetch (null if none)
     * @param confirmChecksum  SHA-256 of the confirming fetch (null if none)
     * @param confirmed        whether a second independent fetch confirmed the table
     * @param message          human explanation
     */
    public record Result(
            Candidate candidate,
            Status status,
            String matchMethod,
            String habousCityId,
            String habousArabicName,
            int dayCount,
            LocalDate firstDate,
            LocalDate lastDate,
            String sourceChecksum,
            String confirmChecksum,
            boolean confirmed,
            String message) {

        public boolean isVerified() {
            return status == Status.VERIFIED;
        }
    }

    private final Fetcher fetcher;
    private final HabousLiveHtmlParser parser;
    private final Clock clock;
    private final Duration requestDelay;
    private final Sleeper sleeper;

    public HabousCityVerifier(
            Fetcher fetcher, HabousLiveHtmlParser parser, Clock clock,
            Duration requestDelay, Sleeper sleeper) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.requestDelay = Objects.requireNonNull(requestDelay, "requestDelay");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /**
     * Verifies every candidate against the directory.
     *
     * @param candidates  the candidates (in inventory order)
     * @param directory   the live-parsed ville dropdown
     * @param verifiedIds slug to already-verified ville id (priority 2)
     * @param proposedIds slug to an unconfirmed prior ville id (routes to manual review)
     * @return one result per candidate, in input order
     */
    public List<Result> verifyAll(
            List<Candidate> candidates, HabousVilleDirectory directory,
            Map<String, String> verifiedIds, Map<String, String> proposedIds) {
        return candidates.stream()
                .map(c -> verify(c, directory, verifiedIds, proposedIds))
                .toList();
    }

    /** Verifies one candidate. */
    public Result verify(
            Candidate candidate, HabousVilleDirectory directory,
            Map<String, String> verifiedIds, Map<String, String> proposedIds) {
        // Priority 1 (exact normalized Arabic name) and 3 (exact known Arabic alias):
        // gather every option matched exactly, deduped by id. Latin source/aliases
        // cannot match the Arabic-only dropdown, so only Arabic names are tried.
        Option nameHit = uniqueOrNull(directory.matchByArabic(candidate.arabicName()));
        java.util.LinkedHashMap<String, Option> byId = new java.util.LinkedHashMap<>();
        String method = null;
        if (nameHit != null) {
            byId.put(nameHit.habousCityId(), nameHit);
            method = "arabic-name";
        } else if (directory.matchByArabic(candidate.arabicName()).size() > 1) {
            return unmatched(candidate, Status.AMBIGUOUS_NAME, "arabic-name",
                    "name matches multiple options: " + ids(directory.matchByArabic(candidate.arabicName())));
        }
        for (String alias : candidate.aliases()) {
            List<Option> hits = directory.matchByArabic(alias);
            if (hits.size() > 1) {
                return unmatched(candidate, Status.AMBIGUOUS_NAME, "arabic-alias",
                        "alias '" + alias + "' matches multiple options: " + ids(hits));
            }
            if (hits.size() == 1) {
                byId.putIfAbsent(hits.get(0).habousCityId(), hits.get(0));
                if (method == null) {
                    method = "arabic-alias";
                }
            }
        }
        if (byId.size() > 1) {
            return unmatched(candidate, Status.AMBIGUOUS_NAME, "arabic-name-or-alias",
                    "name/aliases resolve to multiple distinct options: " + byId.keySet());
        }
        if (byId.size() == 1) {
            return verifyTimetable(candidate, byId.values().iterator().next(), method);
        }

        // Priority 2: exact already-verified numeric id from an existing mapping.
        String verifiedId = verifiedIds.get(candidate.canonicalSlug());
        if (verifiedId != null) {
            Optional<Option> verifiedOption = directory.byId(verifiedId);
            if (verifiedOption.isPresent()) {
                return verifyTimetable(candidate, verifiedOption.get(), "existing-verified-id");
            }
        }

        // Priority 3 (Latin source/alias) cannot match the Arabic-only dropdown, so
        // fall through. A prior *unconfirmed* proposal that exists in the dropdown is
        // a name discrepancy that needs a human — never auto-approved.
        String proposed = proposedIds.get(candidate.canonicalSlug());
        if (proposed != null && directory.byId(proposed).isPresent()) {
            Option o = directory.byId(proposed).orElseThrow();
            return unmatched(candidate, Status.MANUAL_REVIEW_REQUIRED, "name-discrepancy",
                    "prior proposal ville=" + proposed + " (" + o.exactArabicName()
                            + ") does not match candidate name '" + candidate.arabicName() + "'");
        }
        return unmatched(candidate, Status.NOT_LISTED_BY_HABOUS, "none",
                "no dropdown option matches the candidate Arabic name");
    }

    private Result verifyTimetable(Candidate candidate, Option option, String method) {
        String id = option.habousCityId();
        LocalDate today = LocalDate.now(clock);

        String html;
        try {
            html = fetch(id, today);
        } catch (HabousUnavailableException e) {
            return matched(candidate, option, method, Status.TIMETABLE_UNAVAILABLE, 0, null, null,
                    null, null, false, "timetable fetch failed: " + e.getMessage());
        }

        // The returned page must be for the expected city.
        if (!ArabicText.normalize(html).contains(option.normalizedArabic())) {
            return matched(candidate, option, method, Status.INVALID_TIMETABLE, 0, null, null,
                    HabousSnapshotStore.sha256Hex(html), null, false,
                    "page did not contain expected Arabic name " + option.exactArabicName());
        }

        List<HabousLiveDay> days;
        try {
            days = parser.parse(html);
        } catch (HabousParseException e) {
            return matched(candidate, option, method, Status.INVALID_TIMETABLE, 0, null, null,
                    HabousSnapshotStore.sha256Hex(html), null, false,
                    "timetable did not parse: " + e.getMessage());
        }
        String coherence = incoherence(days);
        if (coherence != null) {
            return matched(candidate, option, method, Status.INVALID_TIMETABLE, days.size(),
                    days.get(0).date(), days.get(days.size() - 1).date(),
                    HabousSnapshotStore.sha256Hex(html), null, false, coherence);
        }

        String sourceChecksum = HabousSnapshotStore.sha256Hex(html);

        // Second independent fetch must reproduce the same table.
        String confirmChecksum = null;
        boolean confirmed = false;
        try {
            String html2 = fetch(id, today);
            confirmChecksum = HabousSnapshotStore.sha256Hex(html2);
            List<HabousLiveDay> days2 = parser.parse(html2);
            confirmed = days2.equals(days);
        } catch (HabousUnavailableException | HabousParseException e) {
            confirmed = false;
        }
        if (!confirmed) {
            return matched(candidate, option, method, Status.MANUAL_REVIEW_REQUIRED, days.size(),
                    days.get(0).date(), days.get(days.size() - 1).date(), sourceChecksum,
                    confirmChecksum, false, "second fetch did not reproduce the same table");
        }
        return matched(candidate, option, method, Status.VERIFIED, days.size(),
                days.get(0).date(), days.get(days.size() - 1).date(), sourceChecksum,
                confirmChecksum, true, "verified against live select and timetable");
    }

    /** @return null if the days are coherent, else a description of the problem. */
    private static String incoherence(List<HabousLiveDay> days) {
        if (days.isEmpty()) {
            return "timetable has no day rows";
        }
        List<LocalDate> dates = days.stream().map(HabousLiveDay::date).sorted().distinct().toList();
        if (dates.size() != days.size()) {
            return "timetable has duplicate dates";
        }
        long span = dates.get(dates.size() - 1).toEpochDay() - dates.get(0).toEpochDay() + 1;
        if (span != days.size()) {
            return "timetable dates are not contiguous";
        }
        for (HabousLiveDay d : days) {
            if (d.fajr() == null || d.sunrise() == null || d.dhuhr() == null
                    || d.asr() == null || d.maghrib() == null || d.isha() == null) {
                return "timetable row missing a time column";
            }
        }
        return null;
    }

    private String fetch(String id, LocalDate date) {
        applyDelay();
        return fetcher.fetchMonthlyHtml(id, date);
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

    private static Option uniqueOrNull(List<Option> options) {
        return options.size() == 1 ? options.get(0) : null;
    }

    private static String ids(List<Option> options) {
        return options.stream().map(Option::habousCityId).toList().toString();
    }

    private static Result unmatched(
            Candidate c, Status status, String method, String message) {
        return new Result(c, status, method, null, null, 0, null, null, null, null, false, message);
    }

    private static Result matched(
            Candidate c, Option o, String method, Status status, int dayCount,
            LocalDate first, LocalDate last, String sourceChecksum, String confirmChecksum,
            boolean confirmed, String message) {
        return new Result(c, status, method, o.habousCityId(), o.exactArabicName(),
                dayCount, first, last, sourceChecksum, confirmChecksum, confirmed, message);
    }
}
