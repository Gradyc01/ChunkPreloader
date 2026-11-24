package dev.example.chunkpreloader;

import org.bukkit.World;
import org.bukkit.generator.structure.GeneratedStructure;
import org.bukkit.util.BoundingBox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Records every biome and structure found inside a square (centre +/- radius, in blocks)
 * while chunks are being preloaded. Must be called from the main thread.
 */
final class AreaScanner {

    private static final class BiomeStat {
        long chunks;
        int x, y, z; // first place this biome was seen
    }

    private final World world;
    private final int centerX, centerZ, radius, yStep;
    private final int minX, maxX, minZ, maxZ;

    private final Map<String, BiomeStat> biomes = new TreeMap<>();
    private final Map<String, List<int[]>> structures = new TreeMap<>();
    private final Set<String> seenStructures = new HashSet<>();

    AreaScanner(World world, int centerX, int centerZ, int radius, int yStep) {
        this.world = world;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        this.yStep = Math.max(4, yStep);
        this.minX = centerX - radius;
        this.maxX = centerX + radius;
        this.minZ = centerZ - radius;
        this.maxZ = centerZ + radius;
    }

    void scanChunk(int cx, int cz) {
        int bx0 = cx << 4, bz0 = cz << 4;
        if (bx0 + 15 < minX || bx0 > maxX || bz0 + 15 < minZ || bz0 > maxZ) return;
        scanBiomes(bx0, bz0);
        scanStructures(cx, cz);
    }

    private void scanBiomes(int bx0, int bz0) {
        Set<String> seenHere = new HashSet<>();
        int yMin = world.getMinHeight(), yMax = world.getMaxHeight();
        // Biomes are stored in 4x4x4 cells, so sampling the centre of each cell sees them all.
        for (int x = bx0 + 2; x < bx0 + 16; x += 4) {
            if (x < minX || x > maxX) continue;
            for (int z = bz0 + 2; z < bz0 + 16; z += 4) {
                if (z < minZ || z > maxZ) continue;
                for (int y = yMin + 2; y < yMax; y += yStep) {
                    String key = world.getBiome(x, y, z).key().asString();
                    if (seenHere.add(key)) {
                        BiomeStat s = biomes.computeIfAbsent(key, k -> new BiomeStat());
                        if (s.chunks++ == 0) {
                            s.x = x;
                            s.y = y;
                            s.z = z;
                        }
                    }
                }
            }
        }
    }

    private void scanStructures(int cx, int cz) {
        for (GeneratedStructure gs : world.getStructures(cx, cz)) {
            BoundingBox bb = gs.getBoundingBox();
            String type = gs.getStructure().key().asString();
            String id = type + "@" + (int) bb.getMinX() + "," + (int) bb.getMinY() + "," + (int) bb.getMinZ();
            if (!seenStructures.add(id)) continue; // same structure seen from several chunks
            int x = (int) Math.floor(bb.getCenterX());
            int y = (int) Math.floor(bb.getCenterY());
            int z = (int) Math.floor(bb.getCenterZ());
            if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
            structures.computeIfAbsent(type, k -> new ArrayList<>()).add(new int[]{x, y, z});
        }
    }

    String toJson(boolean complete) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\n");
        sb.append("  \"world\": \"").append(esc(world.getName())).append("\",\n");
        sb.append("  \"complete\": ").append(complete).append(",\n");
        sb.append("  \"generatedAt\": \"").append(Instant.now()).append("\",\n");
        sb.append("  \"center\": {\"x\": ").append(centerX).append(", \"z\": ").append(centerZ).append("},\n");
        sb.append("  \"radius\": ").append(radius).append(",\n");
        sb.append("  \"bounds\": {\"minX\": ").append(minX).append(", \"maxX\": ").append(maxX)
                .append(", \"minZ\": ").append(minZ).append(", \"maxZ\": ").append(maxZ).append("},\n");

        sb.append("  \"biomes\": {");
        boolean first = true;
        for (Map.Entry<String, BiomeStat> e : biomes.entrySet()) {
            BiomeStat s = e.getValue();
            sb.append(first ? "\n" : ",\n");
            first = false;
            sb.append("    \"").append(esc(e.getKey())).append("\": {\"chunks\": ").append(s.chunks)
                    .append(", \"example\": {\"x\": ").append(s.x).append(", \"y\": ").append(s.y)
                    .append(", \"z\": ").append(s.z).append("}}");
        }
        sb.append(first ? "},\n" : "\n  },\n");

        sb.append("  \"structures\": {");
        first = true;
        for (Map.Entry<String, List<int[]>> e : structures.entrySet()) {
            sb.append(first ? "\n" : ",\n");
            first = false;
            sb.append("    \"").append(esc(e.getKey())).append("\": {\"count\": ").append(e.getValue().size())
                    .append(", \"locations\": [");
            boolean firstLoc = true;
            for (int[] p : e.getValue()) {
                if (!firstLoc) sb.append(", ");
                firstLoc = false;
                sb.append("{\"x\": ").append(p[0]).append(", \"y\": ").append(p[1])
                        .append(", \"z\": ").append(p[2]).append("}");
            }
            sb.append("]}");
        }
        sb.append(first ? "}\n" : "\n  }\n");
        sb.append("}");
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
