package io.github.cyonecoder.salatapi.habous.parser;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Conservative Unicode normalization for comparing Habous Arabic city names.
 *
 * <p>Two levels are offered, and <strong>neither substitutes one Arabic city name
 * for another</strong> — no alef/hamza/teh-marbuta/yeh letter folding, no
 * transliteration:
 *
 * <ul>
 *   <li>{@link #normalize} — harmless only: strip directional/control characters,
 *       remove tatweel, apply Unicode NFC, collapse runs of whitespace, and trim.
 *   <li>{@link #fold} — {@code normalize} plus removal of Arabic diacritics
 *       (harakat, tanwin, shadda, sukun, superscript alef), for an
 *       <em>optional</em> comparison that ignores vowel marks only.
 * </ul>
 *
 * <p>The conservatism is deliberate and load-bearing: folding alef variants would
 * merge genuinely distinct Moroccan city names, silently mapping one city's
 * timetable onto another. Ported verbatim from
 * {@code audit.dataset.ArabicText}; see {@code docs/EXTRACTION_MAP.md}.
 */
public final class ArabicTextNormalizer {

    private ArabicTextNormalizer() {
    }

    /** Harmless normalization: controls out, tatweel out, NFC, spaces collapsed, trimmed. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (isControlOrDirectional(c) || c == 'ـ') { // tatweel (kashida)
                continue;
            }
            sb.append(c);
        }
        String nfc = Normalizer.normalize(sb.toString(), Normalizer.Form.NFC);
        return nfc.replaceAll("\\s+", " ").trim();
    }

    /** {@link #normalize} plus Arabic diacritic removal (vowel marks only). */
    public static String fold(String raw) {
        String n = normalize(raw);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (isArabicDiacritic(c)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean isControlOrDirectional(char c) {
        // Bidirectional formatting characters and the Arabic letter mark, plus the
        // zero-width space/BOM. These are invisible and must not affect matching.
        return c == '؜'                              // Arabic letter mark
                || (c >= '​' && c <= '‏')       // ZWSP..RLM
                || (c >= '‪' && c <= '‮')       // LRE..RLO
                || (c >= '⁦' && c <= '⁩')       // LRI..PDI
                || c == '﻿';                         // BOM / ZWNBSP
    }

    private static boolean isArabicDiacritic(char c) {
        // Harakat, tanwin, shadda, sukun (064B-0652), superscript alef (0670),
        // and the Quranic marks range (06D6-06ED). These are marks, not letters,
        // so removing them never turns one letter into another.
        return (c >= 'ً' && c <= 'ْ')
                || c == 'ٰ'
                || (c >= 'ۖ' && c <= 'ۭ');
    }
}
