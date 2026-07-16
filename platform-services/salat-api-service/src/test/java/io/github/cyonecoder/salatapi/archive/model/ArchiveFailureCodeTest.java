package io.github.cyonecoder.salatapi.archive.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Which failure codes are permanent, and therefore degrade a month (spec 18). */
class ArchiveFailureCodeTest {

    @Test
    void anUnparseablePageIsPermanentBecauseRefetchingCannotFixIt() {
        assertThat(ArchiveFailureCode.PAGE_UNPARSEABLE.permanent()).isTrue();
    }

    @Test
    void anOutageIsTransientBecauseALaterSyncMayRecover() {
        assertThat(ArchiveFailureCode.TIMETABLE_UNAVAILABLE.permanent()).isFalse();
    }

    @Test
    void notYetCollectedIsNotAFailureAtAll() {
        assertThat(ArchiveFailureCode.NOT_YET_COLLECTED.permanent()).isFalse();
    }
}
