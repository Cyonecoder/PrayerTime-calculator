package io.github.cyonecoder.salatapi.verification;

import io.github.cyonecoder.salatapi.common.model.Prayer;
import io.github.cyonecoder.salatapi.common.model.PrayerTimes;
import io.github.cyonecoder.salatapi.verification.model.PrayerDifference;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Compares official published times against sec-approach computed times, prayer
 * by prayer, <strong>without ever merging the two sources</strong> (spec 20).
 *
 * <p>Ported and narrowed from
 * {@code application.service.PrayerTimeComparisonService}: the daily verification
 * cares about exact-minute agreement (the ~97% metric), so this produces per-prayer
 * signed minute differences rather than a tolerance pass/fail. All six prayers are
 * compared, including sunrise, since the sec-approach parameters are fitted for
 * all six. See {@code docs/EXTRACTION_MAP.md}.
 */
@Service
public class PrayerTimeComparisonService {

    /**
     * @param official    the officially published schedule
     * @param secApproach the sec-approach computed schedule
     * @return one difference per prayer, in chronological order
     */
    public List<PrayerDifference> compare(PrayerTimes official, PrayerTimes secApproach) {
        List<PrayerDifference> differences = new ArrayList<>();
        for (Prayer prayer : Prayer.values()) {
            differences.add(PrayerDifference.of(prayer, official.get(prayer), secApproach.get(prayer)));
        }
        return List.copyOf(differences);
    }
}
