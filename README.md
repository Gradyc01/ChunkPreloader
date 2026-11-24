# ChunkPreloader (Paper 26.3)
Build (JDK 25): `./gradlew build` -> `build/libs/ChunkPreloader-1.0.0.jar`, drop in `plugins/`.
Edit `plugins/ChunkPreloader/config.yml`, then run `/start-load` (console works).

Commands:
- `/start-load [all|world|nether|end] [sizeX sizeZ]` (no argument = overworld only)
- `/stop-load`
- `/load-speed [concurrent|per-tick|interval] [value]`

Biome/structure results: `plugins/ChunkPreloader/scan-results.json`
(per-dimension copies in `plugins/ChunkPreloader/scan/`).
