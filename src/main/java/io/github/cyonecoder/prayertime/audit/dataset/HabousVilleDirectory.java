package io.github.cyonecoder.prayertime.audit.dataset;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * The official Habous city dropdown ({@code <select name="ville">}) parsed from a
 * live page, with per-option provenance. Audit-only: never a production runtime
 * dependency.
 */
public final class HabousVilleDirectory {

    /** Parser identifier recorded in the audit output. */
    public static final String PARSER_VERSION = "habous-ville-select-1";

    private static final Pattern VILLE_ID = Pattern.compile("ville=(\\d+)");

    /**
     * One official dropdown option.
     *
     * @param habousCityId       the numeric Habous {@code ville} id
     * @param exactArabicName    the option text exactly as published
     * @param visibleLabel       the option's visible label (same text, trimmed)
     * @param normalizedArabic   {@link ArabicText#normalize} of the name
     * @param foldedArabic       {@link ArabicText#fold} of the name (diacritic-insensitive)
     */
    public record Option(
            String habousCityId,
            String exactArabicName,
            String visibleLabel,
            String normalizedArabic,
            String foldedArabic) {
    }

    private final List<Option> options;
    private final String sourceChecksum;

    private HabousVilleDirectory(List<Option> options, String sourceChecksum) {
        this.options = List.copyOf(options);
        this.sourceChecksum = sourceChecksum;
    }

    /**
     * Parses the dropdown from a Habous page.
     *
     * @param html the fetched page HTML (any page carrying the {@code ville} select)
     * @return the parsed directory
     * @throws IllegalStateException if no {@code <select name="ville">} with options exists
     */
    public static HabousVilleDirectory parse(String html) {
        Document doc = Jsoup.parse(html);
        Element select = doc.selectFirst("select[name=ville]");
        if (select == null) {
            throw new IllegalStateException("No <select name=\"ville\"> found on the page");
        }
        List<Option> parsed = new ArrayList<>();
        for (Element option : select.select("option")) {
            String value = option.attr("value");
            Matcher m = VILLE_ID.matcher(value);
            if (!m.find()) {
                continue; // a placeholder/blank option, e.g. "choose a city"
            }
            String id = m.group(1);
            String text = option.text();
            parsed.add(new Option(id, text, text.trim(),
                    ArabicText.normalize(text), ArabicText.fold(text)));
        }
        if (parsed.isEmpty()) {
            throw new IllegalStateException("<select name=\"ville\"> contained no ville options");
        }
        return new HabousVilleDirectory(parsed, HabousSnapshotStore.sha256Hex(html));
    }

    /** @return all parsed options, in document order. */
    public List<Option> options() {
        return options;
    }

    /** @return the SHA-256 of the page the options were parsed from. */
    public String sourceChecksum() {
        return sourceChecksum;
    }

    /**
     * All options whose folded (diacritic-insensitive) Arabic name equals the
     * folded {@code arabicName}. More than one result means the name is ambiguous.
     *
     * @param arabicName the candidate Arabic name
     * @return the matching options (0, 1, or several)
     */
    public List<Option> matchByArabic(String arabicName) {
        String want = ArabicText.fold(arabicName);
        List<Option> hits = new ArrayList<>();
        for (Option o : options) {
            if (o.foldedArabic().equals(want)) {
                hits.add(o);
            }
        }
        return hits;
    }

    /**
     * The unique option with the given numeric id, if present.
     *
     * @param habousCityId the numeric ville id
     * @return the option, or empty
     */
    public java.util.Optional<Option> byId(String habousCityId) {
        return options.stream().filter(o -> o.habousCityId().equals(habousCityId)).findFirst();
    }
}
