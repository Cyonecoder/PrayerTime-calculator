package io.github.cyonecoder.salatapi.secapproach.exception;

/**
 * Raised when sec-approach is asked for a city that has no accepted offsets.
 *
 * <p>Only 48 of the 67 registry cities (and none of the 131 unmapped official
 * villes) have offsets. This is a refusal, not a fallback: the service never
 * substitutes an un-offset raw time and presents it as sec-approach.
 */
public class CityNotSupportedException extends RuntimeException {

    private final String cityIdentifier;

    public CityNotSupportedException(String cityIdentifier, String message) {
        super(message);
        this.cityIdentifier = cityIdentifier;
    }

    /** @return the identifier that was asked for */
    public String cityIdentifier() {
        return cityIdentifier;
    }
}
