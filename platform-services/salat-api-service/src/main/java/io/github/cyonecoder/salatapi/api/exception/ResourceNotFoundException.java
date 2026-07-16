package io.github.cyonecoder.salatapi.api.exception;

/** A requested resource (city, month, archive, report) does not exist → HTTP 404. */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
