package io.github.cyonecoder.salatapi.scheduling;

import io.github.cyonecoder.salatapi.config.SalatApiProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Guards the daily workflow so only one run proceeds at a time (spec 20).
 *
 * <p>Two layers: an in-process flag stops a scheduled run from overlapping a
 * manually triggered one within this JVM, and an OS file lock stops a second
 * process (e.g. a manual {@code sync-now} while the service is running) from
 * colliding on the shared data directory. Both matter because concurrent writers
 * to the same archive files would interleave and corrupt them, and there is no
 * database transaction to fall back on.
 */
@Component
public class SyncExecutionLock {

    private static final Logger log = LoggerFactory.getLogger(SyncExecutionLock.class);
    private static final String LOCK_FILE = "sync.lock";

    /** Held while a run is in progress; releasing it frees both layers. */
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }

    private final SalatApiProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SyncExecutionLock(SalatApiProperties properties) {
        this.properties = properties;
    }

    /**
     * Attempts to acquire both locks.
     *
     * @return a handle to release them, or empty if a run is already in progress
     */
    public java.util.Optional<Handle> tryAcquire() {
        if (!running.compareAndSet(false, true)) {
            log.warn("A synchronization run is already in progress in this process; skipping");
            return java.util.Optional.empty();
        }
        FileChannel channel = null;
        FileLock fileLock = null;
        try {
            Path lockPath = properties.jobsDirectory().resolve(LOCK_FILE);
            Files.createDirectories(lockPath.getParent());
            channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            fileLock = channel.tryLock();
            if (fileLock == null) {
                log.warn("Another process holds the synchronization lock at {}; skipping", lockPath);
                closeQuietly(channel);
                running.set(false);
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new LockHandle(channel, fileLock));
        } catch (OverlappingFileLockException e) {
            closeQuietly(channel);
            running.set(false);
            log.warn("Another handle already holds the synchronization lock; skipping");
            return java.util.Optional.empty();
        } catch (IOException e) {
            closeQuietly(channel);
            running.set(false);
            throw new UncheckedIOException("Failed to acquire the synchronization lock", e);
        }
    }

    /** @return whether a run is currently in progress in this process */
    public boolean isRunning() {
        return running.get();
    }

    private final class LockHandle implements Handle {
        private final FileChannel channel;
        private final FileLock fileLock;

        private LockHandle(FileChannel channel, FileLock fileLock) {
            this.channel = channel;
            this.fileLock = fileLock;
        }

        @Override
        public void close() {
            try {
                fileLock.release();
            } catch (IOException e) {
                log.warn("Failed to release the synchronization file lock", e);
            } finally {
                closeQuietly(channel);
                running.set(false);
            }
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
