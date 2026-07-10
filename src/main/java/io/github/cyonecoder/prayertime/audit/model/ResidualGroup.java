package io.github.cyonecoder.prayertime.audit.model;

/**
 * Aggregate residual statistics for one grouping bucket (a city, a prayer, a
 * month, an elevation band, …).
 *
 * @param count          number of residuals in the bucket
 * @param meanSigned     mean signed error (computed − official), minutes
 * @param meanAbsolute   mean absolute error, minutes
 * @param maxAbsolute    largest absolute error, minutes
 * @param minSigned      most-negative signed error, minutes
 * @param maxSigned      most-positive signed error, minutes
 */
public record ResidualGroup(
        int count, double meanSigned, double meanAbsolute,
        int maxAbsolute, int minSigned, int maxSigned) {

    /** A mutable builder that folds residual errors into a {@link ResidualGroup}. */
    public static final class Accumulator {
        private int count;
        private long sumSigned;
        private long sumAbs;
        private int maxAbs;
        private int minSigned = Integer.MAX_VALUE;
        private int maxSigned = Integer.MIN_VALUE;

        /** Folds one signed minute error into the bucket. */
        public void add(int signedMinutes) {
            int abs = Math.abs(signedMinutes);
            count++;
            sumSigned += signedMinutes;
            sumAbs += abs;
            maxAbs = Math.max(maxAbs, abs);
            minSigned = Math.min(minSigned, signedMinutes);
            maxSigned = Math.max(maxSigned, signedMinutes);
        }

        /** @return the immutable snapshot of the accumulated statistics. */
        public ResidualGroup build() {
            if (count == 0) {
                return new ResidualGroup(0, 0, 0, 0, 0, 0);
            }
            return new ResidualGroup(count, (double) sumSigned / count, (double) sumAbs / count,
                    maxAbs, minSigned, maxSigned);
        }
    }
}
