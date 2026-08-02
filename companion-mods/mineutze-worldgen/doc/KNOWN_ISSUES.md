# Known issues / backlog

Running list of confirmed structural problems in the region solver, found by
code review and by the standalone export CLI (`com.mineutze.worldgen.export`,
`./gradlew :worldgen-core:run --args="<worldSeed> <regionSpan> <outputDir>"`).
Ordered roughly by visual/structural impact, not urgency.

## Open

### 0. `GrowthCohesionProfile.directionalRunMajor/Minor` deadlocks whenever minor >= 2
Per-biome shape identity (`GrowthCohesionProfile`, `BiomeNode.cohesionProfile()`):
lets a biome require a minimum count of already-claimed same-biome neighbors to
grow (blocks 1-cell-wide necks — see the "Fixed" entry below) and, separately,
a directional run requirement meant to encourage elongated growth (rivers,
coastlines) along one axis while still requiring some cross-section thickness
on the other.

The `minSameBiomeNeighbors` half works (Moore-neighborhood count, see below).
The directional-run half does not: `satisfiesDirectionalRun` requires a
perpendicular run of `directionalRunMinor` cells to already exist through the
candidate before it can join, but growth claims one cell at a time — the very
first cell extending into a new row/column can never have that thickness
pre-satisfied, no matter how large the patch already is elsewhere. Confirmed
via the export CLI: `ocean` (the only biome configured with a directional
profile, major=4/minor=2) got dropped in *every* region, every time — 100%
failure rate, not a tuning issue. `ModBiomeGraph.ELONGATED` currently falls
back to `COMPACT` (no directional requirement) until this is redesigned.

Likely fix: stop treating the directional requirement as a hard per-cell
gate. Turn it into a frontier-ordering *preference* instead — cells that
extend an existing run get tried first (lower sort key), same mechanism
`GrowthEngine`'s seed-distance-plus-jitter ordering already uses — layered on
top of the `minSameBiomeNeighbors` check, which stays a hard gate and keeps
blocking thin necks regardless. That gets genuinely elongated emergent shapes
without a hard requirement that can never be satisfied by construction.

### 1. `PatchOpener` trims short tendrils but not long seams between two patches
`PatchOpener.openThinProtrusions` (erode any cell not part of a full 2x2
same-biome square, then re-dilate via `GapFiller`, looped to a fixed point —
see "Fixed" below) measurably works on a short spur sticking off one solid
blob: proven both by unit test (a 4-cell protrusion trims to 0-2 cells) and
by the reported in-game case that motivated it. It does much less for a
*long* winding seam that runs between two roughly-equidistant patches —
measured on a real 9-region export (seed 42): total spur-cell count barely
moved with looping (114 → 113 out of 9216 cells), and the two longest spur
*runs* went 16→14 and 11→11 (unchanged).

Root cause is different from the tendril case: along a long seam, boundary
cells often get enough Moore-neighborhood support from BOTH flanking patches
at once to immediately qualify for re-dilation from either side the moment
they're eroded — erosion removes it, `GapFiller` regrows it right back from
whichever side reaches it first, and the fixed-point loop correctly detects
that as "no progress" and stops (verified: this is intentional, not a bug in
the loop itself — an actual infinite oscillation would be worse). The
technique that works for tendrils (a 2x2-completion requirement) isn't
strong enough to un-stick a seam that's mutually reinforced from both sides.

Two structurally different problems were sharing the "corridor" label. Short
tendril off one blob: solved. Long seam between two blobs: still open — was
about to be investigated as a follow-up (possibly a stronger structuring
element, or a dedicated seam-width pass distinct from `PatchOpener`) when
this doc entry was written.

### 2. Hard straight-line seam at every region boundary
Patches stop dead at the 2048-block region edge instead of curving across
it. `GrowthEngine` only ever grows a patch within its own region's cells
(`GrowthEngine.attemptGrowth`, `regionCells` is `grid.cellsInRegion(region)`
— never crosses into a neighbor). `fixedBoundaryCells` only stops an
*illegal* adjacency from forming across the seam; it does nothing to make a
patch's outline continue its own curve into the next region, because by the
time the neighbor region solves, the first region's patch shapes are already
finalized and cached. Net effect: legal, but visually a straight cut wherever
a patch happens to reach the region edge. Confirmed visually via the export
PNG's region-boundary grid lines.

Needs a real fix, not a placeholder tune — likely means either growing a
margin of "provisional" cells across the boundary before committing, or some
post-hoc boundary-blend pass on the macro-cell grid itself (separate from
`BorderBlend`, which only blends the terrain-shape *value*, not the
macro-cell biome-id grid the patches are drawn from).

### 3. `PatchSizeEnforcer`/`ConnectedComponents` are border-blind
`ConnectedComponents.find` (`ConnectedComponents.java:21`) only traverses
`regionCellSet` — never crosses into `fixedBoundaryCells`, even though
`RegionSolver.cleanUp` merges those in as context. A patch that continues
into an already-solved neighbor gets its size measured using only this
region's slice, which can read as "undersized" when it's actually fine.
`PatchSizeEnforcer` then spuriously merges or `SeparatorWidener`-steals cells
right at the seam. Mainly hits `GapFiller`-born patches near region edges
(grown patches already self-satisfy their min size before commit, so
they're not the trigger). Related to #1 — both are "region edges don't know
about their neighbor's actual shape" bugs, but this one is a correctness
bug (wrong biome assigned), not just a cosmetic seam.

### 4. Cross-region solve is order-dependent, and spawn sits on the worst point for it
`fixedBoundaryCells` feeds directly into every legality check
(`GrowthEngine.isLegal`, `GapFiller`, `PatchSizeEnforcer`,
`SeparatorOrganicizer`), so whichever of a region's neighbors solves *first*
is unconstrained, and the rest stitch against it — solve order changes the
result. Not fixable in general (infinite lazy world, see
doc/ARCHITECTURE.md's "never force-solve a neighbor"), but worth documenting
because world origin (0,0) sits exactly on a region-grid corner (regions are
2048 blocks, aligned to multiples of 2048) — spawn is the meeting point of
4 regions, the single worst-case spot for this. The standalone export CLI
always solves NW-first (`RegionGridSolver`'s fixed `dx`/`dz` loop order), so
its output near that corner won't necessarily match what a live world
produces there, even for the same seed. Not a bug to fix, but a caveat
anyone comparing exported output to in-game output needs to know.

### 5. `BudgetPlanner` is adjacency-blind
`BudgetPlanner.plan` (`BudgetPlanner.java`) draws random `(biome, size)`
demands with no regard for `AdjacencyRule`s. A graph with sparse legal-
partner counts can get budgeted demands that can never legally coexist,
wasting solve work and capacity that ends up as filler instead of the
intended biome. Doc explicitly accepts drops as fine ("dropped, not forced
or shrunk"), but the budget itself could be adjacency-aware to reduce how
often that happens.

### 6. No per-biome rarity/weight
`BiomeNode` has no weight field; `BudgetPlanner` draws uniformly among all
node ids. Every biome (mushroom fields, badlands, plains) is equally likely
to be *drawn* — actual placement frequency is purely a side effect of each
biome's size-range math, not a designed rarity. Only matters if rarity
control becomes a design goal.

## Fixed

### `GapFiller` used different, more myopic logic than `GrowthEngine`
No good reason for it — `GrowthEngine` grows one biome from a seed toward a
target size via flood-fill with legality and cohesion checks; `GapFiller`
independently reinvented a per-cell majority vote among immediate neighbors
only, no lookahead, and — until this fix — no cohesion check at all, so any
leftover territory (dropped demands, patches that didn't quite meet) could
fill in via arbitrary local voting instead of coherently extending whichever
patch was actually closest. Replaced with multi-source BFS (discrete
Voronoi): every already-assigned cell seeds growth of its own biome into
the unclaimed territory simultaneously, each gap cell joining whichever
patch's frontier reaches it first, reusing `GrowthEngine.isLegal` and the
new `GrowthEngine.satisfiesCohesion` (split out from the seed-bootstrap-aware
version `GrowthEngine`'s own flood-fill needs — gap-fill extends already-large
patches, so it never has that bootstrap problem and applies the check
directly). `GapFiller.fillGaps` gained a `worldSeed` parameter for
deterministic tie-breaking between competing patches at equal distance (same
jitter technique as `GrowthEngine`'s own frontier ordering).

Covered by new `GapFillerTest` (no dedicated test existed for the old
implementation): nearest-patch-wins, same-seed determinism, never creates an
illegal adjacency (the specific risk raised before implementing this), full
coverage still falls back to filler when nothing else is reachable, and
cohesion still blocks a patch from flooding through a 1-wide gap during fill
(it may touch the gap cell itself — strong Moore-neighborhood support from
the solid patch behind it — but can't propagate further into open territory
beyond that single cell, since nothing there has 2+ same-biome neighbors
yet). Confirmed via export CLI on seed 42: 0 illegal adjacencies across a
9-region swath, visibly smoother/more coherent gap boundaries than the
majority-vote version.

### No check anywhere stopped a "ridge" that's 1-wide but flanked on one side
`GrowthEngine`/`GapFiller`'s Moore-neighborhood cohesion check
(`minSameBiomeNeighbors`) blocks a truly isolated 1-wide bridge, but a shape
that's 1-wide in one axis while flanked by mass on one side can score just
as high on that count as ordinary blob-boundary growth — the count is
direction-blind. Found via a real in-game example: a forest tendril with
Moore-count 4, well above the threshold of 2, that still read as an obvious
thin corridor. Raising the threshold doesn't cleanly fix it either — that
exact tendril would need threshold 5 to block, which also blocks plenty of
ordinary growth (typically scores 3).

Added `PatchOpener` (morphological "opening": erode then dilate). A cell
only survives erosion if it belongs to at least one fully-same-biome 2x2
square — a 1-wide line never completes one, no matter its diagonal flanking
support. Dilation reuses `GapFiller.fillGaps` directly rather than
reinventing regrowth. Looped to a fixed point (stops the moment the spur
count stops strictly decreasing, not just at zero — some spurs settle into a
stable erode/reclaim cycle rather than shrinking, and re-detecting "no
improvement" catches that without an infinite loop; `MAX_PASSES=50` is a
sanity cap on top, not expected to bind). Gated per-biome: skipped entirely
for any biome below the cohesion-to-open threshold (e.g. `UNRESTRICTED`),
since deliberately-thin shapes are that biome's actual identity.

Covered by new `PatchOpenerTest` (5 cases): trims a long protrusion while
keeping the solid core, no-ops on an already-solid block, never creates an
illegal adjacency, fully-eroded patch still leaves every cell assigned, and
`UNRESTRICTED` biomes are exempt. One bug caught by the test suite before
shipping: the "skip `GapFiller` entirely when there's nothing to erode"
optimization silently broke full-coverage for any caller whose input wasn't
already fully populated — not reachable via the real pipeline (`GapFiller`
always runs immediately before `PatchOpener` in `RegionSolver.cleanUp`), but
a test that didn't mirror that ordering hit it immediately. Documented as an
explicit precondition on `PatchOpener.openThinProtrusions` rather than
defensively working around it.

Measured effect is real but narrower than hoped — see open item #1 above:
solves short tendrils, does much less for long seams between two patches.

### `SeparatorWidener` produced snake/corridor shapes instead of blobs
Frontier was a plain `TreeSet` ordered by raw `(x, z)` coordinate
(`SeparatorWidener.java`), no distance preference — systematically extended
in one direction while satisfying its target size. Every required separator
came out a long thin tunnel instead of a compact patch, even once correctly
sized. Fixed by ordering the frontier by distance from the undersized
patch's own centroid (same idea `GrowthEngine` already uses via its
seed-distance ordering). Confirmed via export CLI: bounding-box fill ratio
went from ~0.05-0.1 (thin snake) to 0.3-0.9 (compact blob) on the same seed.

### `BudgetPlanner` packed regions to ~100% capacity, starving growth of room
Drew demands until `capacity - used < smallestMinCells` — left ~0 slack, so
late-placed demands had nothing but thin leftover slivers to grow into
(contributed to the corridor problem above). Capped at `TARGET_PACKING_FRACTION
= 0.72` of capacity (placeholder, tune from visual output like every other
grid/blend constant), leaving real room for `GrowthEngine` to stay round and
handing the rest to `GapFiller`'s neighbor-majority sweep instead.

### `GrowthEngine` had no way to enforce a biome's own shape identity (added, half-working)
Any cell touching the growing patch anywhere (1 same-biome orthogonal
neighbor) could join — nothing stopped a patch from snaking a 1-cell-wide
neck around an obstacle to reconnect elsewhere, independent of the
`SeparatorWidener` bug above (this hit normal `GrowthEngine` growth, not just
required separators). Added `GrowthCohesionProfile` (`minSameBiomeNeighbors`)
on `BiomeNode`, checked at claim-time in `GrowthEngine.satisfiesCohesion`.

First attempt counted same-biome neighbors orthogonally-only and deadlocked
completely (see #0 above for the diagnosis pattern) — a solid convex blob's
exterior boundary cell only ever touches ONE already-claimed cell
orthogonally, so any threshold above 1 made growth dead-end after the seed's
5-cell bootstrap ring, for every biome, always. Confirmed via export CLI: a
`minSameBiomeNeighbors=2` profile turned the entire map to flat filler, zero
non-filler cells anywhere. Fixed by counting over the full 8-cell Moore
neighborhood (orthogonal + diagonal) instead — a true 1-wide corridor cell
still touches only 1 Moore-neighbor (correctly blocked), a 2-wide-or-thicker
extension touches 2+ (correctly allowed). Confirmed via export CLI on the
same seed: dropped-demand count back to baseline (0-1 per region vs. 45
total with the broken version), solid blobby shapes, zero illegal
adjacencies. The directional-run half of the same feature is NOT fixed —
see #0.
