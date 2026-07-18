# Custom Overworld Biome Placement — Design Spec

Status: **implemented** (all 6 rollout milestones complete — patch size, terrain-class
gating, soft adjacency, underground linkage). Sections below are marked with what
changed during implementation vs. this original design; unmarked sections are still
accurate as background/rationale. See "As-built deltas" (§13) for the full list plus
known follow-up items deferred, not forgotten.

Supersedes the TerraBlender-based `FilterRegion` approach that used to live in this mod
(`companion-mods/terrablender-custom-region`, now renamed
`companion-mods/mineutze-worldgen`) for the **Overworld only** -- Nether and End were
never migrated off TerraBlender, see §9's delta.

## 1. Goal

Replace vanilla/TerraBlender's nearest-point climate biome selection with an
author-controlled biome placement engine that guarantees:

1. Biomes match real terrain (no ocean biomes on dry land, no land biomes in open water).
2. Explicit adjacency control: "biome X may only border {A, B, C}" / "biome X must never
   border Y".
3. Explicit patch size control: "biome X is always at least N, at most M blocks across".
4. Terrain-class-correct placement: a biome tagged as mountainous only generates where the
   terrain is actually mountainous — never assigned to flat ground just because it fits a
   size/adjacency rule.

## 2. Why vanilla's system can't do this (background)

Overworld terrain shape comes from a `NoiseRouter` — density functions for continentalness,
erosion, weirdness (ridges/PV), depth. These produce actual block height (mountains, plains,
caves) independently of biome. Separately, `BiomeSource.getNoiseBiome(x, y, z, sampler)`
reads a `Climate.Sampler` — the same continentalness/erosion/weirdness/depth values, plus
independent temperature/humidity noise — to nearest-point-match a biome's registered
`Climate.ParameterPoint`s.

Vanilla's "frozen peaks only appear on jagged terrain" is not causal, it's curated
coincidence: Mojang hand-picked `frozen_peaks`'s climate point to sit in the same
erosion/weirdness zone their height spline maps to tall jagged terrain. Two independent
consumers of the same signal, correlated by hand-tuning, not enforced by code. Our old
`FilterRegion` anchor system inherited this same weakness, worsened by random per-anchor
erosion/weirdness sampling and a zero-width `depth` point — see git history on
`biome_grid.py` for the specific failure mode.

The fix: since `BiomeSource.getNoiseBiome` already receives the real
continentalness/erosion/weirdness/depth signal (the same one driving height), a custom
`BiomeSource` can gate biome eligibility on that signal directly instead of guessing via
anchor points.

## 3. Architecture overview

Two-tier patch system inside a custom `BiomeSource`:

- **Surface patch layer** (2D, x/z) — land and ocean biomes.
- **Underground patch layer** (3D, x/y/z) — cave/underground biomes (UndergroundWorlds).
- **Dispatch logic** — routes a given `(x, y, z)` query to the matching tier based on a
  real height estimate for that column (bounded, cached per column).

This replaces per-column independent nearest-neighbor lookup with explicit, cached region
patches — required for size and adjacency control, which are meaningless for a system where
patch shape is an accident of Voronoi cell density.

## 4. Terrain-class gating

Bucket the climate signal (continentalness, erosion, weirdness, depth) into terrain classes
per biome-cell:

- Surface: `ocean`, `coast`, `flat_land`, `hilly`, `mountainous` (exact cutoffs TBD, see §11).
- Underground: classes mirroring vanilla's own cave-biome bands (deep dark, lush caves,
  dripstone caves use specific depth/weirdness/PV ranges today — reuse the same signal).

Each biome declares its eligible terrain class(es) in config. Patch seeds only "germinate"
in cells matching one of the biome's classes — this is what prevents a mountain biome from
landing on flat ground. Class derivation reads directly off `Climate.Sampler` output; no
separate height sampling needed for classification (a coarser, cheaper proxy than actually
walking the density function stack). Thresholds need empirical calibration against this
modpack's actual noise router (see §11 — vanilla's own breakpoints are a starting guess only,
BOP/other mods may alter the router).

## 5. Patch generation algorithm

- **Seed placement**: deterministic Poisson-disc-style hashing per coarse region grid cell —
  `seed = hash(worldSeed, regionX, regionZ[, regionY for underground])`. Reproducible without
  precomputing the whole world.
- **Growth**: best-first flood-fill outward from each seed, subject to:
  - terrain-class eligibility (cell must match one of the biome's declared classes)
  - target size sampled per-patch from the biome's configured `[min, max]` range; growth
    stops at target or when blocked by ineligible terrain / a competing patch
  - soft adjacency scoring: growth priority favors cells bordering already-resolved patches
    on the biome's preferred-neighbor list; penalizes (does not hard-block) cells bordering
    the forbidden-neighbor list. Implemented as a weighted priority queue, not plain BFS.
- **Conflict resolution**: when two growing patches contest a cell, resolve by patch
  weight (reused from the old weight-slider concept) with a deterministic tie-break.
- **Caching**: resolved patch-id-per-cell memoized in a thread-safe map keyed by coarse
  region coordinate, computed lazily on first lookup. Must be safe under NeoForge's
  parallel chunk generation.

Adjacency is intentionally **soft**, not a hard constraint solver: no backtracking, no
dead-end handling required, deterministic and cheap. Accepted tradeoff — occasional
violated adjacency won't be visually obvious; escalate to a real constraint-propagation
approach only if it proves to be a real problem in practice.

## 6. Determinism requirements

- Growth must resolve using only bounded-radius neighbor state (cap lookahead to N region
  cells) — never require resolving arbitrarily distant, unrelated chunks first.
- Same world seed + same coordinates → same result regardless of exploration order or
  direction. Validate with an automated test: resolve the same region via two different
  simulated "discovery orders" and diff the output.

## 7. Underground (3D) tier

**AS-BUILT DELTA**: implemented much simpler than this section originally called for, and
the "exact 1:1 linkage" requirement (added after this doc was drafted) changes the
eligibility model entirely — see below.

Originally specified: full 3D version of the same patch/adjacency/size growth engine, per
decision to cover UndergroundWorlds biomes at full fidelity rather than a simplified
depth-band fallback, with terrain classes derived from vanilla's own cave-biome
depth/weirdness/PV bands.

What actually shipped (`UndergroundPatchGrid`): no growth engine at all. Each 32-block
coarse `(x, z)` cell (Y is not a growth axis here) independently does a weighted pick
among whichever cave biomes have that column's *specific* resolved surface biome
(`SurfacePatchGrid.resolve`, memoized) in their `undergroundLinkedSurfaceBiomes` list —
this is what "exact 1:1 linkage" (a requirement clarified after this doc was written)
actually needs: not a terrain-class band match, a specific-surface-biome match. No patch
size or adjacency underground in this milestone — that was never asked for, only
linkage was. Dispatch (surface vs. underground) uses the `depth` climate axis directly
(already what `FilterRegion.java`'s old `depth [0.2, 0.9]` underground entries exploited)
rather than a real height estimate — simpler, and depth already encodes "how far below
the estimated surface," so no separate height sampling was needed after all.

Unlinked underground columns (no cave biome links to that surface biome) fall back to
**the same biome as the surface directly above**, not vanilla's own dripstone/lush/
deep_dark placement — see §9's delta for why (same root cause: avoiding a second
uncertain vanilla-registry codec lookup for a payoff the actual requirements didn't ask
for). Known, disclosed limitation, not an oversight.

## 8. Config / data model (replaces `biome_settings.json` / `paint_*.json`)

Per biome:

- `terrain_classes`: list (replaces old `ZONE_LAND`/`ZONE_OCEAN`, extended with
  hilly/mountainous/underground-band classes)
- `size_min`, `size_max` (unit TBD — see §11)
- `preferred_neighbors`: list of biome ids
- `forbidden_neighbors`: list of biome ids (soft-penalized, not hard-blocked)
- `weight`: patch frequency / growth priority (reused from the old system)

**AS-BUILT DELTA**: the Streamlit app's role never shifted — it was deleted outright
instead. `pythonScripts/worldgen/app.py` and everything only it used (`biome_grid.py`,
`biome_nearest_neighbour.py`, `app_auto_layout.py`, `app_slices.py`, `app_world_paint.py`,
`app_world_map.py`, `app_coverage.py`, `app_data.py`, `biome_constraints.py`,
`entry_pipeline.py`, plus the already-orphaned `app_constraints.py`/`app_tile_editor.py`/
`tile_assignments.py` and the standalone `visualize_biome_layout.py`/
`visualize_world_map.py`) are gone — none of it could be salvaged, since all of it was
built around climate-anchor concepts (weight/zone/temperature/humidity ranges) that have
no counterpart in the patch-growth model at all. The "tune this patch-generator config
and preview the resulting layout" UI this section describes was **never built** — that
was always separate scope from the 6 rollout milestones, deliberately skipped. Right now
there is no UI: tuning `BiomeConfigRegistry.java` means hand-editing that Java file and
rebuilding/redeploying the mod. The Python port of the growth algorithm
(`patch_engine.py`/`patch_seed_hash.py`/`terrain_classifier.py`) was also dropped after
the fact, once it became clear its stated justification ("faster than a Gradle rebuild")
didn't hold up in practice (`compileJava` alone runs in seconds here) and hand-porting
every algorithm fix to two languages was a real, ongoing cost for a preview UI that was
never built. Its determinism check now lives in Java directly
(`PatchGrowthEngineSelfTest`, `./gradlew selfTest` — no deployMod, no game launch).
`calibrate_terrain_classes.py` survives; it's independent log-parsing/bucket-math with no
dependency on the growth algorithm itself. A real authoring pipeline (`patch_config.json`
+ a new Streamlit tab, per §12) is still open work, not done — if it gets built, a Python
port of the algorithm may become worth reintroducing for the preview to drive against.

## 9. TerraBlender removal

**AS-BUILT DELTA**: TerraBlender is NOT fully dropped — only the Overworld half. This
was a deliberate scope decision (confirmed with the user, not a fallback): Nether and
End keep `FilterNetherRegion`/`FilterEndBiomes` exactly as they were, still using
TerraBlender's `Region`/`EndBiomeRegistry` APIs and the raw scanned climate positions.
The mod's `build.gradle` keeps the TerraBlender `compileOnly` dependency and
`neoforge.mods.toml` keeps the `terrablender` dependency block, permanently, not as a
migration-window artifact. Only the Overworld's `FilterRegion.java` was deleted and
replaced.

- **Overworld**: fully custom `BiomeSource` (`CustomOverworldBiomeSource`), registered via
  a dimension JSON override (`data/minecraft/dimension/overworld.json` → `"type":
  "mineutze_filter:custom_overworld"`) and a `DeferredRegister<MapCodec<? extends
  BiomeSource>>` (`ModBiomeSources`) — matches what this section originally called for.
- **Nether/End**: unchanged, still TerraBlender, as described above.

## 10. Mod restructuring

**Done.** `companion-mods/terrablender-custom-region` → `companion-mods/mineutze-worldgen`,
`com.mineutze.filtermod` → `com.mineutze.worldgen`, `FilterMod` → `WorldgenMod`. The
NeoForge modId stayed `mineutze_filter` (not renamed) — only harmless auto-regenerated
cache files referenced it, not worth the churn.

## 11. Effort / risk summary

- **Terrain-class calibration**: tooling shipped (`/dump_height_samples` +
  `pythonScripts/worldgen/calibrate_terrain_classes.py`, smoke-tested against synthetic
  data), but the actual calibration run against this modpack's real noise router has
  **not** been done yet — `TerrainClassifier`'s erosion/weirdness thresholds are still
  the original placeholder guesses. Still an open risk, just no longer a tooling gap.
- **Determinism of patch growth**: validated, now directly in Java —
  `PatchGrowthEngineSelfTest` (`./gradlew selfTest`) resolves the same window twice
  sequentially and eight times concurrently, asserting byte-identical results, which is
  the property that actually matters given `PatchGrowthEngine.resolveWindow` has no
  external "scan order" parameter to vary (its internal iteration order is always
  fixed) — the germination local-maximum rule and seed-derived tie-breaks are what make
  that guarantee hold. No test framework needed, a plain `main()` class was enough. The
  original Python prototype's equivalent (`run_determinism_self_test`, two different
  scan orders) is what caught the patch-identity determinism bug in the first place —
  real value at the time — but the check itself has since been fully superseded by the
  Java version, not just duplicated.
- **3D underground performance**: turned out not to apply — the underground tier shipped
  without a growth engine at all (§7's delta), so this specific risk is moot as
  implemented. A **different**, real performance risk showed up instead on the surface
  tier: see the hang investigation below.
- **Python tooling migration**: did not happen as "migrate incrementally" — the old UI
  was deleted outright once superseded (§8's delta), since none of its code was
  reusable against the new data model.

### Real hang investigated and fixed during implementation (worth keeping as institutional memory)

World creation hung on "Scanning for structures" twice during development, for two
different real reasons, both fixed:

1. An early per-query-window design had **no cap on how far growth could extend** — an
   effectively-unbounded placeholder `size_max` meant a single cache miss grew forever
   outward, sampling `Climate.Sampler` without limit. Fixed by hard-capping growth at a
   resolution window's rectangular boundary regardless of target radius.
2. Window radius derived directly from the largest configured `size_max` with **no
   ceiling** — raising one biome's `size_max` in config alone nearly quadrupled the
   per-cache-miss cost and reproduced the same symptom without an actual infinite loop.
   Fixed with a hard radius ceiling independent of configured values.
3. Separately (not a hang, but confirmed via real profiling data — thread timing logs
   showed lock-wait roughly equal to compute time across ~2600 window resolutions during
   one world creation, several minutes total): the original design used **one global
   lock** (`synchronized` on the whole `SurfacePatchGrid` instance) serializing all of
   NeoForge's parallel chunk-generation worker threads, and **did not cache gap cells**
   (cells no patch's growth reached), causing redundant full recomputation for nearby
   queries. Both fixed by switching from ad-hoc per-query overlapping windows to a
   **fixed-size grid of tiles**, each resolved once via `ConcurrentHashMap.computeIfAbsent`
   (real per-tile concurrency, gaps permanently known once a tile resolves) — see §5's
   caching note, this is much closer to what that section originally specified
   ("memoized... keyed by coarse region coordinate") than the ad-hoc window design that
   preceded it. Accepted tradeoff: patches are now hard-clipped at tile edges (~1300
   blocks apart), a coarser and far less visible version of the very chunk-alignment
   problem this whole rewrite exists to fix (§2) — deliberate, not accidental.

## 12. Open items / next steps

- Run the real terrain-class calibration (tooling exists, hasn't been executed against
  real generated terrain yet).
- Build the real config authoring pipeline: `patch_config.json` + a datapack reload
  listener + a new Streamlit tab. `BiomeConfigRegistry.java` is genuinely temporary
  scaffolding, not just in name — every tuning change today requires a Java edit and a
  full mod rebuild/redeploy.
- ~~Add a Java-side determinism test~~ — done (`PatchGrowthEngineSelfTest`, §11).
- See §13 for concrete, deferred bug follow-ups (rivers, border noise, cave variety).

## 13. As-built deltas not covered above

Requirement 3 ("cave biome X always under overworld biome Y") was clarified as an
**exact 1:1** linkage after this doc's original draft, which had assumed the underground
tier would be independent (§7's delta covers the resulting mechanism change). Requirement
2 (soft, not hard, adjacency) was confirmed as drafted — no change there.

Size unit (left TBD in the original §8/§11): resolved as **radius in blocks**, sampled
per-patch-instance from `[size_min / 2, size_max / 2]`.

A post-processing step not in the original design: `dissolveSmallIslands`
(`PatchGrowthEngine.java`; the now-dropped Python port had an equivalent
`dissolve_small_islands` too, while it existed). Real symptom found during testing —
a germination point deep inside what becomes a much larger neighboring patch's territory
can pass its own local-maximum check and then get boxed in almost immediately, leaving a
small isolated "island" of one biome inside another. Fixed with a post-growth pass:
contiguous same-biome components smaller than ~30% of their biome's own expected minimum
size get reassigned to whichever bordering biome is dominant AND terrain-class-eligible
for every cell involved. Snapshot-then-apply, not mutate-while-deciding, or two adjacent
small islands' outcomes would depend on which got processed first.

Known, deferred (not fixed, not forgotten — tracked as follow-up tasks):

- **Rivers/lakes**: land biomes can still overshoot into inland water. `TerrainClassifier`
  only sees continentalness/erosion/weirdness, and a river/lake cell can carry the same
  macro continentalness as the dry land around it — vanilla itself uses a much narrower
  "weirdness near the valley point" signal specifically for rivers, which the current
  5-class bucketing doesn't capture. Needs a dedicated terrain class (or a height-based
  check) plus a river/lake `BiomeConfig` restricted to it.
- **Border noise between similar-weight biomes sharing a terrain class**: e.g. plains and
  forest (both `FLAT_LAND`-eligible, similar weight) produce a visibly noisy/scattered
  border before settling into solid territory further in. NOT the same failure mode as
  the small-island bug above — border freckles are topologically connected back to their
  main body through a winding path along the frontier, so the dissolve pass's
  connected-component check doesn't catch them (they're not isolated). Real cause:
  `computeCellScore` has no "cohesion" term rewarding a contested cell for having more
  already-resolved same-biome cardinal neighbors, so ties along a contested frontier are
  decided by near-coin-flip tie-break noise instead of favoring a compact, smooth edge.
- **Cave-biome variety for unlinked underground**: see §7's and §9's deltas — unlinked
  underground columns get their surface biome's ID rather than vanilla's own dripstone/
  lush/deep_dark placement. Fixable by wrapping vanilla's default `MultiNoiseBiomeSource`
  as the fallback instead (needs a second registry lookup,
  `Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST`, threaded through the codec) — not
  done because it wasn't something any stated requirement actually asked for, and adds a
  real chunk of uncertain-API risk for that alone.
