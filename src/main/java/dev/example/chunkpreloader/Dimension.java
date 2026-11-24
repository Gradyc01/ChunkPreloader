package dev.example.chunkpreloader;

import java.util.List;

enum Dimension {
    OVERWORLD("overworld"),
    NETHER("nether"),
    END("end");

    /** Key used under "dimensions:" in config.yml and as the key in the JSON output. */
    final String key;

    Dimension(String key) {
        this.key = key;
    }

    /** Parses the first /start-load argument. Returns null if it isn't a dimension selector. */
    static List<Dimension> parse(String arg) {
        return switch (arg.toLowerCase()) {
            case "all" -> List.of(OVERWORLD, NETHER, END);
            case "world", "overworld" -> List.of(OVERWORLD);
            case "nether" -> List.of(NETHER);
            case "end", "the_end" -> List.of(END);
            default -> null;
        };
    }
}
