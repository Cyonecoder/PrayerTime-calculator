package io.github.cyonecoder.prayertime.audit.model;

import io.github.cyonecoder.prayertime.audit.optimization.CandidateResult;
import io.github.cyonecoder.prayertime.audit.optimization.CandidateResult.Residual;
import io.github.cyonecoder.prayertime.audit.optimization.CityLocations;
import io.github.cyonecoder.prayertime.audit.optimization.CityLocations.Location;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Decomposes a candidate's residuals along every axis the Prompt-3 audit
 * requires — city, prayer, month, latitude, longitude, elevation, UTC offset —
 * and runs a set of structural detectors over them: constant per-city offsets,
 * seasonal drift, elevation-linked bias, isolated official overrides and
 * possible parser errors.
 *
 * <p>This never fits anything; it only summarizes an existing evaluation so a
 * human can see where a global model systematically diverges from the official
 * schedule.
 */
public final class ResidualAnalysis {

    private final Map<String, ResidualGroup> byCity;
    private final Map<Prayer, ResidualGroup> byPrayer;
    private final Map<YearMonth, ResidualGroup> byMonth;
    private final Map<Double, ResidualGroup> byLatitude;
    private final Map<Double, ResidualGroup> byLongitude;
    private final Map<Double, ResidualGroup> byElevation;
    private final Map<Integer, ResidualGroup> byUtcOffsetHours;
    private final Map<Integer, Integer> signedErrorFrequency;
    private final List<String> abruptDateChanges;
    private final List<String> detections;

    private ResidualAnalysis(Builder b) {
        this.byCity = b.byCity;
        this.byPrayer = b.byPrayer;
        this.byMonth = b.byMonth;
        this.byLatitude = b.byLatitude;
        this.byLongitude = b.byLongitude;
        this.byElevation = b.byElevation;
        this.byUtcOffsetHours = b.byUtcOffset;
        this.signedErrorFrequency = b.frequency;
        this.abruptDateChanges = b.abruptDateChanges;
        this.detections = b.detections;
    }

    public Map<String, ResidualGroup> byCity() {
        return byCity;
    }

    public Map<Prayer, ResidualGroup> byPrayer() {
        return byPrayer;
    }

    public Map<YearMonth, ResidualGroup> byMonth() {
        return byMonth;
    }

    public Map<Double, ResidualGroup> byLatitude() {
        return byLatitude;
    }

    public Map<Double, ResidualGroup> byLongitude() {
        return byLongitude;
    }

    public Map<Double, ResidualGroup> byElevation() {
        return byElevation;
    }

    public Map<Integer, ResidualGroup> byUtcOffsetHours() {
        return byUtcOffsetHours;
    }

    public Map<Integer, Integer> signedErrorFrequency() {
        return signedErrorFrequency;
    }

    public List<String> abruptDateChanges() {
        return abruptDateChanges;
    }

    public List<String> detections() {
        return detections;
    }

    /**
     * Analyzes the residuals of an evaluated candidate.
     *
     * @param result    the evaluated candidate
     * @param locations resolves per-city latitude/longitude/elevation/zone
     * @return the residual analysis
     */
    public static ResidualAnalysis analyze(CandidateResult result, CityLocations locations) {
        Builder b = new Builder();
        List<Residual> residuals = result.residuals();

        Map<String, ResidualGroup.Accumulator> city = new LinkedHashMap<>();
        Map<Prayer, ResidualGroup.Accumulator> prayer = new EnumMap<>(Prayer.class);
        Map<YearMonth, ResidualGroup.Accumulator> month = new TreeMap<>();
        Map<Double, ResidualGroup.Accumulator> lat = new TreeMap<>();
        Map<Double, ResidualGroup.Accumulator> lon = new TreeMap<>();
        Map<Double, ResidualGroup.Accumulator> elev = new TreeMap<>();
        Map<Integer, ResidualGroup.Accumulator> utc = new TreeMap<>();

        for (Residual r : residuals) {
            int signed = r.signedErrorMinutes();
            Location loc = locations.resolve(r.city());
            double latitude = round(loc.coordinates().latitude());
            double longitude = round(loc.coordinates().longitude());
            double elevation = round(loc.coordinates().elevationMeters());
            int offsetHours = loc.zone().getRules()
                    .getOffset(r.date().atTime(LocalTime.NOON)).getTotalSeconds() / 3600;

            city.computeIfAbsent(r.city(), k -> new ResidualGroup.Accumulator()).add(signed);
            prayer.computeIfAbsent(r.prayer(), k -> new ResidualGroup.Accumulator()).add(signed);
            month.computeIfAbsent(YearMonth.from(r.date()), k -> new ResidualGroup.Accumulator()).add(signed);
            lat.computeIfAbsent(latitude, k -> new ResidualGroup.Accumulator()).add(signed);
            lon.computeIfAbsent(longitude, k -> new ResidualGroup.Accumulator()).add(signed);
            elev.computeIfAbsent(elevation, k -> new ResidualGroup.Accumulator()).add(signed);
            utc.computeIfAbsent(offsetHours, k -> new ResidualGroup.Accumulator()).add(signed);
            b.frequency.merge(signed, 1, Integer::sum);
        }

        b.byCity = freeze(city);
        b.byPrayer = freezeEnum(prayer);
        b.byMonth = freeze(month);
        b.byLatitude = freeze(lat);
        b.byLongitude = freeze(lon);
        b.byElevation = freeze(elev);
        b.byUtcOffset = freeze(utc);

        b.abruptDateChanges = detectAbruptChanges(residuals);
        b.detections = detect(b.byCity, b.byMonth, b.byElevation, residuals, locations);
        return new ResidualAnalysis(b);
    }

    // --- Detectors -----------------------------------------------------------

    private static List<String> detect(
            Map<String, ResidualGroup> byCity, Map<YearMonth, ResidualGroup> byMonth,
            Map<Double, ResidualGroup> byElevation, List<Residual> residuals,
            CityLocations locations) {
        List<String> out = new ArrayList<>();

        // Constant per-city offsets: a nonzero mean with a tight spread.
        for (Map.Entry<String, ResidualGroup> e : byCity.entrySet()) {
            ResidualGroup g = e.getValue();
            int spread = g.maxSigned() - g.minSigned();
            if (Math.abs(g.meanSigned()) >= 0.5 && spread <= 1) {
                out.add(String.format(Locale.ROOT,
                        "constant-city-offset: %s ~%+.2f min (spread %d)",
                        e.getKey(), g.meanSigned(), spread));
            }
        }

        // Seasonal residuals: months whose mean signed error is non-trivial.
        for (Map.Entry<YearMonth, ResidualGroup> e : byMonth.entrySet()) {
            if (Math.abs(e.getValue().meanSigned()) >= 0.5) {
                out.add(String.format(Locale.ROOT,
                        "seasonal-residual: %s mean %+.2f min",
                        e.getKey(), e.getValue().meanSigned()));
            }
        }

        // Elevation-linked residuals: correlation between elevation and mean bias.
        double corr = elevationCorrelation(byElevation);
        if (!Double.isNaN(corr) && Math.abs(corr) >= 0.6) {
            out.add(String.format(Locale.ROOT,
                    "elevation-linked-residual: corr(elevation, mean error) = %+.2f", corr));
        }

        // Isolated official overrides: a single large residual against an
        // otherwise-accurate city.
        Map<String, Integer> cityMedianAbs = new LinkedHashMap<>();
        for (Map.Entry<String, ResidualGroup> e : byCity.entrySet()) {
            cityMedianAbs.put(e.getKey(), (int) Math.round(e.getValue().meanAbsolute()));
        }
        for (Residual r : residuals) {
            if (r.absoluteErrorMinutes() >= 3 && cityMedianAbs.getOrDefault(r.city(), 0) <= 1) {
                out.add(String.format(Locale.ROOT,
                        "isolated-override: %s %s %s error %+d min",
                        r.city(), r.date(), r.prayer(), r.signedErrorMinutes()));
            }
        }

        // Possible parser errors: official times out of canonical daily order.
        out.addAll(detectOrderingErrors(residuals));
        return out;
    }

    private static double elevationCorrelation(Map<Double, ResidualGroup> byElevation) {
        int n = byElevation.size();
        if (n < 2) {
            return Double.NaN;
        }
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double syy = 0;
        double sxy = 0;
        for (Map.Entry<Double, ResidualGroup> e : byElevation.entrySet()) {
            double x = e.getKey();
            double y = e.getValue().meanSigned();
            sx += x;
            sy += y;
            sxx += x * x;
            syy += y * y;
            sxy += x * y;
        }
        double cov = n * sxy - sx * sy;
        double denom = Math.sqrt((n * sxx - sx * sx) * (n * syy - sy * sy));
        return denom == 0 ? Double.NaN : cov / denom;
    }

    /** Groups residuals by (city, prayer) and flags official minute jumps ≥ 3. */
    private static List<String> detectAbruptChanges(List<Residual> residuals) {
        Map<String, List<Residual>> series = new LinkedHashMap<>();
        for (Residual r : residuals) {
            series.computeIfAbsent(r.city() + "|" + r.prayer(), k -> new ArrayList<>()).add(r);
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<Residual>> e : series.entrySet()) {
            List<Residual> list = e.getValue();
            list.sort((a, c) -> a.date().compareTo(c.date()));
            for (int i = 1; i < list.size(); i++) {
                Residual prev = list.get(i - 1);
                Residual curr = list.get(i);
                if (!curr.date().equals(prev.date().plusDays(1))) {
                    continue;
                }
                int officialJump = curr.officialMinuteOfDay() - prev.officialMinuteOfDay();
                if (Math.abs(officialJump) >= 3) {
                    out.add(String.format(Locale.ROOT,
                            "abrupt-official-change: %s %s %s→%s official %+d min",
                            curr.city(), curr.prayer(), prev.date(), curr.date(), officialJump));
                }
            }
        }
        return out;
    }

    private static List<String> detectOrderingErrors(List<Residual> residuals) {
        Map<String, Map<Prayer, Integer>> byDay = new LinkedHashMap<>();
        for (Residual r : residuals) {
            byDay.computeIfAbsent(r.city() + "|" + r.date(), k -> new EnumMap<>(Prayer.class))
                    .put(r.prayer(), r.officialMinuteOfDay());
        }
        Prayer[] order = {Prayer.FAJR, Prayer.SUNRISE, Prayer.DHUHR,
                Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA};
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Map<Prayer, Integer>> e : byDay.entrySet()) {
            Map<Prayer, Integer> day = e.getValue();
            for (int i = 1; i < order.length; i++) {
                Integer prev = day.get(order[i - 1]);
                Integer curr = day.get(order[i]);
                if (prev != null && curr != null && curr <= prev) {
                    out.add("possible-parser-error: " + e.getKey() + " "
                            + order[i - 1] + "≥" + order[i]);
                    break;
                }
            }
        }
        return out;
    }

    // --- Helpers -------------------------------------------------------------

    private static double round(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    private static <K> Map<K, ResidualGroup> freeze(Map<K, ResidualGroup.Accumulator> in) {
        Map<K, ResidualGroup> out = new LinkedHashMap<>();
        in.forEach((k, acc) -> out.put(k, acc.build()));
        return out;
    }

    private static Map<Prayer, ResidualGroup> freezeEnum(Map<Prayer, ResidualGroup.Accumulator> in) {
        Map<Prayer, ResidualGroup> out = new EnumMap<>(Prayer.class);
        in.forEach((k, acc) -> out.put(k, acc.build()));
        return out;
    }

    private static final class Builder {
        Map<String, ResidualGroup> byCity;
        Map<Prayer, ResidualGroup> byPrayer;
        Map<YearMonth, ResidualGroup> byMonth;
        Map<Double, ResidualGroup> byLatitude;
        Map<Double, ResidualGroup> byLongitude;
        Map<Double, ResidualGroup> byElevation;
        Map<Integer, ResidualGroup> byUtcOffset;
        final Map<Integer, Integer> frequency = new TreeMap<>();
        List<String> abruptDateChanges = new ArrayList<>();
        List<String> detections = new ArrayList<>();
    }
}
