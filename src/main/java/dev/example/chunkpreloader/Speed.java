package dev.example.chunkpreloader;

/** Shared, live-tunable speed settings (the same object is used for every dimension in a run). */
final class Speed {
    volatile int maxConcurrent;
    volatile int chunksPerTick;
    volatile int tickInterval;

    Speed(int maxConcurrent, int chunksPerTick, int tickInterval) {
        this.maxConcurrent = maxConcurrent;
        this.chunksPerTick = chunksPerTick;
        this.tickInterval = tickInterval;
    }

    String summary() {
        return "concurrent=" + maxConcurrent + ", per-tick=" + chunksPerTick
                + ", interval=" + tickInterval;
    }
}
