package io.github.cyonecoder.prayertime.application.provider;

import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Compares a freshly-fetched candidate Habous snapshot against the currently
 * accepted one and produces a <em>review artifact</em>. It never mutates the
 * accepted snapshot, never changes production output, and never auto-updates:
 * adopting a candidate is a manual, reviewed step (see docs/HABOUS_DATA_REVIEW.md).
 */
public final class HabousSnapshotReview {

    /** One changed cell between accepted and candidate. */
    public record Changed(LocalDate date, Prayer prayer, String accepted, String candidate) {
    }

    /** The diff between accepted and candidate snapshots for a city. */
    public record Review(
            String cityId,
            String acceptedChecksum,
            String candidateChecksum,
            List<LocalDate> addedDates,
            List<LocalDate> removedDates,
            List<Changed> changedCells) {

        /** @return whether the candidate is identical to the accepted snapshot. */
        public boolean identical() {
            return addedDates.isEmpty() && removedDates.isEmpty() && changedCells.isEmpty();
        }

        /** @return whether a human review is required (any difference at all). */
        public boolean requiresApproval() {
            return !identical();
        }
    }

    /**
     * Reviews a candidate against the accepted snapshot.
     *
     * @param accepted  the currently accepted snapshot (authoritative)
     * @param candidate a freshly-parsed candidate for the same city
     * @return the review artifact
     */
    public Review review(OfficialSnapshot accepted, OfficialSnapshot candidate) {
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(candidate, "candidate");

        TreeSet<LocalDate> acceptedDates = new TreeSet<>(accepted.days().keySet());
        TreeSet<LocalDate> candidateDates = new TreeSet<>(candidate.days().keySet());

        List<LocalDate> added = new ArrayList<>(candidateDates);
        added.removeAll(acceptedDates);
        List<LocalDate> removed = new ArrayList<>(acceptedDates);
        removed.removeAll(candidateDates);

        List<Changed> changed = new ArrayList<>();
        TreeSet<LocalDate> common = new TreeSet<>(acceptedDates);
        common.retainAll(candidateDates);
        for (LocalDate date : common) {
            PrayerTimes a = accepted.days().get(date);
            PrayerTimes c = candidate.days().get(date);
            for (Prayer p : Prayer.values()) {
                String av = OfficialSnapshot.timeFor(a, p).toString();
                String cv = OfficialSnapshot.timeFor(c, p).toString();
                if (!av.equals(cv)) {
                    changed.add(new Changed(date, p, av, cv));
                }
            }
        }
        return new Review(accepted.cityId(), accepted.sourceChecksum(), candidate.sourceChecksum(),
                List.copyOf(added), List.copyOf(removed), List.copyOf(changed));
    }

    /** Renders the review as a human-readable artifact for the approval process. */
    public String render(Review r) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "Habous snapshot review — city '%s'%n", r.cityId()));
        sb.append("accepted checksum:  ").append(r.acceptedChecksum()).append(System.lineSeparator());
        sb.append("candidate checksum: ").append(r.candidateChecksum()).append(System.lineSeparator());
        if (r.identical()) {
            sb.append("No differences. Nothing to approve.").append(System.lineSeparator());
            return sb.toString();
        }
        sb.append("DIFFERENCES DETECTED — manual approval required; accepted snapshot preserved.")
                .append(System.lineSeparator());
        sb.append("added dates:   ").append(r.addedDates()).append(System.lineSeparator());
        sb.append("removed dates: ").append(r.removedDates()).append(System.lineSeparator());
        sb.append("changed cells: ").append(r.changedCells().size()).append(System.lineSeparator());
        for (Changed c : r.changedCells()) {
            sb.append(String.format(Locale.ROOT, "  %s %-8s %s -> %s%n",
                    c.date(), c.prayer(), c.accepted(), c.candidate()));
        }
        return sb.toString();
    }
}
