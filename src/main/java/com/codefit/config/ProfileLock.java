package com.codefit.config;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Cooperative process ownership, acquired before any SQLite initialization or background work. */
public final class ProfileLock implements AutoCloseable {
    private static final List<ProfileLock> PROCESS_LOCKS = new ArrayList<>();
    private final FileChannel channel;
    private final FileLock lock;

    private ProfileLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static ProfileLock acquire(Path database) throws IOException {
        Path absolute = database.toAbsolutePath().normalize();
        Path canonical = absolute.getParent().toRealPath().resolve(absolute.getFileName());
        if (Files.exists(canonical, LinkOption.NOFOLLOW_LINKS)) {
            canonical = canonical.toRealPath();
            if (!Files.isRegularFile(canonical)) throw new IOException("PROFILE_INVALID");
            // Hard links have distinct paths but share data; refuse that unsupported ownership
            // form.
            if (canonical.getFileSystem().supportedFileAttributeViews().contains("unix")
                    && ((Number) Files.getAttribute(canonical, "unix:nlink")).longValue() != 1)
                throw new IOException("PROFILE_INVALID");
        }
        Path sidecar = canonical.resolveSibling(canonical.getFileName() + ".owner.lock");
        FileChannel channel =
                FileChannel.open(
                        sidecar,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("PROFILE_BUSY");
            return new ProfileLock(channel, lock);
        } catch (OverlappingFileLockException busy) {
            channel.close();
            throw new IOException("PROFILE_BUSY");
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    /** JavaFX may have writers still draining after stop(); only JVM exit releases this lock. */
    public static synchronized void holdForProcess(String databaseUrl) throws IOException {
        String prefix = "jdbc:sqlite:";
        if (!databaseUrl.startsWith(prefix)) throw new IOException("PROFILE_INVALID");
        String name = databaseUrl.substring(prefix.length());
        if (name.isBlank()
                || name.startsWith("file:")
                || name.contains("?")
                || name.equals(":memory:")) throw new IOException("PROFILE_INVALID");
        PROCESS_LOCKS.add(acquire(Path.of(name)));
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
        // Never unlink a lock file: another process may already hold an open handle to its inode.
    }
}
