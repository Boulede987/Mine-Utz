# Integrating the per-world tile biome system into Minecraft (design, 2026-09-20)

Status: decisions answered (section 7); stage 1 built (section 8). The Minecraft-side stages are
next. Where section 4-6 text conflicts with section 7, section 7 wins.

## 1. What is being replaced, and by what

Today the mod generates biomes lazily and forever: `ModOverworldBiomeSource.getNoiseBiome` asks
`RegionCacheHolder.get().biomeAt(cell)`, a `SeedMeshCache` that solves a constraint problem over a
hex mesh one macro-cell at a time as chunks are generated, persisting decided faces and vertices
in per-shard `SavedData`. That system can hit a dead end at runtime for some seeds (the reason for
this work), and it needed a spawn-area pre-generation sweep and a 300 s startup latch to survive
real crashes (DECISIONS.md, 2026-09-05 and 2026-09-06).

The replacement solves the WHOLE world once, at world creation, as a finite periodic tile:

```
world seed
  -> pattern        ToroidalSizedPatternGenerator   (40x40 cells, all 66 biomes, sizes and shapes legal)
  -> warp strength  RealizationSelector             (strongest strength whose rendered map verifies)
  -> biome raster   BiomeMapRasterizer              (2598 x 2598 samples, 4 blocks apart, one tile)
  -> lookup         biomeAt(blockX, blockZ)         (O(1) array read, tiles the plane without seams)
```

The world repeats every tile (10,392 blocks along each of two lattice vectors 60 degrees apart).
Nothing is solved lazily and nothing can dead-end while the game runs. Evidence so far: 104 of 104
world seeds produced a verified pattern and a verified map (worst area drift 0.3%, size tolerance
10% of the minimum); see `project_toroidal_identity_localsearch` memory and
`worldgen-core/build/reliability/`.

## 2. What exists today and what happens to each piece

| Today (file) | What it does | Fate |
|---|---|---|
| `data/minecraft/dimension/overworld.json` | Selects `mineutze_worldgen:custom_overworld` biome source | Keep unchanged |
| `data/minecraft/worldgen/noise_settings/overworld.json` | Terrain density reads the biome (`minecraft:biome` conditions) | Keep unchanged - it only needs a biome source |
| `ModBiomeSources`, `ModOverworldBiomeSource.CODEC`, `collectPossibleBiomes` | Registers the source, resolves the catalog's biomes to registry holders, fails loud if one is unregistered | Keep |
| `ModOverworldBiomeSource.getNoiseBiome` | Cell lookup through `RegionCacheHolder` | Rewrite: quart to block center, plan lookup |
| `RegionCacheHolder` (Load hook, latch, 25 km pre-generation, 2M-cell cache) | Lazy per-cell solving | Replace by `WorldBiomePlanHolder`; pre-generation sweep and cell cache disappear |
| `SavedDataFaceStore`, `SavedDataVertexStore`, `SavedDataRegionPersistenceStore` | Persist decided faces/vertices | Replace by one `WorldBiomePlanSavedData` |
| `LocateBiomeOverride` (50,000-block spiral, 32-block step) | Bounded search because biomes may be far | Rewrite as an exact scan of the plan (section 4.5) |
| `RegionDebugCommand`, `WorldBiomeExporter` | Debug/export against the old cache | Adapt to the plan |
| `regionsolve/*`, `reachability/*`, hex-mesh CSP, most of `export/*`, most of `coherence/*` | The failed system and its experiments | Delete in a later, separate step (section 6, stage 6) |
| `ModBiomeGraph.OVERWORLD` from `biome-graph.json` | The catalog | Keep - the same JSON feeds both `BiomeGraphJson` and `RawRuleTable.from(BiomeGraphData)` |

## 3. Facts about Minecraft that the design relies on

Verified in this repo's code:
- Biome placement is 2D: the current source ignores `quartY` and `LocateBiomeOverride` documents it.
- The biome source is created from a fieldless codec that only needs the biome registry.
- `getNoiseBiome` is called from many chunk-generation worker threads at once.
- The old holder had to make workers WAIT for the Overworld to load (latch) after a real
  startup race; the seed is only available from `ServerLevel.getSeed()` on `LevelEvent.Load`.

Not yet verified - to be checked in stage 4 before relying on them:
- Whether the dedicated-server watchdog is running during `LevelEvent.Load`. The old
  pre-generation ran there for seconds; the new work is far longer.
- How the integrated-server loading screen behaves during a multi-minute `Load` handler.

## 4. Design

### 4.1 Public facade in worldgen-core (Minecraft-free)

All production classes today are package-private in `coherence`, which also holds many obsolete
experiments. Add one public class, in a new package (working name `com.mineutze.worldgen.plan`),
that the mod calls and that is unit-testable without Minecraft:

```java
WorldBiomePlan WorldBiomePlan.create(BiomeGraphData catalog, long worldSeed, int threads, ProgressListener progress)
BiomeId        plan.biomeAt(int blockX, int blockZ)            // O(1), thread-safe, read-only
PlanSnapshot   plan.toSnapshot()
WorldBiomePlan WorldBiomePlan.fromSnapshot(PlanSnapshot snapshot)
```

`ProgressListener` is a callback because worldgen-core deliberately has no logging dependency
(DECISIONS.md 2026-09-04); the mod supplies a logger-backed one. Production classes move into the
new package; nothing else in `coherence` is referenced by the mod.

### 4.2 The lookup: the raster is the source of truth

After creation, `biomeAt` reads a `byte[2598*2598]` raster (66 biomes fit in a byte; 6.75 MB
before the save file's gzip). It does NOT re-evaluate the warp per query: one evaluation is
about 1,300 sine calls, and vanilla asks for every quart of every 3D column, so a direct
evaluation would cost tens of milliseconds per chunk. The raster is also exactly what
`BiomeMapVerifier` checked, which is a stronger statement than "the function it was sampled from
was checked".

A world position (x, z) maps to tile coordinates `(u, v) = M^-1 (x, z)`, wrapped, then to the
nearest raster sample. Quart to block: use the CENTER of the quart, `QuartPos.toBlock(q) + 2`.

### 4.3 Reproducibility (a hard requirement, found while reading the math)

`Math.sin/cos/pow/exp/log` are allowed to differ by one unit in the last place between JVMs and
CPUs. If a world's server moved to another machine and the warp were re-evaluated, a border
sample could flip and newly generated chunks would seam against old ones. Java 17+ makes `+ - * /`
and `sqrt` strictly reproducible, so only the transcendental calls matter. Requirements:
- The saved raster is authoritative: an existing world never recomputes it.
- Everything used at creation is still reproducible, so a re-creation from the seed gives the
  same map: use `StrictMath` for setup-time `pow/exp/log`, and a table-driven sine (table built
  once with `StrictMath.sin`, interpolated with strict arithmetic) for per-sample evaluation.
  This is also FASTER than `StrictMath.sin` per call, which matters for section 5.
- The pattern search's acceptance table uses `Math.exp`: switch to `StrictMath.exp` (one-time).
- A test must fail if production code under the new package calls `Math.sin/cos/pow/exp/log`.

### 4.4 Persistence and start-up

`WorldBiomePlanSavedData` (one object, world data folder, gzip'd by Minecraft) stores:
schema version; algorithm version; world seed; catalog fingerprint (hash of the catalog JSON);
grid size, atom width, shape rule, warp settings, chosen strength and warp seed; a palette of
biome resource locations; the raster; the pattern (small, for debugging and re-verification).

On `LevelEvent.Load` for the Overworld:
1. Snapshot present: rebuild the plan from it and use it verbatim. If the catalog fingerprint
   differs from the running catalog, log a loud warning and STILL use the snapshot (the world's
   existing terrain must not change) - never regenerate for an existing world.
2. (Dropped - see section 7, decision 4: no backward compatibility with the old system.)
3. No snapshot: create the plan (blocking, section 4.6), save it, open the latch.

Failure is loud: if creation throws, world load fails with the generator's message, which
already contains the world seed, budget and the biome pairs the closest attempt was stuck on.

### 4.5 Consumers

- `ModOverworldBiomeSource.getNoiseBiome`: plan lookup, resolve `BiomeId` to the `Holder<Biome>`
  map it already has; unknown id is an `IllegalStateException` as today.
- `/locate biome`: every biome occurs in every tile, so the answer is always within about one
  tile diagonal (~15.6 km) and the search is exact. Scan the raster for the nearest sample whose
  biome matches the argument (including biome tags), considering the lattice translates for
  wrap-around; no spiral, no 50 km guess, no "not found" beyond a biome that is not in the
  catalog.
- Debug command: report the biome at the player, tile coordinates, and plan metadata (strength,
  attempts, timings, fingerprint).
- Exporter: keep its purpose (dump what the game REALLY generated) but drop the macro-cell
  assumptions.

### 4.6 Where the work runs

Blocking, synchronously inside the `Load` handler, exactly where the old pre-generation ran,
with all cores but one and a progress line at least every 10 seconds. Reasons: nothing may query
biomes before the plan exists; a background task would need every worker to wait on the latch
anyway; and a blocked main thread during load is the state the game is already in. The latch and
its timeout stay, sized for the slowest expected machine, not the 300 s of today.

### 4.7 Two verification gaps to close

- The verifier samples the TILE's skewed lattice; the game samples an axis-aligned quart grid.
  A filament one sample thick could be connected in one and broken in the other. Add a final
  check on the game's own grid: render a multi-tile axis-aligned window through the raster and
  reject the plan if any component fully inside the window is smaller than its biome's
  allowed minimum (windows clipped at the edge are ignored, so no wrap logic is needed).
- Thin tendrils: the strongest warps stretch shapes up to several times. Measure the minimum
  width of each region on the final map, so "no thin snakes" is checked, not assumed.

## 5. Costs (measured or derived; the derived ones are marked)

| Step | 12 threads (measured, 104 worlds) | 1 thread (DERIVED, estimate) |
|---|---|---|
| Pattern | median 21.6 s, p90 43.6 s, max 68.4 s | attempts x ~20 s: mean ~2.5 min, p90 ~6 min, max ~10 min |
| Realization (per candidate strength, 2598^2 samples, ~1,300 sine calls each) | median total 18.5 s, max 38.1 s | ~120 core-seconds per candidate, median 2 candidates: roughly 4 minutes |

The realization cost is the surprise. It is dominated by re-evaluating the flow warp for 6.75M
samples. Options, cheapest first:
1. Table-driven sine (section 4.3): required anyway, several times faster than `StrictMath`.
2. Evaluate the displacement on a coarse grid (about 8 blocks) and interpolate. The shortest
   wavelength is 40 blocks, so 5 grid points per wave; roughly 16 times fewer evaluations. The
   result is still verified as rendered, so an approximation error cannot slip through.
3. Order the candidate strengths by what actually verified (18-30 all occurred; start near 26).
4. Verify at the final resolution only for the candidate that passed a cheap 2x-coarser screen.

Target: realization under 30 core-seconds. To be measured, not assumed.

## 6. Implementation stages

Each stage ends with tests green, a commit, and the acceptance check named.

1. **Core facade** (worldgen-core). New package, `WorldBiomePlan`, `PlanSnapshot`, progress
   callback, `StrictMath` and table-sine audit with the no-`Math` test. Acceptance: plan for
   seed 1094 equals the saved study pattern; `biomeAt` agrees with `RealizedBiomeField` within
   the sample spacing; snapshot round-trips byte for byte.
2. **Speed** (worldgen-core). Coarse displacement grid, candidate ordering. Acceptance:
   realization core-seconds and single-thread wall time measured on this machine with
   `taskset`-style limits; verification results unchanged on the 104 saved patterns.
3. **Mod wiring** (mod module). `WorldBiomePlanHolder`, `WorldBiomePlanSavedData`,
   `getNoiseBiome`, legacy-shard refusal, catalog-mismatch warning. Acceptance: dev server starts,
   creates a world for seed 1094, restarts and reuses the snapshot with no solving.
4. **Consumers**: `/locate biome`, debug command, exporter. Acceptance: `/locate` finds every
   catalog biome from three different start points, instantly.
5. **In-game verification**: dev-server run over a sizeable explored area; export what the game
   generated and compare with the plan raster; measure chunk-generation throughput against the
   old system; check the two open Minecraft facts from section 3; game-grid speck check and
   thin-width check from section 4.7 run against real world seeds.
6. **Remove the old system**: `regionsolve`, `reachability`, obsolete `export` and `coherence`
   classes, legacy stores, stale logs. Own branch, own commits; last, and only after 5 passes.
7. **Re-run the 104-seed study through the facade**, and record the decisions of section 7 in
   `DECISIONS.md` in this repo's existing format.

## 7. Decisions (answered by the author, 2026-09-20)

1. Two worlds generated from the same seed must be the same world. The deterministic attempt
   sequence (lowest solving attempt wins) satisfies that; it is not "reseeding" in the sense
   of DESIGN.md. Verified: the same seed gives a byte-identical map across separate processes.
2. Solving time matters while the player is exploring (nobody waits in place for the next
   chunk), not at world creation. A one-time world creation of about 5 minutes is acceptable.
   Nothing may be solved lazily during play - which the tile design guarantees.
3. The world repeating every ~10 km (10,000 blocks) is accepted.
4. No backward compatibility: worlds made by the old system are not supported. The legacy-shard
   refusal proposed in section 4.4 (step 2) is therefore DROPPED. An existing world made by the
   NEW system keeps its saved plan verbatim, with a loud warning if the catalog has changed.
5. No dead code is kept. The old system is deleted (it lives on in git history), as soon as the
   new one is verified in-game rather than left behind a switch.

## 8. Progress

- Stage 1 (core facade) done: `WorldBiomePlan`, `PlanSnapshot`, `BlockPosition`,
  `DeterministicTrig` (machine-independent sine and cosine), `ReproducibilityRulesTest`. Real-scale
  check (`./gradlew :worldgen-core:worldBiomePlanCheck`, seed 1094, 12 threads): plan created in
  22 s, 26.5 million lookups per second, `nearest()` 5 ms per biome, snapshot round trip exact,
  tile repeat exact over 2 million random points up to +-1,000,000 blocks, raster SHA-256 identical
  across two processes, solver pattern identical to the one a study saved days earlier.
- The facade lives in `com.mineutze.worldgen.plan` (renamed from `coherence` once the dead code around it was deleted).

- Stage 3 and 5 (mod wiring, in-game verification) done on a real dev server (seed 42, the modpack's
  Biomes O' Plenty / Biomes We've Gone jars added to `runs/server/mods`, driven by console commands):
  - New world: plan created in 32 s on 11 threads (pattern attempt 1, warp strength 26), saved with the
    world (`data/mineutze_worldgen_plan.dat`), server "Done" in 33 s. Restart: plan restored, "Done" in
    0.4 s, no solving. Spawn-area preparation after the plan took about 2 s.
  - `/locate biome` is exact and instant: desert 4,071 blocks away, mushroom fields 22, a Biomes We've
    Gone biome 4,618.
  - Acceptance check (`/mineutzeworldgen exportbiomes <radius> <step>`): the biomes stored in generated
    chunk data equal the plan at every quart (0 mismatches in 251,001 exhaustive checks); the blended
    `level.getBiome` differs from the plan at about 0.8-0.95% of samples, ALL explained by vanilla's own
    jitter between neighboring quarts (0 unexplained in 251,001 + 564,001 + 564,001 samples).
  - Facts settled: the server watchdog is NOT running during `LevelEvent.Load` (a 32 s creation there is
    safe) but IS running during commands (an export over 60 s on the server thread killed the server).
  - Found only by running the game: (1) the saved data was never written because it was not marked dirty;
    (2) the dev-run needed one asm version, both source sets declared as one mod group, and stdin
    forwarded. The old integration had not compiled since an earlier package reorganisation.
- Cleanup done (2026-09-20): the old system is deleted (regionsolve, reachability, export, 21 superseded
  proof/prototype classes and their tests, their gradle tasks, export scripts, generated reports); the
  package is renamed to `plan`; the graph editor's Deep check and Coherence proof are replaced by one
  Solve check (`graphEditorSolveCheck`: real plans from the saved graph for 4 world seeds; 4 of 4 solved,
  16-64 s each). 242 worldgen-core tests pass. DECISIONS.md has the entry.
- Still open: unit tests for the mod-side save format (NeoGradle's JUnit hook did not start; the in-game
  save/restore is the check for now); the game-grid speck check and the minimum-width check of section
  4.7; re-running the 104-seed study through `WorldBiomePlan`.
- Required borders (2026-09-20): `requiredNeighbors` is now enforced (see DECISIONS.md). Open: "any of these"
  groups (a beach touches `ocean` or `lukewarm_ocean`), and a rendered border fringe for beaches narrower than a cell.
- Also done (2026-09-20): editor "Remove focused biome" button; `BiomeGraph`'s old-mesh completeness checks
  and the catalog node-count test deleted (241 tests).
