package io.github.cyonecoder.prayertime.audit.secapproach;

import io.github.cyonecoder.prayertime.audit.secapproach.SecApproachTrainingDataset.TrainingObservation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deterministic chronological split of a city's reference dates into
 * training, validation and holdout, used to report generalization honestly
 * rather than a purely in-sample summer fit.
 *
 * <p>The split is proportional and order-based — first two-thirds training, next
 * one-sixth validation, final one-sixth holdout — which for the validated 30-day
 * window reproduces exactly:
 *
 * <ul>
 *   <li>Training: 2026-06-17 … 2026-07-06 (20 days)</li>
 *   <li>Validation: 2026-07-07 … 2026-07-11 (5 days)</li>
 *   <li>Holdout: 2026-07-12 … 2026-07-16 (5 days)</li>
 * </ul>
 *
 * and degrades deterministically for any other window length.
 */
public final class SecApproachChronologicalSplit {

    /** Which chronological segment a date belongs to. */
    public enum Segment { TRAINING, VALIDATION, HOLDOUT }

    /** The observations of one (city, prayer) partitioned by segment, date-ordered. */
    public record Partition(
            List<TrainingObservation> training,
            List<TrainingObservation> validation,
            List<TrainingObservation> holdout) {

        public Partition {
            training = List.copyOf(training);
            validation = List.copyOf(validation);
            holdout = List.copyOf(holdout);
        }
    }

    private SecApproachChronologicalSplit() {
    }

    /**
     * Assigns each distinct date to a segment.
     *
     * @param dates the dates (any order; deduplicated and sorted internally)
     * @return date to segment, in ascending date order
     */
    public static Map<LocalDate, Segment> assign(List<LocalDate> dates) {
        List<LocalDate> sorted = dates.stream().distinct().sorted().toList();
        int n = sorted.size();
        int holdout = n / 6;
        int validation = n / 6;
        int training = n - validation - holdout;
        Map<LocalDate, Segment> out = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            Segment segment = i < training ? Segment.TRAINING
                    : i < training + validation ? Segment.VALIDATION
                    : Segment.HOLDOUT;
            out.put(sorted.get(i), segment);
        }
        return out;
    }

    /**
     * Partitions one (city, prayer)'s observations by the chronological split of
     * their own dates.
     *
     * @param observations the observations (must be non-empty)
     * @return the training/validation/holdout partition
     */
    public static Partition partition(List<TrainingObservation> observations) {
        if (observations.isEmpty()) {
            throw new IllegalArgumentException("no observations to split");
        }
        Map<LocalDate, Segment> segments =
                assign(observations.stream().map(TrainingObservation::date).toList());
        List<TrainingObservation> training = new ArrayList<>();
        List<TrainingObservation> validation = new ArrayList<>();
        List<TrainingObservation> holdout = new ArrayList<>();
        for (TrainingObservation o : observations) {
            switch (segments.get(o.date())) {
                case TRAINING -> training.add(o);
                case VALIDATION -> validation.add(o);
                case HOLDOUT -> holdout.add(o);
            }
        }
        return new Partition(training, validation, holdout);
    }
}
