# Terrain Coherence — Phase 2 (goal for the next session)

Status: **not started — planning/handoff only.** This document exists so a fresh
conversation can pick up exactly where this one left off without re-deriving any of the
investigation below. Do not start implementing from this doc without the user's explicit
go-ahead in that session — it captures a *decision about direction*, not an approved
implementation plan.

Phase 1 (everything in `DESIGN.md` — the custom `BiomeSource` replacing TerraBlender,
patch-growth placement, terrain-class gating, domain-warped borders, underground
linkage) is **done and working**. This document is about a deeper problem Phase 1's
testing exposed, which Phase 1's architecture cannot fix no matter how much more it gets
tuned.

## 1. The problem, precisely

Phase 1's whole premise (`DESIGN.md` §2): a custom `BiomeSource` can read the same
climate signal (continentalness/erosion/weirdness) that drives terrain shape, and gate
biome eligibility on it directly, instead of guessing via hand-placed anchor points like
TerraBlender did. This is a **reactive** design: climate noise → terrain shape (computed
by the world generator, independently) AND climate noise → biome decision (computed by
us). Biome selection *reacts* to whatever terrain the generator already decided to make;
it can never *cause* terrain to be a particular shape.

This modpack's terrain generator is **Tectonic** (`minecraft/mods/tectonic-3.0.19-neoforge-1.21.1.jar`),
not vanilla — this was not known when Phase 1's architecture was designed. Tectonic
replaces vanilla's NoiseRouter with its own, and critically: **its "region" system**
(internal names: club/diamond/heart/spade) that produces dramatic shape features
(jungle pillars, badlands/spade ridges, general jaggedness) is driven by noise
(`region_selector`, `continent_selector`) that is only **partially** correlated with the
climate axes we can read — enough that calibrating our thresholds against real generated
height would likely improve the *broad* picture, but specific named shape features are
layered on top via what amounts to separate noise, decoupled from what our
`TerrainClassifier` can see. Confirmed symptom: sand spires generating inside
desert-assigned areas, jungle pillars outside jungle-appropriate terrain, terrain in
general "not consistent with biome at all" per direct user testing.

No amount of tuning `TerrainClassifier`'s thresholds fixes this, because the information
needed to predict a spire isn't fully present in the signal we're allowed to read. The
mismatch is architectural, not a calibration gap.

## 2. Decision made at the end of the Phase 1 conversation

The user has decided: reactive biome-follows-terrain, even perfectly calibrated, cannot
give the coherence guarantee the project needs ("a desert is a huge plain of sand with
little water, not negotiable"). The fix has to invert the causality: **our biome
placement decision (size, adjacency, terrain-class intent) must drive terrain shape, not
react to it.** This will likely require modifying or forking Tectonic's terrain
generation — the user states Tectonic is MIT-licensed, which if true means this is
legally open — **this has not been independently verified in this conversation, confirm
it before any forking/code-reuse work begins.**

## 3. What was considered and explicitly rejected as insufficient

- **Calibrate `TerrainClassifier` against real Tectonic-generated height** (tooling
  already built and working: `/dump_height_samples` + `calibrate_terrain_classes.py`,
  including a river/depression pass — see `DESIGN.md` §13). Would help the broad
  correlation, confirmed real signal exists (Tectonic's `biome_parameter/erosion` does
  incorporate `region_selector`), but was never expected to fully solve named,
  semi-independent shape features like spires. Not wasted work — still worth running for
  whatever residual improvement it gives — but not sufficient alone, and the user moved
  past this before it was even run.
- **Datapack-override the specific named Tectonic density functions responsible for the
  worst mismatches** (`data/tectonic/worldgen/density_function/region/heart/jungle_pillar.json`,
  `region/club/badlands_ridge.json`, `region/spade/{upper_tier,lower_tier}.json` — exact
  paths confirmed by extracting and reading the jar's bundled resource pack, see §5).
  Technically sound and low-risk (climate signal is computed before final terrain shape
  in the generation pipeline, so gating those specific layers on our biome decision
  isn't circular), and was proposed as an honest 80/20 — kills the most visible mismatches,
  leaves Tectonic's base region/continent shape untouched. **User wants to go further
  than this** — a targeted override of a few named features still leaves the *base*
  terrain shape (which region a location falls in at all) fully outside our control.

## 4. What's believed necessary now (unconfirmed — real investigation needed)

Full inversion: compute the biome/size/adjacency/terrain-class decision essentially
independent of reading Tectonic's *shape* output (may still read raw climate-ish noise
for broad placement, or may not need to at all), then make terrain generation —
Tectonic's own density function graph, forked and modified, or a new one built to match
its quality — **constrained by** that decision rather than the reverse. This is a much
larger undertaking than anything built in Phase 1; treat it as its own project, not a
continuation/patch.

Open questions for next session's investigation phase (research first, no code):
1. Verify Tectonic's actual license terms (confirm MIT, check for any additional
   restrictions e.g. on redistribution of modified jars) before deciding to fork vs.
   reimplement vs. hybrid.
2. Map Tectonic's *full* density function graph (not just the spire/ridge features
   already found) to scope what "biome-driven" would actually need to touch — the
   `region_selector`/`continent_selector`/`terrain_spline` machinery is the likely core,
   not just the named shape-feature files.
3. Decide whether biome *placement* (our existing size/adjacency system, `PatchGrowthEngine`)
   can run fully decoupled from any real terrain signal at all (pure position + world
   seed + authored rules) — this is the crux of inverting the pipeline order. If yes,
   Phase 1's terrain-class *gating* concept (a biome can only grow where real terrain
   already matches) inverts into terrain-class *intent* (a biome declares what it wants;
   generation is made to comply).
4. Decide the actual mechanism: forked Tectonic density functions reading our placement
   decision as an input, vs. a new parallel density-function layer, vs. something else.
5. Scope how much of Tectonic's terrain *quality* survives the intervention — the more
   we force compliance, the more we risk flattening exactly the "realistic, beautiful
   terrain" the user explicitly doesn't want to lose. This is a real tension, not a
   solved problem — expect a tradeoff, not a free lunch.

## 5. Concrete technical grounding already established — don't re-derive this

From a prior investigation this session (full report available in conversation history
if deeper detail is needed, summarized here):

- **Mod**: `tectonic`, version `3.0.19`, NeoForge 1.21.1. Jar:
  `minecraft/mods/tectonic-3.0.19-neoforge-1.21.1.jar`. Packwiz metadata in
  `mods/tectonic.pw.toml` (CurseForge 686836) and `minecraft/mods/.index/tectonic.pw.toml`
  (Modrinth `lWDHr9jE`).
- **Config**: `minecraft/config/tectonic.json` — real, live tunables including
  `continents.jungle_pillars` (currently `true`; set `false` to kill jungle-pillar
  generation entirely with zero engineering — already a real, available lever, unrelated
  to whether Phase 2 happens), plus `continents_scale`/`erosion_scale`/`ridge_scale`/
  `vertical_scale` and others whose exact numeric effect lives in Java-side mixin classes
  (`NoiseState.class`, `NoiseBasedChunkGeneratorMixin.class`) not yet decompiled.
- **Mechanism**: Tectonic ships mixins (`tectonic.mixins.json`,
  `tectonic_1.21.1.mixins.json` targeting `NoiseBasedChunkGeneratorMixin`, `NoisesMixin`,
  `BlendingDataMixin`, `HeightmapMixin`, `TemperatureModifierMixin`, `WorldCarverMixin`,
  etc.) **and** bundles a full resource-pack/datapack tree inside the jar at
  `resourcepacks/tectonic/...` with multiple selectable overlays (`overlay.datapack`,
  `overlay.mod` — most likely active given `general.mod_enabled=true`, not confirmed —
  `overlay.clifftree`, `overlay.terratonic`, `overlay.ultrasmooth`, `overlay.no_carvers`).
  This completely replaces vanilla's `data/minecraft/worldgen/density_function/overworld/noise_router/*`
  and `data/minecraft/worldgen/noise_settings/overworld.json`.
- **Climate axis redirection**: vanilla's `continents`/`erosion`/`ridges` (weirdness)
  router entries are redirected to Tectonic's own density functions
  (`tectonic:noise/full_continents`, `tectonic:biome_parameter/erosion`,
  `tectonic:biome_parameter/ridges`), with different underlying noise octaves than
  vanilla's known values. `temperature`/`vegetation` remain vanilla-driven (unchanged) —
  so biome-selection climate on those two axes is comparatively trustworthy; continentalness/
  erosion/weirdness are not vanilla-equivalent at all.
- **Region/shape system**: terrain shape comes from `region/{club,club_weak,diamond,heart,
  spade,spade_weak}` density functions, selected via `terrain_spline/offset/regions.json`
  using a spline over `tectonic:noise/continent/erosion` combined with
  `tectonic:noise/region_selector` (its own largely-independent noise field,
  `noise/region/selector.json`).
- **Named problem features** (confirmed via reading the actual JSON):
  - `region/heart/jungle_pillar.json` — literally
    `{"type":"minecraft:mul","argument1":"tectonic:__constants/jungle_pillars", ...}`,
    where the constant reads straight from `tectonic.json`'s `continents.jungle_pillars`
    config key at runtime.
  - `region/club/badlands_ridge.json`, `region/spade/{upper_tier,lower_tier}.json`,
    `region/diamond/dune/*` — similar `abs(noise)`-ridge-style density functions, no
    equivalent single config toggle found for these ones.
  - General mountain jaggedness (`mountain_ridges/ridges`, `mountain_ridges/weathering`)
    applied broadly wherever continentalness/jaggedness factor is high, again independent
    of biome.
- **Not yet determined**: exact numeric behavior of the Java-side mixin config sliders
  (would need decompiling `NoiseState.class` and `NoiseBasedChunkGeneratorMixin.class` —
  not done, out of scope for a read-only investigation).

## 6. Phase 1 system — unaffected regardless of Phase 2's outcome, for now

`CustomOverworldBiomeSource`, `PatchGrowthEngine` (seeded patch growth, size/adjacency/
terrain-class gating, domain-warped organic borders, small-island dissolve),
`UndergroundPatchGrid` (exact surface-to-cave linkage), `TerrainClassifier` (calibration
tooling built, not yet run for real) — all confirmed working, all documented in
`DESIGN.md`. None of this needs to be touched to start Phase 2 investigation. Depending
on what Phase 2 concludes, Phase 1's *terrain-class gating* concept may end up either:
(a) fully superseded (if biome decisions end up driving terrain directly, there's no
more "does real terrain match" question to gate on), or (b) retained as a complementary
mechanism for whatever Phase 2's intervention doesn't cover (e.g. still useful for the
`OCEAN`/`COAST`/`RIVER` split, which may not need to be terrain-*driving* the same way
mountainous/spire features do). Don't assume either outcome going in — decide it as part
of Phase 2's own design work.

## 7. Suggested entry point for next session

Start with §4's open questions as research (no code), particularly #1 (license) and #3
(can placement decouple from real terrain fully) — #3 in particular determines whether
this is "a large but bounded density-function project" or "we're now also redesigning
`PatchGrowthEngine`'s core eligibility model." Don't skip straight to forking Tectonic's
jar before that's answered.
