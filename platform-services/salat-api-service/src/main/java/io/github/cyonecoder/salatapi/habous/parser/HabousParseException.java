package io.github.cyonecoder.salatapi.habous.parser;

/**
 * Raised when an official page cannot be parsed into a trustworthy structure —
 * a missing {@code <select name="ville">}, an absent or malformed timetable
 * table, or an unreadable row.
 *
 * <p>This is deliberately distinct from an outage: the page arrived, but it is
 * not what it claims to be (a maintenance notice, a redesign, a truncated
 * response). Such a page must never be archived as a timetable.
 */
public class HabousParseException extends RuntimeException {

    public HabousParseException(String message) {
        super(message);
    }

    public HabousParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
