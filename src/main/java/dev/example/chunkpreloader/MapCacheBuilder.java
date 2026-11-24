package dev.example.chunkpreloader;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.map.MapPalette;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Records the "map view" of every column in the scan square while chunks are preloaded
 * (top map-coloured block, its height, water depth), then renders it the way vanilla filled
 * maps do for all 5 scales and writes one binary file per scale.
 * Each output byte is a ready-to-use map colour byte (baseColorId * 4 + shade, 0 = transparent).
 * Must be called from the main thread.
 */
final class MapCacheBuilder {

    static final int MAGIC = 0x43504D43;   // "CPMC"
    static final int VERSION = 1;
    static final int SCALES = 5;           // vanilla scales 0..4

    private final World world;
    private final int minX, maxX, minZ, maxZ, width, height;

    private final byte[] base;     // map base colour id per column (0 = none/unscanned)
    private final short[] surfaceY; // y of the top map-coloured block
    private final byte[] depth;    // water depth under the surface (0 if not water)

    private final Map<BlockData, Byte> idCache = new HashMap<>();
    private final byte waterId;

    MapCacheBuilder(World world, int centerX, int centerZ, int radius) {
        this.world = world;
        this.minX = centerX - radius;
        this.maxX = centerX + radius;
        this.minZ = centerZ - radius;
        this.maxZ = centerZ + radius;
        this.width = maxX - minX + 1;
        this.height = maxZ - minZ + 1;
        int n = width * height;
        this.base = new byte[n];
        this.surfaceY = new short[n];
        this.depth = new byte[n];
        this.waterId = baseId(Bukkit.createBlockData(Material.WATER));
    }

    static boolean fits(int radius) {
        long side = 2L * radius + 1;
        return side * side <= 60_000_000L; // ~240 MB of arrays at the limit
    }

    // ------------------------------------------------------------------ capture

    void addChunk(Chunk chunk) {
        int cx = chunk.getX() << 4, cz = chunk.getZ() << 4;
        if (cx + 15 < minX || cx > maxX || cz + 15 < minZ || cz > maxZ) return;

        ChunkSnapshot snap = chunk.getChunkSnapshot();
        int minY = world.getMinHeight();

        for (int lz = 0; lz < 16; lz++) {
            int bz = cz + lz;
            if (bz < minZ || bz > maxZ) continue;
            for (int lx = 0; lx < 16; lx++) {
                int bx = cx + lx;
                if (bx < minX || bx > maxX) continue;
                int idx = (bz - minZ) * width + (bx - minX);

                int y = snap.getHighestBlockYAt(lx, lz);
                if (y < minY) continue; // empty column

                BlockData bd = snap.getBlockData(lx, y, lz);
                byte id = baseId(bd);
                while (id == 0 && y > minY) {   // skip blocks with no map colour (air, glass, ...)
                    y--;
                    bd = snap.getBlockData(lx, y, lz);
                    id = baseId(bd);
                }
                if (id == 0) continue;

                int d = 0;
                int fluid = fluidKind(bd);
                if (fluid != 0) {
                    d = 1;
                    for (int yy = y - 1; yy > minY && fluidKind(snap.getBlockData(lx, yy, lz)) != 0; yy--) d++;
                    if (fluid == 1) id = waterId; // waterlogged blocks/kelp/seagrass render as water
                }
                base[idx] = id;
                surfaceY[idx] = (short) y;
                depth[idx] = (byte) Math.min(d, 255);
            }
        }
    }

    /** 0 = no fluid, 1 = water, 2 = lava */
    private static int fluidKind(BlockData bd) {
        Material m = bd.getMaterial();
        if (m == Material.LAVA) return 2;
        if (m == Material.WATER || m == Material.BUBBLE_COLUMN || m == Material.KELP
                || m == Material.KELP_PLANT || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS) return 1;
        if (bd instanceof Waterlogged w && w.isWaterlogged()) return 1;
        return 0;
    }

    @SuppressWarnings("deprecation")
    private byte baseId(BlockData bd) {
        Byte cached = idCache.get(bd);
        if (cached != null) return cached;
        int rgb = bd.getMapColor().asRGB() & 0xFFFFFF;
        byte id = 0;
        if (rgb != 0) { // black (0,0,0) is vanilla's "no colour"
            // Vanilla's NORMAL shade is base * 220/255. Matching that exact colour in the palette
            // returns (baseId * 4 + 1), so shifting by 2 gives the base id.
            int r = ((rgb >> 16) & 0xFF) * 220 / 255;
            int g = ((rgb >> 8) & 0xFF) * 220 / 255;
            int b = (rgb & 0xFF) * 220 / 255;
            id = (byte) ((MapPalette.matchColor(new java.awt.Color(r, g, b)) & 0xFF) >> 2);
        }
        idCache.put(bd, id);
        return id;
    }

    // ------------------------------------------------------------------ render + write

    void write(Path dir) throws IOException {
        Files.createDirectories(dir);
        StringBuilder levels = new StringBuilder();
        for (int s = 0; s < SCALES; s++) {
            int bpp = 1 << s;
            int pxMin = Math.floorDiv(minX, bpp), pxMax = Math.floorDiv(maxX, bpp);
            int pzMin = Math.floorDiv(minZ, bpp), pzMax = Math.floorDiv(maxZ, bpp);
            int pw = pxMax - pxMin + 1, ph = pzMax - pzMin + 1;
            byte[] out = render(s, pxMin, pzMin, pw, ph);

            String file = "overworld_scale" + s + ".bin";
            try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(dir.resolve(file)), 1 << 16))) {
                o.writeInt(MAGIC);
                o.writeInt(VERSION);
                o.writeInt(s);
                o.writeInt(pxMin);
                o.writeInt(pzMin);
                o.writeInt(pw);
                o.writeInt(ph);
                o.write(out);
            }
            if (s > 0) levels.append(",\n");
            levels.append("    {\"scale\": ").append(s).append(", \"blocksPerPixel\": ").append(bpp)
                    .append(", \"file\": \"").append(file).append("\", \"originPixelX\": ").append(pxMin)
                    .append(", \"originPixelZ\": ").append(pzMin).append(", \"width\": ").append(pw)
                    .append(", \"height\": ").append(ph).append("}");
        }

        String meta = "{\n"
                + "  \"formatVersion\": " + VERSION + ",\n"
                + "  \"world\": \"" + world.getName().replace("\\", "\\\\").replace("\"", "\\\"") + "\",\n"
                + "  \"generatedAt\": \"" + Instant.now() + "\",\n"
                + "  \"bounds\": {\"minX\": " + minX + ", \"maxX\": " + maxX
                + ", \"minZ\": " + minZ + ", \"maxZ\": " + maxZ + "},\n"
                + "  \"headerBytes\": 28,\n"
                + "  \"levels\": [\n" + levels + "\n  ]\n}\n";
        Files.writeString(dir.resolve("meta.json"), meta);
    }

    /** Renders one scale like vanilla's MapItem.update: modal colour + height/depth shading. */
    private byte[] render(int scale, int pxMin, int pzMin, int pw, int ph) {
        int bpp = 1 << scale;
        byte[] out = new byte[pw * ph];
        double[] northAvg = new double[pw];
        java.util.Arrays.fill(northAvg, Double.NaN);
        int[] counts = new int[256];

        for (int pz = pzMin; pz < pzMin + ph; pz++) {
            int bz0 = Math.max(pz * bpp, minZ), bz1 = Math.min(pz * bpp + bpp - 1, maxZ);
            for (int px = pxMin; px < pxMin + pw; px++) {
                int bx0 = Math.max(px * bpp, minX), bx1 = Math.min(px * bpp + bpp - 1, maxX);

                java.util.Arrays.fill(counts, 0);
                long hSum = 0, dSum = 0;
                int n = 0;
                for (int bz = bz0; bz <= bz1; bz++) {
                    int row = (bz - minZ) * width - minX;
                    for (int bx = bx0; bx <= bx1; bx++) {
                        int i = row + bx;
                        int id = base[i] & 0xFF;
                        if (id == 0) continue;
                        counts[id]++;
                        hSum += surfaceY[i];
                        dSum += depth[i] & 0xFF;
                        n++;
                    }
                }
                int col = px - pxMin;
                if (n == 0) {
                    northAvg[col] = Double.NaN;
                    continue; // stays 0 = transparent
                }

                int best = 1;
                for (int id = 2; id < 256; id++) if (counts[id] > counts[best]) best = id;
                double avgH = hSum / (double) n;
                double avgD = dSum / (double) n;
                int parity = (px + pz) & 1;

                int shade; // vanilla ids: 0 = LOW, 1 = NORMAL, 2 = HIGH
                if (best == (waterId & 0xFF)) {
                    double d = avgD * 0.1 + parity * 0.2;
                    shade = d < 0.5 ? 2 : (d > 0.9 ? 0 : 1);
                } else {
                    double north = Double.isNaN(northAvg[col]) ? avgH : northAvg[col];
                    double d = (avgH - north) * 4.0 / (bpp + 4) + (parity - 0.5) * 0.4;
                    shade = d > 0.6 ? 2 : (d < -0.6 ? 0 : 1);
                }
                northAvg[col] = avgH;
                out[(pz - pzMin) * pw + col] = (byte) (best * 4 + shade);
            }
        }
        return out;
    }
}
