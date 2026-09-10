package tacz.longrangesniping.terrain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.function.Consumer;

/** One bounded worker per save. No world objects and no disk access in query methods. */
public final class SnapshotDiskCache implements AutoCloseable {
    public record Limits(long diskBytes, int sections, int queueCount, long queueBytes, long bytesPerSecond, int operationsPerSecond) {
        public Limits {
            if (diskBytes < 1 || sections < 1 || queueCount < 1 || queueBytes < SnapshotCodec.MAX_BYTES
                    || bytesPerSecond < 1 || operationsPerSecond < 1) throw new IllegalArgumentException("Invalid disk budget");
        }
    }
    public record Status(boolean ready, boolean failed, int indexed, long diskBytes, int queued, long queueBytes,
                         long reads, long writes, long dropped, long corrupt, long evicted, String error) {}
    private record Entry(long bytes, long modified) {}
    private static final class Job {
        final SectionKey key;
        final SectionSnapshot write;
        final long charge;
        boolean running, complete;
        SectionSnapshot result;
        Job(SectionKey key, SectionSnapshot write) {
            this.key = key; this.write = write;
            charge = write == null ? SnapshotCodec.MAX_BYTES : write.estimatedBytes();
        }
    }
    private final Path directory;
    private final String fingerprint;
    private final Path worldData;
    private final Limits limits;
    private final Thread worker;
    private final Consumer<String> errors;
    private final Map<SectionKey, Entry> index = new LinkedHashMap<>(16, .75f, true);
    private final Map<SectionKey, Job> jobs = new LinkedHashMap<>();
    // Invalidation addresses have a separate bound: a normal chunk load invalidates many sections at once.
    private final Set<SectionKey> deletes = new LinkedHashSet<>();
    private long diskBytes, reserved, reads, writes, dropped, corrupt, evicted;
    private boolean ready, failed, closing, abort;
    private String error = "none";
    private long nextOperation, nextBytes;

    public SnapshotDiskCache(Path directory, String fingerprint, Limits limits, Consumer<String> errors) {
        this(directory, fingerprint, limits, errors, null);
    }
    public SnapshotDiskCache(Path directory, String fingerprint, Limits limits, Consumer<String> errors, Path worldData) {
        this.directory = directory.toAbsolutePath().normalize(); this.fingerprint = fingerprint;
        this.worldData = worldData;
        this.limits = limits; this.errors = errors;
        worker = new Thread(this::run, "taczlrs-snapshot-io"); worker.setDaemon(true); worker.start();
    }
    public synchronized Status status() {
        return new Status(ready, failed, index.size(), diskBytes, jobs.size(), reserved, reads, writes, dropped, corrupt, evicted, error);
    }
    public synchronized boolean known(SectionKey key) { return ready && !failed && index.containsKey(key); }
    public synchronized void request(SectionKey key) {
        if (!ready || failed || closing || index.get(key) == null || jobs.containsKey(key) || deletes.contains(key)) return;
        enqueue(new Job(key, null));
    }
    public synchronized void save(SectionSnapshot snapshot) {
        if (failed || closing) return;
        invalidate(snapshot.key()); // An omitted replacement must never leave an older disk record authoritative.
        enqueue(new Job(snapshot.key(), snapshot));
    }
    /** Retry an omitted write without restarting a job already queued for this address. */
    public synchronized void retrySave(SectionSnapshot snapshot) {
        if (ready && !failed && !closing && !index.containsKey(snapshot.key()) && !jobs.containsKey(snapshot.key())
                && jobs.size() < limits.queueCount && reserved + snapshot.estimatedBytes() <= limits.queueBytes) save(snapshot);
    }
    private void enqueue(Job job) {
        if (failed || jobs.size() >= limits.queueCount || reserved + job.charge > limits.queueBytes) { dropped++; return; }
        jobs.put(job.key, job); reserved += job.charge; notifyAll();
    }
    public synchronized void invalidate(SectionKey key) {
        Entry old = index.remove(key);
        if (old != null) diskBytes -= old.bytes;
        Job job = jobs.remove(key);
        if (job != null && !job.running) reserved -= job.charge;
        if (old != null || job != null || !ready) {
            if (deletes.size() >= limits.sections && !deletes.contains(key)) { fail("Invalidation index exhausted"); return; }
            deletes.add(key); notifyAll();
        }
    }
    /** Bounded hand-off to the server; an invalidation removes a completed read before it can be consumed. */
    public synchronized List<SectionSnapshot> drain(int maximum) {
        var result = new ArrayList<SectionSnapshot>();
        var iterator = jobs.values().iterator();
        while (iterator.hasNext() && result.size() < maximum) {
            Job job = iterator.next();
            if (job.complete) {
                iterator.remove(); reserved -= job.charge;
                if (job.result != null) result.add(job.result);
            }
        }
        notifyAll(); return result;
    }
    private synchronized void fail(String message) {
        failed = true; error = message; index.clear(); diskBytes = 0;
        for (var job : jobs.values()) if (!job.running) reserved -= job.charge;
        jobs.clear(); deletes.clear();
        errors.accept(message); notifyAll();
    }
    private void run() {
        try {
            initialize();
            while (true) {
                SectionKey deletion = null; Job job = null;
                synchronized (this) {
                    if (abort || failed) break;
                    if (!deletes.isEmpty()) { deletion = deletes.iterator().next(); deletes.remove(deletion); }
                    else {
                        for (Job candidate : jobs.values()) if (!candidate.running && !candidate.complete) {
                            candidate.running = true; job = candidate; break;
                        }
                    }
                    if (deletion == null && job == null) {
                        if (closing) break;
                        wait(50); continue;
                    }
                }
                if (deletion != null) { throttle(0); Files.deleteIfExists(path(deletion)); }
                else process(job);
            }
            synchronized (this) { if (abort || failed) return; }
            // Only a drained, orderly stop can declare the disk cache reusable next session.
            Files.writeString(directory.resolve("format.txt"), fingerprintNow(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(directory.resolve("session.dirty"));
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (IOException | RuntimeException failure) { fail(failure.toString()); }
    }
    private void initialize() throws IOException, InterruptedException {
        if (Files.isSymbolicLink(directory)) throw new IOException("Cache directory is a symbolic link");
        Files.createDirectories(directory);
        Path manifest = directory.resolve("format.txt"), dirty = directory.resolve("session.dirty");
        boolean reusable = Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) && Files.size(manifest) < 4096
                && Files.readString(manifest).equals(fingerprintNow()) && !Files.exists(dirty, LinkOption.NOFOLLOW_LINKS);
        // Force the dirty marker before exposing records or accepting world changes. Crash => unknown next startup.
        Files.writeString(dirty, "in progress", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try (var channel = FileChannel.open(dirty, StandardOpenOption.WRITE)) { channel.force(true); }
        var scanned = new TreeMap<Long, List<SectionKey>>();
        var entries = new HashMap<SectionKey, Entry>();
        long bytes = 0;
        try (var files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                String name = file.getFileName().toString();
                if (!name.endsWith(".snap") && !name.endsWith(".tmp")) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                SectionKey key = parse(name);
                long size = Files.size(file);
                if (!reusable || key == null || size > SnapshotCodec.MAX_BYTES || size > limits.diskBytes) { Files.delete(file); continue; }
                long modified = Files.getLastModifiedTime(file).toMillis();
                entries.put(key, new Entry(size, modified)); bytes += size;
                scanned.computeIfAbsent(modified, ignored -> new ArrayList<>()).add(key);
                while (entries.size() > limits.sections || bytes > limits.diskBytes) {
                    var first = scanned.firstEntry(); var removed = first.getValue().removeLast();
                    if (first.getValue().isEmpty()) scanned.pollFirstEntry();
                    bytes -= entries.remove(removed).bytes; Files.deleteIfExists(path(removed)); evicted++;
                }
            }
        }
        Files.writeString(manifest, fingerprintNow(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        synchronized (this) {
            for (var group : scanned.values()) for (var key : group) {
                if (deletes.contains(key) || jobs.containsKey(key)) continue;
                Entry entry = entries.get(key); index.put(key, entry); diskBytes += entry.bytes;
            }
            ready = true; notifyAll();
        }
    }
    private String fingerprintNow() throws IOException {
        return fingerprint + (worldData == null ? "" : "\nworld=" + (Files.isRegularFile(worldData)
                ? Files.getLastModifiedTime(worldData) + ":" + Files.size(worldData) : "new"));
    }
    private void process(Job job) throws IOException, InterruptedException {
        SectionSnapshot restored = null;
        try {
            if (job.write == null) {
                Path file = path(job.key);
                long size = Files.size(file);
                if (size > SnapshotCodec.MAX_BYTES) throw new IOException("Oversized snapshot file");
                throttle(size);
                restored = SnapshotCodec.decode(Files.readAllBytes(file), job.key);
                synchronized (this) { reads++; }
            } else {
                byte[] bytes = SnapshotCodec.encode(job.write);
                throttle(bytes.length);
                if (!current(job)) return;
                if (bytes.length * 2L > limits.diskBytes) { synchronized (this) { dropped++; } return; }
                trimFor(bytes.length * 2L); // Include one temporary file in the byte budget.
                Path temporary = directory.resolve(path(job.key).getFileName() + ".tmp");
                Files.write(temporary, bytes);
                if (!current(job)) { Files.deleteIfExists(temporary); return; }
                try { Files.move(temporary, path(job.key), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException unavailable) { Files.move(temporary, path(job.key), StandardCopyOption.REPLACE_EXISTING); }
                synchronized (this) {
                    if (jobs.get(job.key) == job && !abort && !failed) {
                        Entry old = index.put(job.key, new Entry(bytes.length, System.currentTimeMillis()));
                        diskBytes += bytes.length - (old == null ? 0 : old.bytes); writes++;
                    } else deletes.add(job.key);
                }
            }
        } catch (IOException | IllegalArgumentException invalid) {
            synchronized (this) { corrupt++; Entry old = index.remove(job.key); if (old != null) diskBytes -= old.bytes; }
            Files.deleteIfExists(path(job.key));
            // Missing/corrupt data affects this address only. A failure to delete escalates to session failure.
        } finally {
            synchronized (this) {
                job.running = false;
                if (jobs.get(job.key) != job) reserved -= job.charge;
                else if (job.write != null || restored == null || closing || failed) {
                    jobs.remove(job.key); reserved -= job.charge;
                } else { job.result = restored; job.complete = true; }
                notifyAll();
            }
        }
    }
    private synchronized boolean current(Job job) { return jobs.get(job.key) == job && !abort && !failed; }
    private void trimFor(long bytes) throws IOException {
        while (true) {
            SectionKey remove;
            synchronized (this) {
                if (index.size() < limits.sections && diskBytes + bytes <= limits.diskBytes) return;
                if (index.isEmpty()) return;
                remove = index.keySet().iterator().next(); diskBytes -= index.remove(remove).bytes; evicted++;
            }
            Files.deleteIfExists(path(remove));
        }
    }
    private void throttle(long bytes) throws InterruptedException {
        long now = System.nanoTime(), due = Math.max(nextOperation, nextBytes);
        while (due > now) {
            Thread.sleep(Math.min(50, Math.max(1, (due - now) / 1_000_000)));
            synchronized (this) { if (abort || failed) throw new InterruptedException(); }
            now = System.nanoTime();
        }
        nextOperation = Math.max(now, nextOperation) + 1_000_000_000L / limits.operationsPerSecond;
        nextBytes = Math.max(now, nextBytes) + bytes * 1_000_000_000L / limits.bytesPerSecond;
    }
    public Path path(SectionKey key) {
        return directory.resolve(Base64.getUrlEncoder().withoutPadding().encodeToString(key.dimension().getBytes(StandardCharsets.UTF_8))
                + "." + key.x() + "." + key.y() + "." + key.z() + ".snap");
    }
    private static SectionKey parse(String name) {
        try {
            String[] parts = name.split("\\.");
            if (parts.length != 5 || !parts[4].equals("snap")) return null;
            String dimension = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            if (dimension.length() > 256 || !dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) return null;
            return new SectionKey(dimension, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (IllegalArgumentException invalid) { return null; }
    }
    @Override public void close() {
        synchronized (this) {
            closing = true;
            var iterator = jobs.values().iterator();
            while (iterator.hasNext()) { var job = iterator.next(); if (job.write == null && !job.running) { iterator.remove(); reserved -= job.charge; } }
            notifyAll();
        }
        try {
            worker.join(10_000);
            if (worker.isAlive()) { synchronized (this) { abort = true; } worker.interrupt(); worker.join(500); }
        } catch (InterruptedException interrupted) { synchronized (this) { abort = true; } worker.interrupt(); Thread.currentThread().interrupt(); }
    }
}
