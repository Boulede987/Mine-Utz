# 2026-08-29 — Remove region stitching, keep a shapeless solve-batch

**Superseded 2026-09-20: this whole live hex-mesh/CSP system was deleted and replaced by a
per-world solved plan; see DECISIONS.md's 2026-09-20 entry. Kept as history, describes deleted code.**

Status: proposed, still not built. The blocking condition (the hex-mesh +
edge-displacement pivot, `doc/decisions/2026-08-29-hex-mesh-edge-displacement.md`,
landing its Phase 5 cutover) is now satisfied — `TriangleFieldGeometryRealization`
has exactly one live orchestration seam again (old triangle+growth path
deleted), so this is unblocked whenever it's picked up next.

While chasing that cutover, a real bug surfaced directly in the territory
this doc already flags as "pure cost, not benefit": `fixedBoundaryCells`
plumbing existed to paper over the solve-order asymmetry, and
`EdgeDisplacementRasterizer` turned out to only pre-fill the ONE cell that
made a neighbor face "fixed", never rasterizing that face's own full
displaced polygon — silently orphaning cells whenever a fixed face's
territory spilled into the new region's own cell set. Fixed for now (fixed
faces are rasterized too, see `EdgeDisplacementRasterizer`'s own doc), but
it's a concrete data point for this doc's own argument: the solve-order
asymmetry is a real, recurring source of bugs, not a hypothetical one.

## Motivation

Regions (`RegionPos`, `MacroGridConfig.regionOf`/`cellsInRegion`) don't
obviously earn their complexity. Two separate concerns got raised:

1. **What are they even for?** A region solved first has zero knowledge of
   what a not-yet-solved neighbor will place right next to it
   (`RegionGridSolver`'s own doc). That solve-order asymmetry is a real,
   already-documented source of bugs, not a hypothetical:
   - An illegal adjacency a 3×3 export batch couldn't even see, because
     each region only ever consulted neighbors that had already solved by
     the time IT solved.
   - An undersized ocean patch that survived `PatchSizeEnforcer` because its
     true neighbors hadn't solved yet when it was checked — `PatchSizeEnforcer`
     isn't benefiting from region boundaries here, it's actively fighting
     them.
2. **Regions have a fixed, predetermined SHAPE — a square (grid) partition
   — even with `MacroGridConfig`'s own domain warp giving that square a
   wiggly border.** That's a structural mismatch with the whole point of
   this pivot: biome shape is supposed to emerge from the mesh/CSP/
   displacement, not be pre-imposed by a partition scheme drawn before any
   biome decision happens. A batch of faces to solve together doesn't need
   a shape at all — it only needs to be SOME finite, nameable set of faces.
   Imposing squareness one level up (at the solve-batch level) is the same
   smell this pivot is already removing one level down (at the biome-patch
   level).

## What regions actually earn their keep for (keep these, reshape their input)

- **`FaceFairnessQuota`'s "fair share of area" math.** Genuinely needs a
  finite area to divide by — not optional, just doesn't need that area to be
  square. Any batch's own actual face set/area works the same in the
  formula.
- **Lazy generation + caching (`SeedRegionCache`, `RegionPersistenceStore`).**
  Needs *some* nameable, stable unit to key a cache entry and a solve-lock
  by. A batch id works exactly as well as a `RegionPos` here — the cache/
  lock/persistence machinery itself doesn't care what shape the thing it's
  keying is.

## What's pure cost, not benefit (remove these)

- `RegionBoundaryCollector`'s Moore-8 neighbor scan + margin-depth math —
  exists only to reconstruct cross-batch context that a shapeless,
  order-independent batch design wouldn't need to reconstruct in the first
  place.
- `fixedBoundaryCells` plumbing through `TriangleFieldGeometryRealization`/
  `RegionSolveInput` — the whole reason it exists is the solve-order
  asymmetry; remove the asymmetry, remove the plumbing.
- The solve-order dependency itself, and the solve-lock races in
  `SeedRegionCache` that exist only because two adjacent regions can race
  each other.

## Proposed direction (sketch, not a full design)

Replace the fixed square `RegionPos` partition with a shapeless solve-batch:
some finite cluster of faces/cells assembled on demand around whatever was
actually requested (e.g. grown outward from a query point until it's big
enough for `FaceFairnessQuota` to be meaningful), memoized in one global
store keyed by a batch identity, not by a grid cell. Since `SeedGrid`'s own
seed placement is already batch-independent (a prior, already-shipped design
decision — see its own class doc), nothing in the mesh/seed layer resists
this; the change is scoped to the CSP-batching/caching layer only.

Real work this still requires, not free: `FaceFairnessQuota`'s fairness
formula needs reframing over a batch's own actual (variable, non-square)
area instead of one fixed region's exact area — bounded work, not a
rewrite, but not zero either.

## Why deferred

This decision doc exists to not lose the idea, not to schedule it. Land
after the hex-mesh pivot's Phase 5 cutover (new rasterizer live, old
triangle+growth path deleted) so this next change has one clean orchestration
seam to work against instead of two pipelines mid-migration at once.
