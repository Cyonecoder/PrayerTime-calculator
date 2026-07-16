package io.github.cyonecoder.salatapi.habous.client;

import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Abstraction over waiting, so the request delay and retry backoff are testable
 * without a test actually sleeping.
 *
 * <p>Ported from {@code HabousReferenceCollector.Sleeper}; see
 * {@code docs/EXTRACTION_MAP.md}.
 */
@FunctionalInterface
public interface Sleeper {

    /**
     * @param duration how long to wait; a non-positive duration returns immediately
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void sleep(Duration duration) throws InterruptedException;

    /** The real, thread-blocking sleeper used in production. */
    @Component
    class Blocking implements Sleeper {
        @Override
        public void sleep(Duration duration) throws InterruptedException {
            if (duration != null && !duration.isNegative() && !duration.isZero()) {
                Thread.sleep(duration.toMillis());
            }
        }
    }
}
