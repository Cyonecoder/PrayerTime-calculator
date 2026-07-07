package io.github.cyonecoder.prayertime.audit.model;

import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset;
import io.github.cyonecoder.prayertime.audit.optimization.ReferenceDataset.Observation;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic SHA-256 checksum of a {@link ReferenceDataset}'s content.
 *
 * <p>The checksum is computed from a canonical serialization (observations are
 * always held sorted by city then date, and each field is emitted in a fixed
 * order) so that the same observations always produce the same digest,
 * independent of file layout or load order. It anchors the reproducibility
 * record: a changed checksum means the fitting/validation/holdout inputs
 * changed.
 */
public final class DatasetChecksum {

    private DatasetChecksum() {
    }

    /**
     * @param dataset the dataset to digest (must not be empty)
     * @return the lowercase hex SHA-256 of its canonical serialization
     */
    public static String sha256(ReferenceDataset dataset) {
        return sha256(dataset.observations());
    }

    /**
     * Digests an arbitrary set of observations, sorting them into the canonical
     * (city, date) order first. Accepts an empty list (e.g. an absent holdout
     * partition), which yields the well-defined SHA-256 of the empty content.
     *
     * @param observations the observations to digest
     * @return the lowercase hex SHA-256 of their canonical serialization
     */
    public static String sha256(List<Observation> observations) {
        List<Observation> sorted = new ArrayList<>(observations);
        sorted.sort(Comparator.comparing(Observation::city).thenComparing(Observation::date));
        StringBuilder sb = new StringBuilder();
        for (Observation o : sorted) {
            sb.append(o.city()).append('|').append(o.date());
            for (Prayer prayer : Prayer.values()) {
                sb.append('|').append(o.timeFor(prayer));
            }
            sb.append('\n');
        }
        return hex(digest(sb.toString()));
    }

    private static byte[] digest(String canonical) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }
}
