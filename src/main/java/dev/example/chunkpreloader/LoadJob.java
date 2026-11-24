package dev.example.chunkpreloader;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Preloads ONE dimension: walks the area row by row and keeps a bounded number of async chunk
 * loads in flight. Chunks are not ticketed after loading, so the server can unload (and save)
 * them normally and memory stays flat no matter how large the area is.
 * Calls onDone once every chunk has finished (not when cancelled).
 */
final class LoadJob {

    private final ChunkPreloaderPlugin plugin;
    private final Plan plan;
    private final World world;
    private final AreaScanner scanner; // may be null
    private final MapCacheBuilder mapCache; // may be null
    private final Speed speed;
    private final boolean generate;
    private final int progressSeconds;
    private final Runnable onDone;
    private final long total;

    private long next = 0;                       // only touched on the main thread
    private long tickCounter = 0;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private boolean scanErrorLogged = false;

    private BukkitTask task;
    private volatile boolean running;
    private long startNanos, lastLogNanos;

    LoadJob(ChunkPreloaderPlugin plugin, Plan plan, AreaScanner scanner, MapCacheBuilder mapCache, Speed speed,
            boolean generate, int progressSeconds, Runnable onDone) {
        this.plugin = plugin;
        this.plan = plan;
        this.world = plan.world();
        this.scanner = scanner;
        this.mapCache = mapCache;
        this.speed = speed;
        this.generate = generate;
        this.progressSeconds = progressSeconds;
        this.onDone = onDone;
        this.total = plan.totalChunks();
    }

    void start() {
        running = true;
        startNanos = lastLogNanos = System.nanoTime();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void cancel() {
        running = false;
        if (task != null) task.cancel();
    }

    boolean isRunning() {
        return running;
    }

    private void tick() {
        if (!running) return;

        if (completed.get() >= total) {
            finish();
            return;
        }

        if (++tickCounter % speed.tickInterval == 0) {
            int submitted = 0;
            while (next < total && submitted < speed.chunksPerTick && inFlight.get() < speed.maxConcurrent) {
                long i = next++;
                int cx = plan.startChunkX() + (int) (i % plan.sizeX());
                int cz = plan.startChunkZ() + (int) (i / plan.sizeX());
                inFlight.incrementAndGet();
                submitted++;
                world.getChunkAtAsync(cx, cz, generate).whenComplete((chunk, ex) -> onMain(() -> {
                    // chunk is null if generate=false and the chunk doesn't exist: counts as done
                    if (ex != null) {
                        failed.incrementAndGet();
                        plugin.getLogger().warning("Failed chunk " + cx + "," + cz + ": " + ex);
                    } else if (chunk != null) {
                        try {
                            if (scanner != null) scanner.scanChunk(cx, cz);
                            if (mapCache != null) mapCache.addChunk(chunk);
                        } catch (Throwable t) {
                            if (!scanErrorLogged) {
                                scanErrorLogged = true;
                                plugin.getLogger().warning("Scan/map capture failed at chunk " + cx + "," + cz
                                        + " (further errors hidden): " + t);
                            }
                        }
                    }
                    inFlight.decrementAndGet();
                    completed.incrementAndGet();
                }));
            }
        }

        long now = System.nanoTime();
        if (now - lastLogNanos >= progressSeconds * 1_000_000_000L) {
            lastLogNanos = now;
            logProgress(now);
        }
    }

    private void onMain(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(plugin, r);
    }

    private void logProgress(long now) {
        long done = completed.get();
        double secs = (now - startNanos) / 1e9;
        double rate = done / Math.max(secs, 0.001);
        double eta = rate > 0 ? (total - done) / rate : 0;
        plugin.getLogger().info(String.format(
                "[%s] %d/%d (%.1f%%) | %.1f chunks/s | in flight %d | ETA %s",
                plan.dimension().key, done, total, 100.0 * done / total, rate, inFlight.get(), fmt(eta)));
    }

    private void finish() {
        running = false;
        task.cancel();
        double secs = (System.nanoTime() - startNanos) / 1e9;
        plugin.getLogger().info(String.format("[%s] Done: %d chunks in %s (%d failed).",
                plan.dimension().key, total, fmt(secs), failed.get()));
        onDone.run();
    }

    private static String fmt(double s) {
        long t = (long) s;
        return String.format("%dh %02dm %02ds", t / 3600, (t % 3600) / 60, t % 60);
    }
}
