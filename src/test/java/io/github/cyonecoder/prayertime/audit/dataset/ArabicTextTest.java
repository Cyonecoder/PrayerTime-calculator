package io.github.cyonecoder.prayertime.audit.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArabicTextTest {

    @Test
    void normalizeTrimsCollapsesSpacesAndStripsTatweelAndControls() {
        // Tatweel (kashida) and a right-to-left mark are removed; runs of spaces collapse.
        String raw = "  ‏الـرباط   البيضاء  ";
        assertThat(ArabicText.normalize(raw)).isEqualTo("الرباط البيضاء");
    }

    @Test
    void normalizeKeepsDiacriticsButFoldRemovesThem() {
        String withFatha = "آسَفي"; // آسَفي (madda + fatha)
        String plain = "آسفي";           // آسفي
        // normalize keeps the fatha, so the two differ; fold removes only the vowel mark.
        assertThat(ArabicText.normalize(withFatha)).isNotEqualTo(ArabicText.normalize(plain));
        assertThat(ArabicText.fold(withFatha)).isEqualTo(ArabicText.fold(plain));
    }

    @Test
    void foldNeverSubstitutesOneLetterForAnother() {
        // Different letters (kaf vs jim) must stay distinct — no fuzzy folding.
        assertThat(ArabicText.fold("كرسيف"))   // كرسيف (Habous)
                .isNotEqualTo(ArabicText.fold("جرسيف")); // جرسيف (inventory)
        // A word-separating space is significant and is not removed.
        assertThat(ArabicText.fold("بن صالح"))   // بن صالح
                .isNotEqualTo(ArabicText.fold("بنصالح")); // بنصالح
    }

    @Test
    void nullAndBlankAreEmpty() {
        assertThat(ArabicText.normalize(null)).isEmpty();
        assertThat(ArabicText.fold("   ")).isEmpty();
    }
}
