package io.github.cyonecoder.prayertime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Minimal smoke test proving the Gradle + JUnit 5 + AssertJ build is wired up
 * under the standard directory layout and that the legacy entry point still
 * compiles onto the classpath. It intentionally does not exercise any
 * prayer-time math.
 */
class BuildSmokeTest {

    @Test
    void junitAndAssertJAreAvailable() {
        assertThat(2 + 2).isEqualTo(4);
    }

    @Test
    void legacyMainClassIsOnTheClasspath() throws ClassNotFoundException {
        assertThat(Class.forName(
                "io.github.cyonecoder.prayertime.legacy.LegacyMain")).isNotNull();
    }
}
