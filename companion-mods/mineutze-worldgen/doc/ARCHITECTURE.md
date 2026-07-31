# Architecture (v2, ground-up rewrite)

Goals are in DESIGN.md. This doc is the technical "how".

## The core problem: infinite world vs. global constraint solve

DESIGN.md wants biome distribution computed *before* terrain shape, with
global constraints (adjacency, size ranges). A true constraint solver
normally wants the whole graph in memory. Minecraft worlds are infinite and
generated lazily, chunk by chunk, forever. We can't solve one graph for an
infinite world.

**Fix: solve in cached regions, not globally.**

- Split the world into **macro-cells**: 128x128 block squares. This is the
  grid the constraint solver operates on (not the same as chunks).
- Group macro-cells into **solve-regions**: 16x16 macro-cells = 2048x2048
  blocks. A solve-region is the unit of work: solved once, cached to disk,
  never resolved.
- Solving a region only needs to know the biome assignment of macro-cells
  along its **west and north edges** (from already-solved neighbor regions).
  It does NOT need its east/south neighbors yet.
- Regions are solved in raster-scan dependency order: a region depends only
  on regions with smaller (regionX, regionZ) in scan order. No cycles,
  because nothing ever depends on a region solved later than itself.
- First time a chunk generation request lands in an unsolved region, solve
  that region (using already-cached west/north edges as fixed boundary
  constraints), cache result, then read from cache like any other region
  file.

This mirrors how vanilla already handles other infinite/lazy problems
(structure references, etc.) — never depend on data that doesn't exist yet.

## Layers

1. **Biome Graph (config)** — static data, not generated at runtime.
   - Nodes: biome families (desert, savanna, badlands, ocean, forest, ...)
   - Edges: adjacency rules — allowed / forbidden / weighted preference
     (e.g. desert-savanna allowed, desert-ocean forbidden)
   - Per-edge **blend width** in blocks — how wide the border gradient is
     between that specific pair. Mountains-plains gets a wide blend
     (varied terrain needs more room to transition); desert-savanna can be
     narrow. Not a global constant — a property of the relation.
   - Per-node size range in blocks (desert: 700-1000 wide)
   - Per-node terrain-shape hints (roughness, depth bias, flatness)
   - Per-node feature toggles (rivers off, lakes off, etc.)
   - **Invariant the graph must satisfy**: at least one node ("universal
     filler" biome, e.g. plains) must be adjacency-compatible with every
     other node. This guarantees the solver below always has a legal move
     and never needs to relax a constraint to terminate.

2. **Region Solver** — turns the graph + fixed boundary edges into a
   macro-cell -> biome-id grid for one solve-region. Proper CSP
   backtracking, constraints are never relaxed:
   - Seed placement inside the region (deterministic from world seed +
     region coords) using blue-noise / Poisson-disc spacing tuned to the
     target biome size range.
   - Grow each seed outward across macro-cells (flood-fill / region growth)
     until it hits its target size range or a boundary.
   - On conflict (growth would violate an adjacency rule against a fixed or
     already-placed neighbor): **do not shrink/relax** — try the next
     candidate biome for that seed instead (any biome whose size class
     fits and whose adjacency rules allow every neighbor touched so far).
   - If no candidate biome works for a seed, backtrack to the previous
     seed decision, try its next candidate, and retry forward from there
     (standard CSP backtracking). The universal-filler invariant above
     guarantees this always terminates with a legal assignment — it just
     may fall back to filler biome in the worst case, never a broken rule.
   - Output cached as: macro-cell -> biome-id. This is the ground truth for
     everything downstream.

3. **Density Router** — vanilla's NoiseRouter equivalent, but its
   parameters are selected per-column from the biome-id at that column's
   macro-cell (with blending near macro-cell borders so edges aren't
   literal squares). Desert cell -> flat/shallow density params; badlands
   cell -> rocky/uneven; ocean cell -> deep. This is the "shape follows
   biome" requirement from DESIGN.md.

4. **Feature Gate** — per-biome config toggles vanilla features/carvers
   (rivers, lakes, etc.) based on the resolved biome-id, not vanilla's
   climate params.

## What replaces vanilla's multi-noise biome source

Nothing samples temperature/humidity/etc. noise to *pick* a biome anymore.
The macro-cell grid (from the cached region solve) is the sole source of
"what biome is at column x,z". Vanilla noise is only used *within* a biome
for surface detail and for blending across macro-cell borders.

## Cache storage: what vanilla does, and why ours is safe

Vanilla itself already splits per-chunk data into **separate parallel
folders**, each keyed by chunk/region coordinate, sitting next to each
other inside the save: `region/` (block data), `poi/` (points of interest),
`entities/` (entity data). Same storage mechanism (`RegionFile`/Anvil),
three independent folders. Nothing about this is a single sacred format —
it's already a "pick a folder per concern" convention.

So our solve-region cache is just another sibling folder, e.g.
`<world>/mineutze_macrobiome/`, one entry per solve-region coordinate. It
never touches `region/`, `poi/`, or `entities/`, so it cannot corrupt or
conflict with anything vanilla or another mod reads. Simplest safe option:
NeoForge's `SavedData` API, one instance per solve-region (named by region
coords) — NeoForge handles read/write/dirty-tracking/versioning for us, and
it's the standard mechanism for "extra world data that isn't a chunk"
(vanilla uses the equivalent for maps, raid state, etc.). No need to
hand-roll the Anvil region-file format ourselves.

## Decided

- Macro-cell size: 128 blocks. Solve-region: 16x16 cells (2048x2048 blocks).
  Not proven against desired biome sizes yet — will tune from logged
  region-solve output (actual biome widths produced) instead of upfront math.
- Backtracking never relaxes a constraint. On conflict, try a different
  biome candidate first, then backtrack to an earlier seed decision.
  Requires the biome graph to have a universal-filler node so backtracking
  always terminates.
- Blend width is per-adjacency-edge, not a global constant. Placeholder
  value: 10 blocks for every edge. Will adjust per-relation once real
  terrain is visible.
- Cache storage: own sibling folder next to `region/`/`poi/`/`entities/`,
  via NeoForge `SavedData`, one per solve-region. Placeholder scheme, may
  change once real read/write volume is known.
- Backtrack depth cap: sanity limit, not a correctness mechanism (the
  universal-filler invariant already guarantees termination). If the cap
  is hit, log a warning and fall back to the universal-filler biome for
  that seed — signals a graph-config bug (e.g. a node with no viable
  neighbor within reach) without hanging or crashing world gen.

## Open questions (need decisions before implementation)

- Concrete backtrack depth cap number (placeholder, tune from logs like
  cell size and blend width).
