package io.github.cyonecoder.salatapi.habous.parser;

import io.github.cyonecoder.salatapi.common.checksum.Sha256Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Parses the official Habous city dropdown ({@code <select name="ville">}) from a
 * live page, with per-option provenance.
 *
 * <p>Ported from {@code audit.dataset.HabousVilleDirectory} into a production
 * package (spec 9 forbids retaining {@code audit}); see
 * {@code docs/EXTRACTION_MAP.md}.
 */
@Component
public class HabousVilleDirectoryParser {

    /** Parser identifier recorded as provenance on the refreshed catalogue. */
    public static final String PARSER_VERSION = "habous-ville-select-1";

    private static final Pattern VILLE_ID = Pattern.compile("ville=(\\d+)");

    /**
     * One official dropdown option.
     *
     * @param habousCityId     the numeric Habous {@code ville} id
     * @param exactArabicName  the option text exactly as published — never altered
     * @param normalizedArabic {@link ArabicTextNormalizer#normalize} of the name
     * @param foldedArabic     {@link ArabicTextNormalizer#fold} of the name
     */
    public record Option(
            String habousCityId,
            String exactArabicName,
            String normalizedArabic,
            String foldedArabic) {
    }

    /**
     * The parsed dropdown plus the checksum of the page it came from.
     *
     * @param options        the options in document order
     * @param sourceChecksum SHA-256 of the page the options were parsed from
     */
    public record Directory(List<Option> options, String sourceChecksum) {

        public Directory {
            options = List.copyOf(options);
        }

        /** @return the observed number of official villes — never assume 191 */
        public int count() {
            return options.size();
        }

        /** @param habousCityId the numeric ville id */
        public Optional<Option> byId(String habousCityId) {
            return options.stream().filter(o -> o.habousCityId().equals(habousCityId)).findFirst();
        }

        /**
         * All options whose folded (diacritic-insensitive) Arabic name equals the
         * folded {@code arabicName}. More than one result means the name is ambiguous.
         */
        public List<Option> matchByArabic(String arabicName) {
            String want = ArabicTextNormalizer.fold(arabicName);
            List<Option> hits = new ArrayList<>();
            for (Option o : options) {
                if (o.foldedArabic().equals(want)) {
                    hits.add(o);
                }
            }
            return hits;
        }
    }

    private final Sha256Service sha256;

    public HabousVilleDirectoryParser(Sha256Service sha256) {
        this.sha256 = sha256;
    }

    /**
     * Parses the dropdown from a Habous page.
     *
     * @param html the fetched page HTML (any page carrying the {@code ville} select)
     * @return the parsed directory
     * @throws HabousParseException if no {@code <select name="ville">} with options exists
     */
    public Directory parse(String html) {
        if (html == null || html.isBlank()) {
            throw new HabousParseException("Cannot parse an empty page");
        }
        Document doc = Jsoup.parse(html);
        Element select = doc.selectFirst("select[name=ville]");
        if (select == null) {
            // A maintenance page or a site redesign lands here. Refusing to parse
            // it stops an empty catalogue from erasing the known cities.
            throw new HabousParseException("No <select name=\"ville\"> found on the page");
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
            parsed.add(new Option(
                    id,
                    text.trim(),
                    ArabicTextNormalizer.normalize(text),
                    ArabicTextNormalizer.fold(text)));
        }
        if (parsed.isEmpty()) {
            throw new HabousParseException("<select name=\"ville\"> contained no ville options");
        }
        return new Directory(parsed, sha256.hex(html));
    }

    /**
     * The ville the page was served for, read from the {@code selected} option of
     * its {@code <select name="ville">}.
     *
     * <p>Used to confirm a fetched timetable is actually for the city that was
     * requested (spec 20, "validate the city name"): the official page marks the
     * chosen city with {@code selected=selected}, so a mismatch means the site
     * returned the wrong city and the page must not be stored under the requested
     * id.
     *
     * @param html the fetched page HTML
     * @return the selected option, or empty if none is marked
     */
    public Optional<Option> selectedVille(String html) {
        if (html == null || html.isBlank()) {
            return Optional.empty();
        }
        Document doc = Jsoup.parse(html);
        Element option = doc.selectFirst("select[name=ville] option[selected]");
        if (option == null) {
            return Optional.empty();
        }
        Matcher m = VILLE_ID.matcher(option.attr("value"));
        if (!m.find()) {
            return Optional.empty();
        }
        String text = option.text();
        return Optional.of(new Option(
                m.group(1), text.trim(),
                ArabicTextNormalizer.normalize(text), ArabicTextNormalizer.fold(text)));
    }
}
