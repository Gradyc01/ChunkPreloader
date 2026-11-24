package dev.example.chunkpreloader;

import org.bukkit.World;

/** Everything needed to preload one dimension. Areas are in chunks, scan values in blocks. */
record Plan(Dimension dimension, World world,
            int startChunkX, int startChunkZ, int sizeX, int sizeZ,
            int scanCenterX, int scanCenterZ, int scanRadius) {

    long totalChunks() {
        return (long) sizeX * sizeZ;
    }
}
