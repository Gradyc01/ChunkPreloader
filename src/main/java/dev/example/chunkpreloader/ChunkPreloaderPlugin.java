package dev.example.chunkpreloader;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class ChunkPreloaderPlugin extends JavaPlugin {

    private final Deque<Plan> queue = new ArrayDeque<>();
    private final Speed speed = new Speed(32, 8, 1);

    private LoadJob job;
    private Plan currentPlan;
    private AreaScanner currentScanner;
    private MapCacheBuilder currentMapCache;
    private boolean sessionActive;

    // Settings captured when /start-load runs
    private boolean generate;
    private int progressSeconds;
    private boolean shutdownWhenDone;
    private boolean scanEnabled;
    private int scanYStep;
    private String outputFile;
    private boolean mapCacheEnabled;
    private String mapCacheDir;

    @Override
    public void onEnable() {
        saveDefaultConfig();
    }

    @Override
    public void onDisable() {
        if (job != null) job.cancel();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        switch (command.getName().toLowerCase()) {
            case "start-load" -> start(sender, args);
            case "stop-load" -> stop(sender);
            case "load-speed" -> speed(sender, args);
        }
        return true;
    }

    // ------------------------------------------------------------------ /start-load

    private static final String START_USAGE = "Usage: /start-load [all|world|nether|end] [sizeX sizeZ]";

    private void start(CommandSender sender, String[] args) {
        if (sessionActive) {
            sender.sendMessage("A load is already running.");
            return;
        }
        reloadConfig();
        FileConfiguration cfg = getConfig();

        if (cfg.getBoolean("require-no-players", true) && !Bukkit.getOnlinePlayers().isEmpty()) {
            sender.sendMessage("Players are online; refusing to start (see require-no-players).");
            return;
        }

        // --- which dimensions? (no argument = overworld only)
        List<Dimension> dims = List.of(Dimension.OVERWORLD);
        int idx = 0;
        if (args.length > 0 && !isInt(args[0])) {
            dims = Dimension.parse(args[0]);
            if (dims == null) {
                sender.sendMessage(START_USAGE);
                return;
            }
            idx = 1;
        }

        // --- optional size override (chunks), only when a single dimension is selected
        Integer overrideX = null, overrideZ = null;
        int rest = args.length - idx;
        if (rest == 2 && isInt(args[idx]) && isInt(args[idx + 1])) {
            if (dims.size() > 1) {
                sender.sendMessage("A size override only works with a single dimension.");
                return;
            }
            overrideX = Integer.parseInt(args[idx]);
            overrideZ = Integer.parseInt(args[idx + 1]);
        } else if (rest != 0) {
            sender.sendMessage(START_USAGE);
            return;
        }

        // --- build a plan per dimension
        List<Plan> plans = new ArrayList<>();
        for (Dimension d : dims) {
            ConfigurationSection sec = cfg.getConfigurationSection("dimensions." + d.key);
            if (sec == null) {
                sender.sendMessage("config.yml has no 'dimensions." + d.key + "' section.");
                return;
            }
            String worldName = sec.getString("world", "");
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                sender.sendMessage("World '" + worldName + "' (" + d.key + ") not found. Loaded worlds: "
                        + Bukkit.getWorlds().stream().map(World::getName).toList());
                return;
            }
            int sizeX = overrideX != null ? overrideX : sec.getInt("area.size-x", 100);
            int sizeZ = overrideZ != null ? overrideZ : sec.getInt("area.size-z", 100);
            if (sizeX < 1 || sizeZ < 1) {
                sender.sendMessage("Sizes must be at least 1 (" + d.key + ").");
                return;
            }
            plans.add(new Plan(d, world,
                    sec.getInt("area.start-chunk-x", 0), sec.getInt("area.start-chunk-z", 0),
                    sizeX, sizeZ,
                    sec.getInt("scan.center-x", 0), sec.getInt("scan.center-z", 0),
                    Math.max(0, sec.getInt("scan.radius", 1024))));
        }

        // --- settings
        generate = cfg.getBoolean("generate", true);
        progressSeconds = Math.max(1, cfg.getInt("progress-interval-seconds", 10));
        shutdownWhenDone = cfg.getBoolean("shutdown-when-done", true);
        scanEnabled = cfg.getBoolean("scan.enabled", true);
        scanYStep = cfg.getInt("scan.biome-y-step", 4);
        outputFile = cfg.getString("scan.output-file", "scan-results.json");
        mapCacheEnabled = cfg.getBoolean("map-cache.enabled", true);
        mapCacheDir = cfg.getString("map-cache.output-dir", "map-cache");
        speed.maxConcurrent = Math.max(1, cfg.getInt("speed.max-concurrent", 32));
        speed.chunksPerTick = Math.max(1, cfg.getInt("speed.chunks-per-tick", 8));
        speed.tickInterval = Math.max(1, cfg.getInt("speed.tick-interval", 1));

        queue.clear();
        queue.addAll(plans);
        sessionActive = true;

        StringBuilder msg = new StringBuilder("Started preload of:");
        for (Plan p : plans) {
            msg.append("\n - ").append(p.dimension().key).append(" ('").append(p.world().getName()).append("'): ")
                    .append(p.sizeX()).append("x").append(p.sizeZ()).append(" chunks (")
                    .append(p.totalChunks()).append(")");
        }
        sender.sendMessage(msg.toString());
        startNext();
    }

    private void startNext() {
        Plan p = queue.poll();
        if (p == null) {
            endSession();
            return;
        }
        currentPlan = p;
        currentScanner = scanEnabled
                ? new AreaScanner(p.world(), p.scanCenterX(), p.scanCenterZ(), p.scanRadius(), scanYStep)
                : null;
        currentMapCache = null;
        if (mapCacheEnabled && p.dimension() == Dimension.OVERWORLD) {
            if (MapCacheBuilder.fits(p.scanRadius())) {
                currentMapCache = new MapCacheBuilder(p.world(), p.scanCenterX(), p.scanCenterZ(), p.scanRadius());
            } else {
                getLogger().warning("map-cache skipped: scan radius " + p.scanRadius() + " is too large.");
            }
        }
        getLogger().info("Starting " + p.dimension().key + " ('" + p.world().getName() + "')");
        job = new LoadJob(this, p, currentScanner, currentMapCache, speed, generate, progressSeconds, this::onJobDone);
        job.start();
    }

    private void onJobDone() {
        currentPlan.world().save();
        writeScan(currentPlan, currentScanner, true);
        if (currentMapCache != null) {
            try {
                getLogger().info("Rendering map cache (5 scales)...");
                currentMapCache.write(getDataFolder().toPath().resolve(mapCacheDir));
                getLogger().info("Wrote map cache to plugins/ChunkPreloader/" + mapCacheDir);
            } catch (IOException e) {
                getLogger().severe("Could not write map cache: " + e);
            }
            currentMapCache = null; // free the arrays
        }
        startNext();
    }

    private void endSession() {
        sessionActive = false;
        job = null;
        getLogger().info("All requested dimensions finished.");
        if (shutdownWhenDone) {
            getLogger().info("Saving worlds and shutting down...");
            for (World w : Bukkit.getWorlds()) w.save();
            Bukkit.shutdown();
        }
    }

    // ------------------------------------------------------------------ /stop-load

    private void stop(CommandSender sender) {
        if (!sessionActive) {
            sender.sendMessage("No load is running.");
            return;
        }
        job.cancel();
        queue.clear();
        sessionActive = false;
        writeScan(currentPlan, currentScanner, false); // partial results, "complete": false
        currentMapCache = null; // an aborted run does not write a (partial) map cache
        sender.sendMessage("Load aborted. Server will NOT shut down automatically.");
    }

    // ------------------------------------------------------------------ JSON output

    /**
     * Each dimension is saved to plugins/ChunkPreloader/scan/<dimension>.json, then the combined
     * file is rebuilt from whichever of those exist. That way running "/start-load nether" later
     * doesn't wipe out the overworld results.
     */
    private void writeScan(Plan plan, AreaScanner scanner, boolean complete) {
        if (scanner == null) return;
        try {
            Path dir = getDataFolder().toPath().resolve("scan");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(plan.dimension().key + ".json"), scanner.toJson(complete));

            StringBuilder all = new StringBuilder("{");
            boolean first = true;
            for (Dimension d : Dimension.values()) {
                Path f = dir.resolve(d.key + ".json");
                if (!Files.exists(f)) continue;
                all.append(first ? "\n" : ",\n");
                first = false;
                all.append("  \"").append(d.key).append("\": ").append(Files.readString(f).replace("\n", "\n  "));
            }
            all.append(first ? "}" : "\n}");
            Files.writeString(getDataFolder().toPath().resolve(outputFile), all.toString());
            getLogger().info("Wrote scan results to plugins/ChunkPreloader/" + outputFile);
        } catch (IOException e) {
            getLogger().severe("Could not write scan results: " + e);
        }
    }

    // ------------------------------------------------------------------ /load-speed

    private void speed(CommandSender sender, String[] args) {
        if (!sessionActive) {
            sender.sendMessage("No load is running. Edit config.yml to change the defaults.");
            return;
        }
        if (args.length == 0) {
            sender.sendMessage(speed.summary());
            return;
        }
        if (args.length != 2) {
            sender.sendMessage("Usage: /load-speed [concurrent|per-tick|interval] [value]");
            return;
        }
        if (!isInt(args[1]) || Integer.parseInt(args[1]) < 1) {
            sender.sendMessage("Value must be a whole number >= 1.");
            return;
        }
        int v = Integer.parseInt(args[1]);
        switch (args[0].toLowerCase()) {
            case "concurrent" -> speed.maxConcurrent = v;
            case "per-tick" -> speed.chunksPerTick = v;
            case "interval" -> speed.tickInterval = v;
            default -> {
                sender.sendMessage("Unknown parameter. Use concurrent, per-tick or interval.");
                return;
            }
        }
        sender.sendMessage("Updated. " + speed.summary());
    }

    private static boolean isInt(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        String name = command.getName().toLowerCase();
        if (name.equals("load-speed") && args.length == 1) return List.of("concurrent", "per-tick", "interval");
        if (name.equals("start-load") && args.length == 1) return List.of("all", "world", "nether", "end");
        return List.of();
    }
}
