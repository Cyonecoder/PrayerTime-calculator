package io.github.cyonecoder.prayertime.application.provider;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.cyonecoder.prayertime.domain.model.Prayer;
import io.github.cyonecoder.prayertime.domain.model.PrayerTimes;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders a {@link ProvidedTimes} as the public API JSON, preserving field order
 * and emitting explicit {@code null}s for non-applicable metadata (e.g.
 * {@code parameterVersion} on an official result).
 */
public final class ProvidedTimesJson {

    private static final Gson GSON =
            new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private ProvidedTimesJson() {
    }

    /** @return the API JSON for a served result. */
    public static String toJson(ProvidedTimes r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cityId", r.cityId());
        m.put("date", r.date().toString());
        m.put("sourceMode", r.sourceMode().name());
        m.put("source", r.source().name());
        m.put("official", r.official());
        m.put("fallbackUsed", r.fallbackUsed());
        m.put("habousCityId", asNumberOrString(r.habousCityId()));
        m.put("sourceChecksum", r.sourceChecksum());
        m.put("fetchedAt", r.fetchedAt() == null ? null : r.fetchedAt().toString());
        m.put("parameterVersion", r.parameterVersion());
        m.put("times", times(r.times()));
        r.comparisonOpt().ifPresent(c -> m.put("comparison", comparison(c)));
        return GSON.toJson(m);
    }

    private static Map<String, String> times(PrayerTimes t) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("fajr", t.fajr().toString());
        m.put("sunrise", t.sunrise().toString());
        m.put("dhuhr", t.dhuhr().toString());
        m.put("asr", t.asr().toString());
        m.put("maghrib", t.maghrib().toString());
        m.put("isha", t.isha().toString());
        return m;
    }

    private static Map<String, Object> comparison(ProvidedTimes.Comparison c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("officialAvailable", c.officialAvailable());
        m.put("official", c.official() == null ? null : times(c.official()));
        m.put("calculated", times(c.calculated()));
        Map<String, Integer> diffs = new LinkedHashMap<>();
        for (Prayer p : Prayer.values()) {
            if (c.differenceMinutes().containsKey(p)) {
                diffs.put(p.name().toLowerCase(java.util.Locale.ROOT), c.differenceMinutes().get(p));
            }
        }
        m.put("differenceMinutes", diffs);
        return m;
    }

    private static Object asNumberOrString(String habousCityId) {
        if (habousCityId == null) {
            return null;
        }
        try {
            return Long.parseLong(habousCityId.trim());
        } catch (NumberFormatException e) {
            return habousCityId;
        }
    }
}
