# Custom Overworld Biome Placement — Design Spec

Status: design phase, not yet implemented. Supersedes the current TerraBlender-based
`FilterRegion` approach in this mod.

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

Full 3D version of the same patch/adjacency/size engine, per decision to cover
UndergroundWorlds biomes at full fidelity rather than a simplified depth-band fallback.

- Terrain classes derived from the depth/weirdness/PV bands vanilla already uses for its own
  cave biomes — same signal-reuse principle as the surface tier.
- Dispatch: for a given `(x, y, z)`, first determine whether this y sits in the "surface
  band" (near the column's height estimate) or the "underground band" (deep enough / cave
  space), then route to the matching tier's patch lookup.
- 3D patches are more expensive to resolve than 2D — memoize aggressively, and expect to use
  a coarser cell size (e.g. 32-block cubes) than the surface tier, since precision matters
  less underground than at the surface.

## 8. Config / data model (replaces `biome_settings.json` / `paint_*.json`)

Per biome:

- `terrain_classes`: list (replaces old `ZONE_LAND`/`ZONE_OCEAN`, extended with
  hilly/mountainous/underground-band classes)
- `size_min`, `size_max` (unit TBD — see §11)
- `preferred_neighbors`: list of biome ids
- `forbidden_neighbors`: list of biome ids (soft-penalized, not hard-blocked)
- `weight`: patch frequency / growth priority (reused from the old system)

The Streamlit app's role shifts from "tune `Climate.ParameterPoint` anchors" to "tune this
patch-generator config and preview the resulting patch layout":

- World Map / Coverage tabs: concept survives, needs a new renderer driven by the patch
  engine instead of nearest-point climate math.
- Tile Map painter (`paint_seeds`/`paint_assignments`): survives and gets more useful — becomes
  "manually pin a patch seed at this location" instead of a climate-anchor preview.
- Parameter Slices tab: retired. There's no climate-anchor space left to visualize; a
  meaningful replacement would need real sampled noise from the game rather than
  `biome_noise.py`'s synthetic approximation.

## 9. TerraBlender removal

Drop the TerraBlender dependency entirely.

- **Overworld**: fully custom `BiomeSource`, registered via a dimension JSON override
  (`data/minecraft/dimension/overworld.json` → custom `biome_source` type + `MapCodec`) or
  NeoForge's chunk-generator substitution hook.
- **Nether/End**: not app-tuned today, so they don't need the new patch engine. Replace
  `FilterNetherRegion`/`FilterEndBiomes`'s TerraBlender `Region` registration with direct,
  vanilla-native climate point registration (`MultiNoiseBiomeSource`-style) using the same
  raw scanned positions already in use. No third-party worldgen dependency left anywhere in
  the mod.

## 10. Mod restructuring

The current name/package (`terrablender-custom-region`, `com.mineutze.filtermod`,
`FilterRegion`/`FilterMod`) stops fitting once TerraBlender is dropped and this becomes a
full custom `BiomeSource`. Recommend renaming (e.g. `mineutze-worldgen` /
`com.mineutze.worldgen`) as a first mechanical step before writing the new Java — not a
blocker for this spec.

## 11. Effort / risk summary

- **Terrain-class calibration (highest risk of silent wrongness)**: needs an empirical dump
  of `(continentalness, erosion, weirdness) → actual generated height` from this modpack's
  real noise router, not assumptions carried over from vanilla — BOP and other mods may
  alter the router. Without this, terrain-class buckets are guesses.
- **Determinism of patch growth (highest implementation risk)**: untested pattern in this
  codebase; needs the discovery-order test from §6 before trusting it in a real world.
- **3D underground performance**: unproven cost of resolving 3D patches at chunk-gen speed;
  needs a memoization/coarse-cell strategy validated under load.
- **Python tooling migration**: moderate effort, mostly config-shape changes (§8), not a
  rewrite of the UI framework.

## 12. Open items / next steps

- Calibrate terrain-class thresholds from a real in-game noise dump (not vanilla defaults).
- Decide the size unit: patch radius vs. area in blocks² vs. raw block count.
- Prototype the patch growth engine standalone (plain Java, no Minecraft dependencies)
  and validate determinism before wiring it into `BiomeSource`.
- Rename the mod/package.
- Migrate Python tooling incrementally: patch-config UI first, retire
  `ParameterPoint`-era code (`terrablender_codegen.py`, `biome_grid.py`'s anchor logic) last.
