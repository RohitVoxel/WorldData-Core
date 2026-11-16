# WorldData Core

**A read-side world data cache and query API for Minecraft 1.21.1 on Fabric and NeoForge.**

Minecraft loads and owns the world. WorldData Core (WDC) reads what Minecraft already holds, stores it in a compact cache, and lets any number of mods query it through one small API. Mods stop scanning chunks on their own and share one answer instead.

| | |
|---|---|
| Mod id | `worlddata_core` |
| Version / API version | `1.0.0` / `1.0` |
| Minecraft | 1.21.1 |
| Loaders | Fabric (Loader 0.19.2+ and Fabric API), NeoForge 21.1.x |
| Java | 21 |
| Side | Client, dedicated server, or both |
| API package | `net.wdc.core.api` (entry class `WDC`) |
| License | API: MIT. Everything else: LGPL-3.0-or-later |

## Contents

1. [Features](#features)
2. [How it works](#how-it-works)
3. [Getting started](#getting-started)
4. [API reference](#api-reference)
5. [Behaviour notes](#behaviour-notes)
6. [Performance and memory](#performance-and-memory)
7. [Hot sections and pins](#hot-sections-and-pins)
8. [Hot-read redirect (experimental)](#hot-read-redirect-experimental)
9. [Commands](#commands)
10. [Configuration](#configuration)
11. [Diagnostics](#diagnostics)
12. [Compatibility and limits](#compatibility-and-limits)
13. [License](#license)

## Features

- Reads blocks, fluids, biomes, heights, columns, light and water directly from the data Minecraft holds.
- Modded blocks, fluids and biomes behave like vanilla ones. WDC addresses everything by registry id and tag, so no per-mod support is needed.
- One shared build per chunk version for each derived layer (height map, column, block index, occupancy), however many mods ask.
- Pull API for reads at any time and a push feed that delivers block changes once per tick as one batch per dimension.
- Spatial queries across chunks: box, sphere, column and grid.
- A strict, configurable tick budget and fixed capacity limits, so the cache cannot grow or run without bound.
- Hot sections: frequently read sections are kept in a faster form inside a small memory budget, with operator pins.
- Built-in diagnostics: F3 lines, an API usage monitor, and timed test sessions that write MSPT reports.

## How it works

```
Minecraft (source of truth)
   |  chunk load, block change, tick
   v
WDC ingest (server thread, strict tick budget)
   v
Compact cache: chunk -> section -> palette + packed block indexes, plus heights, columns, biome
   |                                      |
   v  PULL: ask at any time               v  PUSH: one batch per dimension at the end of the tick
WDC.requests().chunk(...)                 WDC.changes().subscribe(...)
```

WDC always reads from the most accurate side available. A dedicated server reads its own loaded chunks. Singleplayer and LAN hosts read from the integrated server, so there is one cache that every thread can read. A client connected to a remote server reads the chunks that server sent it. WDC adds no networking of its own, and it never loads or generates a chunk to answer a query.

Every chunk result carries a state:

| State | Meaning |
|-------|---------|
| `LIVE` | Minecraft holds the chunk and the cache is current. |
| `STALE` | Minecraft no longer holds the chunk. The last known data is kept and may be old. |
| `PENDING` | The chunk is loaded in Minecraft but not ingested yet. The read is queued. |
| `UNAVAILABLE` | Nothing is known about the chunk. |

## Getting started

**For players and server owners:** install the jar for your loader next to the mods that require it. WDC has no gameplay content of its own.

**For mod authors:**

1. Put the WDC jar on your compile classpath.
2. Declare the dependency in your mod metadata. Fabric: add `"worlddata_core": "*"` to `depends` in `fabric.mod.json`. NeoForge: add a `[[dependencies.yourmod]]` block with `modId="worlddata_core"` and `type="required"` in `neoforge.mods.toml`.
3. Call the API from `net.wdc.core.api.WDC`.

```java
import net.wdc.core.api.WDC;
import net.wdc.core.api.request.*;

WDCChunkResult chunk = WDC.requests().chunk("minecraft:overworld", chunkX, chunkZ);
if (chunk.isUsable()) {                                   // LIVE or STALE
    String blockId = chunk.blockAt(x, y, z).blockId();    // "minecraft:stone" or "mymod:ruby_ore"
    int surface    = chunk.heightmap().topY(x & 15, z & 15);
    int ores       = chunk.countBlocksByTag("c:ores");
    var logs       = chunk.findBlocksByTag("minecraft:logs");
}
```

## API reference

| Entry point | Purpose |
|-------------|---------|
| `WDC.requests()` | `chunk`, `peek` and `availability`. Returns a `WDCChunkResult`. |
| `WDC.blocks()` | State id, block name, property and tag lookups. |
| `WDC.spatial()` | `summarizeBox`, `summarizeSphere`, `visitBox`, `visitSphere`, `visitColumn`, `sampleGrid`. |
| `WDC.light()` | Sky and block light: `lightAt` and `read`. |
| `WDC.world()` | Raw time, weather and moon values through `snapshot(dimension)`. |
| `WDC.changes()` | `subscribe`, `chunkVersion`, `markChunkDirty`, `markBlockDirty`. |
| `WDC.capabilities()` | Feature flags, so a mod can check what is available. |
| `WDC.coords()` | Coordinate helpers. |
| `WDC.extensions()` | Registry for optional data providers. |

### Reading a chunk

`WDCChunkResult` offers point reads (`blockAt`, `stateIdAt`, `materialAt`, `waterAt`, `isSubmerged`, `hasTagAt`, `matchesAt`), scans (`findBlocks`, `countBlocks`, `hasBlock`, `visitBlocks`, each also by tag, name and state id), and derived layers (`biome`, `heightmap`, `column`, `blockIndex`, `occupancy`). Block filters can be built from tags, names, state ids or `WDCBlockFilter`.

`WDC.requests().peek(...)` is the lightweight variant of `chunk(...)`. It reads the cache only and does not track acquisition status.

### Spatial queries

```java
var summary = WDC.spatial().summarizeSphere("minecraft:overworld", x, y, z, 24);
var grid    = WDC.spatial().sampleGrid("minecraft:overworld", minX, minZ, maxX, maxZ, 16);
```

### Pull and push

| Pipe | When | What you see |
|------|------|--------------|
| **Pull**: `requests().chunk`, `blockAt`, `countBlocks`, scans | Any time, including mid-tick | The cache right now, including block changes Minecraft has already reported this tick. |
| **Push**: `changes().subscribe(...)` or a `worlddata_core:changes` entrypoint | End of each tick | One coalesced batch per dimension. Several edits to one block become one change. |

```java
WDCSubscription subscription = WDC.changes().subscribe(WDCChangeFilter.all(), batch -> {
    if (batch.reset() || batch.truncated()) return;   // detail was dropped: pull again instead
    batch.chunks().forEach(chunk -> chunk.blockChanges().forEach(change -> {
        // change.oldBlockId() -> change.newBlockId() at change.x(), change.y(), change.z()
    }));
});
```

A mid-tick pull is best effort. For logic that must be exact at the same instant, read Minecraft directly. Edits that bypass `LevelChunk.setBlockState` (bulk editing tools, for example) are not seen until you call `WDC.changes().markChunkDirty(dimension, chunkX, chunkZ)`.

A mod can also receive changes without calling `subscribe` itself, by declaring a change entrypoint in its metadata.

- Fabric, `fabric.mod.json`: `"entrypoints": { "worlddata_core:changes": ["com.example.MyListener"] }`
- NeoForge, `neoforge.mods.toml`: `[modproperties.examplemod]` followed by `"worlddata_core:changes" = "com.example.MyListener"`

The class implements `net.wdc.core.api.changes.WDCChangeEntrypoint` and needs a public no-argument constructor.

### Stability of ids

State ids are internal and valid only inside one session. Persist the block id and its properties, not the id.

## Behaviour notes

**Light.** `WDC.light().lightAt(...)` answers live from the thread that owns the level: the server thread reads the server level and the client thread reads the client level, so the answer is exact at the moment of the call. Other threads, and client reads for chunks the client does not hold, are answered from a server snapshot kept in RAM only (never on disk). The server thread builds it inside the tick budget when a chunk loads, refreshes the sections around a block change, and drops it when the chunk unloads, so a loaded chunk answers from its first read and the answer is at most a few ticks behind. Sky darkening from time, rain and thunder is computed at read time from the current clock. `WDC.light().read(...)` returns the same data together with a reason when there is none: `PENDING` (a section that was never built, normally only right after a chunk loaded), `UNLOADED`, `NO_WORLD` or `UNAVAILABLE`. WDC never reads a server level from a non-server thread.

**Waterlogged blocks.** A waterlogged slab, stair or fence is one cell with one state, as in Minecraft. Block reads (`blockAt`, `stateIdAt`, `materialAt` and block filters) answer with the block. `waterAt` and `isSubmerged` answer with the water inside it. A block from another mod that holds a different fluid reports that fluid.

**Heights.** WDC builds the highest block of every column once per chunk when the chunk is read, and keeps it up to date on every block change, so a height read is one array lookup and never scans. Values are the Y of the top block. Minecraft's own `Level.getHeight` returns that Y plus one, so subtract or add one when mixing the two. A column with no matching block answers `UNKNOWN`. Air, cave air and void air never count as a block. The kinds follow Minecraft's own height predicates, using each block state's collision (`blocksMotion`), fluid and leaves data when the state is first seen, so modded blocks behave like vanilla ones:

- `WORLD_SURFACE` answers the highest block that is not air, so plants and other blocks without collision count as blocks.
- `MOTION_BLOCKING` answers the highest block that blocks motion or holds a fluid: plants are skipped, water, lava and waterlogged blocks count.
- `OCEAN_FLOOR` answers the highest block that blocks motion: fluids and plants are skipped, a waterlogged block counts.
- `MOTION_BLOCKING_NO_LEAVES` answers the same as `MOTION_BLOCKING` but skips leaves.
- `WDCColumn.topSolidY` answers the highest block that is not air or water, and `topWaterY` the highest water block.

**Modded content.** Blocks, fluids and biomes from other mods need no registration with WDC. Use their registry ids and tags.

## Performance and memory

WDC spends tick time only on asking Minecraft for chunk data, building its cache, and answering other mods.

| Setting (`budget.*`) | Default |
|----------------------|--------:|
| `ingestShareOfRemainingTickPercent` | 10 |
| `fixedMs` (used when the tick is late) | 4 |
| `absoluteMaxMs` | 16 (hard ceiling) |

Ingestion runs on the server thread. Background threads exist only for file I/O and work on detached bytes. Each tick WDC also re-checks a bounded number of chunks, so `LIVE` never exceeds what Minecraft actually holds, and it revives `STALE` chunks that Minecraft holds again.

| Limit (`capacity.*`) | Default | Ceiling |
|----------------------|--------:|--------:|
| `liveMax` (LIVE chunks) | 4096 | 16384 |
| `staleMax` (STALE chunks per dimension) | 1024 | 16384 |

Memory is kept low by storing a section that is a single block or air as one token, and by bit-packing every other section at the narrowest width that fits. Each chunk also keeps its highest block per column, so height reads never scan. Chunks that leave memory are written to `config/worlddata_core/<world>/<dimension>/` in region files, and disk work never runs on the server thread.

## Hot sections and pins

A section that is read often and rarely changes can be kept as a plain array of tokens (about 16 KB) so a read skips the palette. WDC counts reads per section. After `hot.promoteAfterTicks` of steady reads with few edits a section becomes hot, and it cools down after `hot.demoteAfterTicks` of quiet or heavy editing. Operators can also pin a section so that it stays hot until it is unpinned.

The memory budget is per world session. Pins and automatic sections share one limit (`hot.maxSections`, default 192, about 3 MB). When the budget is full, a new pin is refused with a message. Automatic sections are dropped to make room for a pin. Pins are never evicted and are cleared when the world session ends.

The section overlay is drawn by the client for the player who turned it on: yellow boxes are normal sections, green boxes are hot sections (solid green is pinned, faded green is automatic). It works in singleplayer and on a LAN host through `/worlddata overlay`, and anywhere else through the key bound to "Show or hide hot section overlay" (F9 by default).

## Hot-read redirect (experimental)

Off by default. `/worlddata redirect on` lasts until the game or server restarts. To make it the default, set `hot.redirectReads` to `true` in the configuration.

### What it does today

When it is on, vanilla `Level.getBlockState` and `Level.getFluidState` are answered from WDC when it is safe to do so. WDC answers only when all of these hold:

- the chunk is `LIVE`,
- the caller is on the thread that owns the level,
- no block change is waiting in the queue, and
- the position is inside the height range WDC stored for the chunk.

Air, cave air and void air are stored as separate states and are served like any other block, so open sky, caves and structures answer the same as in vanilla. A position above or below the stored range goes to vanilla, which answers void air there. In every other case vanilla answers. A waterlogged block answers as the block for block reads and as water for fluid reads. Writes, world generation and the light engine are never affected, and WDC's own reads of those two methods always go to vanilla.

### Redirect commands

All of them need operator level 2 by default.

| Command | What it does |
|---------|--------------|
| `/worlddata redirect` | Shows ON or OFF, the configured default, and how many reads were served from WDC and how many fell back to vanilla, split into `getBlockState` and `getFluidState`. |
| `/worlddata redirect on` | Turns the redirect on for this run. The counters start again and the totals of the last run are printed. |
| `/worlddata redirect off` | Turns it off right away. Vanilla answers every read again. |
| `/worlddata redirect reset` | Clears the counters without changing ON or OFF. |

The F3 screen shows `WDC Redirect: ON served=... fallback=...`. The counters are read counts, not time saved. To judge the redirect, run `/worlddata session start` with it off and again with it on, in the same place and doing the same thing, and use Spark for a per-mod breakdown.

### Performance expectations

WorldData Core does not automatically speed up your whole pack. Most gains appear when other mods, or script mods that can call Java APIs, use its API instead of scanning the world the slow way. Installing it on its own is not free TPS.

For a quick test of the optional vanilla redirect:

1. Install WorldData Core.
2. In game, run `/worlddata redirect on`. It needs operator permission, so in singleplayer cheats must be on.
3. This sends two vanilla reads, `getBlockState` and `getFluidState`, through Core when its data is available.
4. Compare how the game feels, MSPT and Spark with the redirect off and on. `/worlddata session start` writes a report you can compare.

The redirect is off by default. It can improve some block and fluid reads, and the gain is small. It will not fix lag from pipes, machines, entities or other heavy mods. Use `/worlddata redirect off` to turn it off again.

For API usage details, see [Diagnostics](#diagnostics).

### What players should not expect

- A higher TPS or FPS from installing WorldData Core by itself. Nothing gets faster until a mod uses its API or you turn the redirect on.
- A cure for lag from pipes, machines, entities, redstone, chunk generation or other heavy mods. WDC does not touch them.
- A big change from the redirect. It can improve some block and fluid reads, and many mods read blocks in ways it does not see.
- Anything visible. It adds no blocks, items, mobs, world generation, weather or shaders. The only visible parts are F3 lines, commands and a debug overlay.
- Memory to be free. WDC keeps its own copy of loaded chunk data, so it uses some RAM on top of Minecraft's.
- A repair tool. It does not load, generate or fix chunks, and it never changes your world.

### What mod authors should not expect

- **Exact-instant truth.** Pull reads include changes Minecraft already reported this tick, but a mid-tick pull is best effort. Logic that must be exact at the same instant should read Minecraft directly.
- **Notice of every edit.** Edits that bypass normal block updates, such as bulk editing tools, are not seen until `markChunkDirty` or `markBlockDirty` is called. With the redirect on, other mods can read the old block until then.
- **Data for every chunk.** WDC never loads chunks. An unloaded, pending or not yet read chunk answers with a reason instead of data. A position above or below the stored height has no state (`NULL_ID`) and does not answer void air.
- **Air in scans.** Point reads return the state at the position, including `minecraft:air`, `minecraft:cave_air`, `minecraft:void_air` and any other state Minecraft calls air. `isPresent`, block searches, counts, height maps, columns, the block index and occupancy count real blocks only.
- **Stable ids.** State ids are valid for one session. Persist block ids and properties.
- **A write API.** There is none. WDC cannot place, remove or change blocks.
- **The redirect to be a contract.** It is an internal, experimental optimization and it is off by default. Do not depend on it, and do not assume `Level.getBlockState` became cheap.
- **Every thread to be served.** Reads from a thread that does not own the level always go to vanilla.

### Risks, delays and duplication

WDC is built to be safe, but a cache sits between a mod and the game, so these are worth knowing. Some of them are only possible in theory. They are listed so that nothing is a surprise.

- **Stale answers.** Answers come from a cache. A change that WDC was not told about can be read as the old block until it is noticed. WDC re-reads chunks around players over time, and the redirect does not answer while a known change is still queued, but it cannot see an edit that bypasses normal block updates.
- **Delays.** New chunks are read inside the tick budget, so a chunk can be `PENDING` for a few ticks after it loads. Changes made off the owning thread wait in a queue until the next drain. The push feed delivers once per tick. In those windows vanilla answers, or the API says why it has no data.
- **Duplicated data.** WDC keeps its own compact copy of loaded chunk data next to Minecraft's. That costs RAM, bounded by the `capacity.*` limits and shown on the F3 screen. Two copies of the same data can also disagree for a short time, as described under stale answers.
- **Duplicated work.** If a mod keeps scanning chunks itself and also calls WDC, the work is done twice. WDC cannot stop a mod from doing that.
- **Duplicating items or blocks.** Not possible through WDC. It is read-only and never touches inventories, entities, block entities or world saves. The only way an old block could be used is a mod that acts on a stale answer, which is one reason the redirect is off by default.
- **Double counting.** Redirect counters count reads, not unique blocks. The same position read a thousand times counts a thousand times.
- **Redirect overhead.** A fallback costs a little more than vanilla alone, and a served read is not always faster. If your pack reads blocks mostly through other paths you may see no change, or a very small slowdown.
- **Mod conflicts.** The redirect and the block change hook are mixins. Another mod that rewrites the same methods can fail at launch or give inconsistent results. Remove one of the two mods.
- **Wrong side or wrong thread.** On a client connected to a remote server, WDC knows only the chunks the server has sent. Reads from threads that do not own the level are never redirected.
- **Disk.** Chunk data that leaves memory is written under `config/worlddata_core/`. It is a cache that WDC can rebuild from the loaded world.
- **Crashes and restarts.** The cache lives in memory. After a crash or restart it is rebuilt from the loaded world, and anything not yet written is lost without affecting the world.
- **Exact decisions.** Do not use cached data to decide permissions, prices or anything else that must be exact.

## Commands

All commands start with `/worlddata`. Permission levels are configurable under `commands.*`. By default info commands are level 0 and changing commands are level 2.

| Command | Level | Description |
|---------|:-----:|-------------|
| `stats`, `help`, `performance`, `capacity show`, `budget`, `pins` | 0 | Status, cost, limits and hot memory use. |
| `monitor report [top]` | 0 | Print the API usage report. |
| `budget share\|fixed\|max\|reset` | 2 | Change the ingestion tick budget at runtime, or return it to its defaults. |
| `capacity reset`, `performance reset` | 2 | Return runtime changes to the values in the configuration file. |
| `pin <x> <y> <z>`, `pin here` | 2 | Pin the section that contains the block. |
| `pin area <x1> <y1> <z1> <x2> <y2> <z2>` | 2 | Pin every section the box touches, as far as the budget allows (at most 4096 sections per command). |
| `unpin <x> <y> <z>`, `unpin here` | 2 | Unpin a section. It then follows the automatic rules. |
| `overlay [on\|off\|radius <chunks>]` | 2 | Control the hot section overlay. |
| `redirect [on\|off\|reset]` | 2 | Status, enable, disable or clear the redirect counters. |
| `monitor on\|off\|reset\|save\|sample <n>` | 2 | Control the API usage monitor. |
| `session start [seconds]`, `session stop`, `session status` | 2 | Run a timed test session. |
| `dump here\|chunk <chunkX> <chunkZ>\|section <chunkX> <sectionY> <chunkZ>` | 2 | Print what the cache holds for a chunk or section. |
| `gc` | 2 | Request a JVM garbage collection, to check memory use on the F3 screen. |

## Configuration

The file `config/worlddata_core/worlddata_core.json` is created on first start. Out-of-range values are clamped when the file is loaded.

| Section | Keys |
|---------|------|
| `budget` | `ingestShareOfRemainingTickPercent`, `fixedMs`, `absoluteMaxMs` |
| `capacity` | `liveMax`, `staleMax`, `liveMaxCeiling`, `staleMaxCeiling`, `evictionBatch`, `ingestQueueLengthMax`, `spillQueueLength` |
| `hot` | `autoPromote`, `maxSections`, `promoteAfterTicks`, `demoteAfterTicks`, `promoteReadsPerTick`, `maxMutationsPerWindow`, `overlayRadiusChunks`, `redirectReads` |
| `commands` | `permissionLevel`, `infoPermissionLevel` |
| `monitor` | `allowed`, `sampleEvery`, `maxCallers` |
| `session` | `allowed` |
| `debug` | `showF3Overlay`, `showOpsExternal`, `showOpsInternal`, `ingestionEnabled`, `overlayEnabled`, `logBiomeChanges`, `logModifierValues`, `configAutoReload` |

## Diagnostics

**F3 screen.** WDC adds `WDC Mem`, `WDC CPU`, `WDC Budget` and `WDC Redirect: ON|OFF served=... fallback=...`. F7 and F8 toggle the operator panels and F9 toggles the hot section overlay.

**API usage monitor.** Shows which mods call the WDC API, which calls they make and how often. It is off until an operator runs `/worlddata monitor on`. Totals per call are exact. Callers are identified on one in `sampleEvery` calls (default 8) and scaled to the exact totals, so caller figures are estimates. The report lists the busiest callers (mod id where the loader can resolve it, otherwise the calling class), the busiest calls with how often the chunk was live, stale, unloaded or pending, and a repeat share that shows callers who could cache their results. `/worlddata monitor save` writes the report to `config/worlddata_core/monitor/`. While off, the monitor costs one flag check per call. Memory use is fixed by `monitor.maxCallers` (default 256).

### Test sessions

`/worlddata session start [seconds]` records a timed window (default 300 seconds, range 60 to 600) and writes a plain text report when it ends. Only one session runs at a time. While it runs, WDC records:

- Server tick time from the start of the tick to its end: samples, minimum, average, maximum and ticks over 50 ms.
- How many redirect reads were served and how many fell back during the window.
- Which mods called the WDC API, using the API usage monitor for the length of the session.

Reports are written to `config/worlddata_core/sessions/wdc-session-<date>_<time>-redirect-<on|off|mixed>.txt`. To judge the redirect, run one session with it off and one with it on at the same place doing the same thing, and do not switch it while a session runs. The report covers server tick time and WDC calls only. Use Spark for a per-mod CPU breakdown. Set `session.allowed` to `false` to disable test sessions on a production server.

## Compatibility and limits

- WDC supports modded blocks, fluids and biomes through registry ids and tags.
- WDC is written to cope with mods that replace Minecraft's chunk palette storage, for example Lithium.
- WDC does not load or generate chunks, change the world, render anything beyond its F3 lines and debug overlay, simulate weather, temperature or wind, or track entities and block entities.
- Edits that bypass normal block updates are not seen until `markChunkDirty` or `markBlockDirty` is called.
- Air, cave air, void air and any other state Minecraft calls air are stored as separate states. Point reads return the exact state, while `isPresent`, block searches, counts, height maps, columns, the block index and occupancy count real blocks only. A position outside the stored height range answers `NULL_ID`.
- State ids are not stable between sessions.

## License

The public API (`net.wdc.core.api.*`) is licensed under the MIT license, see `LICENSE-API`. Everything else is licensed under LGPL-3.0-or-later, see `LICENSE` and `COPYING`.
