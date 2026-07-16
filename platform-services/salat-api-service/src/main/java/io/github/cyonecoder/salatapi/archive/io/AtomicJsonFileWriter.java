package io.github.cyonecoder.salatapi.archive.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.stereotype.Component;

/**
 * Writes JSON to the local data directory atomically: serialize to a temporary
 * file in the same directory, then move it into place.
 *
 * <p>Without this, a crash or a kill mid-write leaves a truncated JSON file that
 * parses as corrupt on the next read — and since there is no database and no
 * ability to re-fetch history from the official source, a corrupted archive is
 * unrecoverable. The move is atomic where the filesystem supports it, with a
 * documented fallback where it does not.
 */
@Component
public class AtomicJsonFileWriter {

    private final ObjectMapper mapper;

    public AtomicJsonFileWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Serializes {@code value} as pretty JSON to {@code target}, replacing any
     * existing file only once the new content is fully written.
     *
     * @param target the destination file; parent directories are created
     * @param value  the value to serialize
     */
    public void write(Path target, Object value) {
        try {
            Path parent = target.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, ".tmp-", ".json");
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), value);
                move(temp, target);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + target, e);
        }
    }

    /**
     * Reads JSON from {@code source}.
     *
     * @param source the file to read
     * @param type   the target type
     * @return the deserialized value
     */
    public <T> T read(Path source, Class<T> type) {
        try {
            return mapper.readValue(source.toFile(), type);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + source, e);
        }
    }

    private void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Some filesystems (notably certain network mounts) cannot move
            // atomically. A replacing move is still safer than writing in place,
            // because the content was fully flushed before this point.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
