package io.github.cyonecoder.salatapi.jobs;

import java.util.Locale;
import java.util.Optional;

/** A one-shot job selectable with {@code --salat.job=...} (spec 25). */
public enum JobMode {

    /** Run the full daily workflow now. */
    SYNC_NOW("sync-now"),
    /** Reassemble every Gregorian month from stored raw snapshots, without fetching. */
    REBUILD_ARCHIVES("rebuild-archives"),
    /** Reassemble one Gregorian month (needs {@code salat.job.period=YYYY-MM}). */
    REBUILD_MONTH("rebuild-month"),
    /** Verify one date against its archive (needs {@code salat.job.date=YYYY-MM-DD}). */
    VERIFY_DATE("verify-date");

    private final String flag;

    JobMode(String flag) {
        this.flag = flag;
    }

    /** @return the {@code --salat.job} value that selects this job */
    public String flag() {
        return flag;
    }

    /**
     * @param value a {@code salat.job} value, or null/blank for no job
     * @return the matching job, or empty when none is requested
     */
    public static Optional<JobMode> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (JobMode mode : values()) {
            if (mode.flag.equals(normalized)) {
                return Optional.of(mode);
            }
        }
        throw new IllegalArgumentException("Unknown job '" + value + "'. Valid jobs: "
                + java.util.Arrays.stream(values()).map(JobMode::flag).toList());
    }
}
