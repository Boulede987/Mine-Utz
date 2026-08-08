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

### 1. Hard straight-line seam at every region boundary
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

### 2. `PatchSizeEnforcer`/`ConnectedComponents` are border-blind
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

### 3. Cross-region solve is order-dependent, and spawn sits on the worst point for it
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

### 4. `BudgetPlanner` is adjacency-blind
`BudgetPlanner.plan` (`BudgetPlanner.java`) draws random `(biome, size)`
demands with no regard for `AdjacencyRule`s. A graph with sparse legal-
partner counts can get budgeted demands that can never legally coexist,
wasting solve work and capacity that ends up as filler instead of the
intended biome. Doc explicitly accepts drops as fine ("dropped, not forced
or shrunk"), but the budget itself could be adjacency-aware to reduce how
often that happens.

### 5. No per-biome rarity/weight
`BiomeNode` has no weight field; `BudgetPlanner` draws uniformly among all
node ids. Every biome (mushroom fields, badlands, plains) is equally likely
to be *drawn* — actual placement frequency is purely a side effect of each
biome's size-range math, not a designed rarity. Only matters if rarity
control becomes a design goal.

## Fixed

### `SeparatorOrganicizer` gated on a whole connected component's shape, so a biome that sprawls into one huge component (plains, spanning 800+ cells across many regions) never got organicized anywhere, even where a specific stretch was a perfectly straight corridor
User feedback after the pinch/diagonal/width-3 fixes above: "plains still
act as a border, even if it is thicker... These are NOT blobs, they are
lines." Measured directly rather than assumed: a plains separator's width
after `SeparatorWidthEnforcer` ran was a perfectly uniform 3-4 cells across
every one of 16 sampled rows in the middle region — zero organic variation,
a straight-edged corridor.

Root cause: `SeparatorOrganicizer.isElongated`/`isRequiredSeparator` (the
old gate deciding whether to bulge a patch's edges at all) measured the
cell's WHOLE connected component's bounding-box aspect ratio. Correct for a
small, genuinely wall-shaped separator — wrong for plains, which connects
through many regions into one 807-cell component spanning nearly the entire
9-region export (bbox -29,-32 to 31,63). That component's overall aspect
ratio is ~1.57 — well under the 3.0 threshold — so the gate read "this is
blobby enough overall" and skipped bulging entirely, even on the specific
corridor-shaped stretch inside it.

Fixed by measuring shape LOCALLY per cell instead of globally per
component (`doc/Modelisation/ActivityDiagram/separator-organicizer-local-corridors.puml`):
a cell counts as a corridor cell if its bounded run (extracted into a new
shared `AxisRunFinder`, reused from `SeparatorWidthEnforcer`) on either axis
is capped by a real, mutually-incompatible-with-each-other biome pair and no
wider than `CORRIDOR_WIDTH_THRESHOLD` (6). Corridor cells get clustered
(same `ConnectedComponents.find`-on-a-subset pattern `SeamWidener` already
uses for pinch clusters) and each cluster gets bulged with the exact same
unchanged `bulge()` steal mechanism — only cluster SELECTION changed, not
how a cluster gets bulged once found.

Measured on seed 42: the same middle-region stretch that was a uniform 3-4
cells wide across all 16 sampled rows now varies 3-5 — real organic
variation, not a straight edge. Side effect confirmed harmless: the last
remaining diagonal collision (6→1 from the earlier fix) also resolved to 0,
and pinch points dropped 5→4, both from the extra legal bulging shifting
context slightly, not a regression (re-verified: 0 illegal adjacencies, 0
isolated cells throughout). No test file existed for `SeparatorOrganicizer`
before this — added `SeparatorOrganicizerTest` covering the exact bug
(a big blob + thin corridor tail, whole-component aspect ratio well under
3.0, tail still gets bulged).

### Nothing checked diagonals - two mutually-incompatible biomes could touch at a bare corner (`d./.f`); `DiagonalCornerResolver` gives one of the two cells away
`GrowthEngine.isLegal`/`GapFiller` only ever check the 4 orthogonal
neighbors — nothing anywhere checked the 4 diagonals, so two biomes with no
`AdjacencyRule` (desert/forest, same class as badlands/forest) could end up
touching at a single corner completely undetected. Reported directly by a
user reading the exported ASCII grid, then confirmed programmatically: 6
real corner collisions on the same seed-42 9-region export used throughout
this file's other measurements.

Added a matching check to `validate_biome_export.py` (excludes any pair that
already touches orthogonally too — that's the existing illegal-adjacency
check's job) and a new pass, `DiagonalCornerResolver`
(`doc/Modelisation/ActivityDiagram/diagonal-corner-resolver.puml`): a
corner-only touch can't be fixed by changing the OTHER two corners (the two
colliding cells are still diagonally adjacent no matter what's between
them), so it tries giving one of the two colliding cells itself away to one
of its own orthogonal neighbors' biomes — same bounded-reassignment safety
primitives `PinchResolver` already uses (`GrowthEngine.isLegal`,
`SeparatorWidener.stealingPreservesDonorConnectivity`,
`SeparatorOrganicizer.donorComponentSizeWithout`/`minCellsFor`), plus an
extra check that the replacement biome is actually compatible with the
diagonal neighbor too (otherwise it would just recreate the same collision
under a different name). Falls through to trying the other cell
symmetrically; never forces a resolution.

Measured on the same seed-42 export: 6 → 1 diagonal collisions. The
remaining one is blocked by the same legality/connectivity/min-size
guarantees as everywhere else in this pipeline — never a forced fix.

### Blobs could be exactly 2 cells wide even after `SeamWidener` ran, because its target is an aggregate cell count, not a guaranteed width at every row; `SeparatorWidthEnforcer` targets the specific thin rows/columns directly
Reported directly: a required separator (a patch bridging two
mutually-incompatible biomes, e.g. plains between savanna and ocean) could
run 3-wide for most of its length but narrow to exactly 2 cells at a few
specific rows, even after `SeamWidener` already ran there. Root cause:
`SeamWidener`'s target (`MIN_SEAM_WIDTH_CELLS=9`) is an aggregate cell count
for the whole pinch cluster — hitting that total doesn't guarantee every
individual row/column meets it, so a cluster could satisfy its own target
while still leaving a couple of rows thin. Confirmed on the same seed-42
export used throughout this file: 121 real width-≤2 spots existed on
required-separator patches.

Deliberately scoped narrower than "every patch >= 3 wide everywhere":
ordinary round blobs naturally taper to 1-2 cells wide at their own tips,
and widening there would fight normal organic shape rather than fix a
defect — confirmed with the user before building this (their explicit
call: separators only, not a global invariant). `SeparatorWidthEnforcer`
(`doc/Modelisation/ActivityDiagram/separator-width-enforcer.puml`) only
targets a cell whose bounded run on an axis is under 3 AND the two capping
biomes are mutually incompatible with each other (same "actually bridging
two incompatible neighbors" test `SeparatorOrganicizer` already uses) —
widens by stealing the boundary cell on whichever side is legal and safe,
same primitives as everywhere else in this pipeline. Also covers the
width-1 separator case `PinchResolver` structurally cannot fix: giving a
1-wide separator cell away to either flanking biome would put the two
incompatible flanks directly next to each other, so `GrowthEngine.isLegal`
already blocks that candidate and `PinchResolver` correctly leaves it alone
— widening is the only valid fix for that shape.

Measured on the same seed-42 export, run after the diagonal-collision fix
above: one-cell-wide pinch points 48 → 5, and the 121 real width-≤2
required-separator spots found before this fix → 0 after. Visual blobs
confirmed still solid/organic in the export PNG, not boxier — this pass
never touches ordinary blob boundaries between mutually-legal biomes.

### `PatchOpener` erosion is directionally blind - a cell can be part of a 2x2 square on ONE axis while still 1-wide on the OTHER; `PinchResolver` gives those cells away instead of eroding them forever
Traced a specific still-open pinch point (seed 42, macro-cell (-24,-24) — a
savanna cell pinched east/west between plains and desert) all the way
through the pipeline with `WorldgenTrace` and hand-inspection of the actual
exported grid, per the standing next-step this item's own diagnosis left
open. The real mechanism wasn't the "mutually-reinforced from both sides"
theory this item originally guessed at — measured directly instead:
`PatchOpener.isCore` (must belong to at least one full 2x2 same-biome
square) correctly flagged this exact cell as non-core on *every* pass — no
2x2 square touches it, confirmed by hand against the exported grid — but
its re-dilation step (`GapFiller.fillGaps` against the stricter-cohesion
graph) reclaimed it right back every time anyway. Why: the cell sits
directly below a solid 2x2 block of its own biome, so it scores 3
Moore-neighbors on re-dilation — comfortably past even the stricter +1
threshold — entirely from that ONE block, with zero awareness that the cell
remains exactly 1-wide on the perpendicular axis (a "tab" sticking sideways
off a solid block, not the "reinforced from both flanking patches" shape
originally guessed). Erode and reclaim repeat identically forever; the
fixed-point loop's "no progress" stop condition can't tell that apart from
"actually resolved," so it just gives up with the pinch still there.

Fixed with a new 5th cleanup pass, `PinchResolver`, run once after the
existing 4 settle (`doc/Modelisation/ActivityDiagram/pinch-resolver.puml`):
for every cell 1-wide on an axis (mirroring `validate_biome_export.py`'s own
pinch definition exactly — both neighbors on one axis differ, cell isn't
isolated), try giving it directly to whichever flanking biome is legal
there, as long as doing so doesn't disconnect or undersize its current
patch. Reuses already-proven safety primitives rather than inventing new
ones: `GrowthEngine.isLegal` for full-neighborhood legality,
`SeparatorWidener.stealingPreservesDonorConnectivity` and
`SeparatorOrganicizer.donorComponentSizeWithout`/`minCellsFor` for the same
donor-safety guarantees `GapFiller.makeLegalHere` already trusts for a
bounded single-cell reassignment. Looped to a fixed point like `PatchOpener`
(a reassignment can turn up a new pinch on the cell's other axis), capped at
`MAX_PASSES=20`. Never forces anything — a pinch neither flank can legally
absorb is left alone, same rule as everywhere else in this pipeline.

Measured on the same seed-42 9-region export this item's diagnosis used:
132 → 50 pinch points (~62% reduction), 0 illegal adjacencies, 0 isolated
cells, both before and after — verified via `validate_biome_export.py`, not
eyeballed. Patch count dropped 26 → 19 as a side effect (expected, not a
regression): giving a shared pinch cell away can reconnect two previously-
separate same-biome components into one). Remaining 50 are pinches neither
flanking biome can legally/safely absorb without violating some other
guarantee (adjacency legality, donor connectivity, or donor minimum size) —
a smaller, better-characterized remainder than the original ~130-170, not a
new open item on its own.

### Universal-filler removal broke dead-end resolution; three-tier resolver + eager graph validation fixes it, guaranteed never to crash in a real game
Every `BiomeNode` used to carry a `universalFiller` flag — one designated
node (plains) was required to be legal next to every other node, so
`GapFiller`'s last-resort sweep always had a guaranteed-safe fallback. On
request, this was removed entirely (not replaced with an internal-only
filler): plains got real adjacency restrictions like any other biome, and
badlands lost its `PLAINS-BADLANDS` rule specifically (its only remaining
ally is desert). `BiomeGraph`/`BiomeNode`/`ModBiomeGraph` simplified
accordingly; `GapFiller.resolveRemaining` picked up the termination
guarantee's job instead.

Immediate consequence: any cell touching two mutually-incompatible biomes
directly (forest and badlands share no ally at all) had zero legal
candidates and nothing to fall back to — 100% crash rate confirmed across 6
seeds, `IllegalStateException` from `GapFiller`. Fixed in stages:

**1. `GapFiller.makeLegalHere`** — bounded neighbor reassignment (depth-2
recursive search): if no biome is directly legal at a cell, try reassigning
a blocking neighbor to something that unblocks it (and can itself be made
legal in turn, one layer further out), reverting fully on failure. Safety
guarantees reused from `SeparatorWidener`/`SeparatorOrganicizer`: a
reassigned neighbor's donor patch must stay connected and at/above its own
declared minimum size. Caught and fixed a real bug here mid-session: the
first version returned success after fixing only the *first* blocking
neighbor it found, leaving other blocking neighbors untouched — could
silently leave an actual illegal adjacency on the board while reporting
success. Fixed to keep resolving every blocking neighbor until the cell is
genuinely legal, with full rollback across all of them if it never gets
there (`GapFillerTest.aDeadEndCellGetsResolvedByReassigningANeighborInstead`
exercises exactly this — a cell with two simultaneously-blocking neighbors).

**2. `GapFiller.dropSmallestBlockingPatch`** — depth-2 reassignment fixes
isolated conflicts but not donor scarcity: badlands and forest are
graph-distance 3 apart (`badlands-desert-plains-forest`), so bridging one
contact point borrows cells from a small nearby plains/desert patch — fine
once, but badlands and forest touch at *many* cells in the same region, and
independent bounded fixes compete for the same limited donor material until
one hits its own minimum size and stops being usable (confirmed via
instrumented trace: a plains patch already down to "would drop to 1 < min
22" by the time a later cell needed it). When bounded reassignment is fully
exhausted, `fillGaps` now drops the smallest directly-blocking patch
entirely — exactly as if it had never been placed — and retries the whole
fill so that territory gets reclaimed naturally. Capped at 3 patches per
`fillGaps` call (`MAX_PATCH_DROP_RETRIES`) — resolves the large majority of
real conflicts, but raising the cap empirically just relocated remaining
crashes further into the region rather than eliminating them (tested up to
8), confirming this tier alone can't be a complete guarantee for an
arbitrarily-contested region.

**3. `BiomeGraph` eager graph-distance validation** — `makeLegalHere`'s
depth-2 search can only bridge two biomes that are within 3 hops of each
other in the allow-graph (worked out by hand and confirmed empirically:
`badlands-desert-plains-forest` is exactly 3 edges and reliably resolves
isolated conflicts; a 4-hop chain does not). `BiomeGraph`'s constructor now
BFS-checks every non-adjacent biome pair and throws immediately if any pair
needs more than 3 hops to bridge — catching "impossible in principle" graph
misconfigurations at construction time instead of a runtime crash mid-world-gen.
Deliberately *not* a full guarantee: global connectivity (no isolated node)
is necessary but not sufficient, and this check cannot and does not catch
runtime donor-scarcity (tier 2's problem) — that depends on placement
density, not graph structure. `ModBiomeGraph.DEFAULT` passes as-is
(badlands-forest sits exactly at the 3-hop limit).

**4. `GapFiller.resolveRemaining`'s `force` mode** — the actual
game-crash-proofing tier 1-3 don't fully cover: a region can have enough
simultaneous badlands/forest(+desert) contact points that even patch-drop
retries just move the failure further along without resolving it (confirmed
by raising the cap and watching the crash relocate, never disappear). Since
"never crash in a shipped game" is a harder requirement than "produce
locally-optimal terrain," `fillGaps` now falls through to an unconditional
last resort once patch-drop is exhausted: directly commit whichever biome
best fits the dead-end cell's neighbors (most matching neighbors, same
tie-break as the normal legal path), ignoring adjacency legality entirely,
for that one cell only. A first implementation of this tier was wrong and
caught by re-running the previously-crashing seeds: it cleared the
blocking neighbor cells and relied on the next fill pass to naturally
re-resolve everything — but that fill pass usually just refilled the
cleared cells with the exact same biomes (nothing outside those few cells
had changed), recreating the identical dead end in a loop (hit a 1028-
iteration safety cap on seed 42). Fixed by committing the dead-end cell's
own value directly and permanently instead of indirectly hoping a later
pass wouldn't undo it — `resolveRemaining` never revisits an already-
assigned cell, so this cannot cycle.

Verified on all 6 previously-100%-crashing seeds (42, 1, 2, 3, 4, 5): zero
crashes, and the illegal-adjacency count this last-resort tier actually
introduces is small — 0-58 cells out of 9216 per seed (0-0.6%), each one a
single macro-cell-wide seam, not a sprawling defect. Two seeds (3, 4) never
even reached tier 4. All 69 `worldgen-core` tests pass.

### `SeamWidener` — locally-pinched seams between incompatible biomes (2 false rejections, now enabled)
A giant, otherwise-adequately-sized buffer patch (typically filler) can
still have one local spot pinched thin where it happens to sit between two
mutually-incompatible neighbors — neither `PatchOpener` (checks the
buffer's OWN shape, blind to what's on either side) nor
`PatchSizeEnforcer`/`SeparatorWidener` (checks the WHOLE component's total
size, blind to local width) catch it. The shape, reported directly (o=ocean,
b=badlands, .=plains buffer, x=the exact pinch — reproduced verbatim as
`SeamWidenerTest`'s `widensALocallyPinchedSeamBetweenIncompatibleBiomes`
fixture):

```
..bbb
..bbb
.xbbb
ox...
o....
```

Ocean/badlands have no `AdjacencyRule` (default-deny), so they never touch
directly — always 0 illegal adjacencies, confirmed every measurement below.
The problem is purely buffer WIDTH: 2 cells everywhere except at `x`, where
it narrows to 1 — each `x` cell still has 3+ Moore same-biome neighbors
from the wider buffer nearby, so nothing else in the pipeline ever flags
it. `SeamWidener` finds cells whose neighborhood contains an incompatible
pair neither of which is that cell's own biome, clusters them, and widens
each cluster via `SeparatorWidener.widen`.

**Two attempts were built, measured, and reverted before this one — the
regressions were real measurements, but the wrong conclusion got drawn from
them.** First attempt: plain centroid-distance widening (`SeparatorWidener`'s
existing behavior). Second: gave `widen` a `repelFrom` parameter and had
`SeamWidener` pass every incompatible biome found across the whole pinch
cluster's detection window. Both, measured via "minimum approach distance
between every incompatible pair, full 9-region sequential solve, before vs
after enabling `SeamWidener`," showed pairs getting *worse* (2 pairs on
attempt 1, 4 on attempt 2) — both reverted rather than shipped.

**The measurement itself was the actual bug.** `SeparatorWidener.widen`
only ever REMOVES cells from a donor biome's set to give to the buffer —
and removing elements from a set can only increase or hold steady its
minimum distance to another set, never decrease it. This is provable, not
just measured: the pass cannot mathematically worsen any pair's local
separation. Confirmed by isolating one region with fixed, identical
neighbor-boundary context and comparing "no `SeamWidener`" vs "`SeamWidener`
alone" vs "full cleanup" directly — zero differences across all 7
incompatible pairs. The swath-wide sequential metric that flagged
regressions twice is contaminated by ordinary cross-region order-dependence
(see the open item below): enabling ANY per-region pass shifts which
boundary context downstream regions solve against, which can move an
unrelated pair's number regardless of whether the change itself is sound.
Decisive check: the already-shipped, independently-confirmed-good
`PatchOpener` re-dilation tightening (spur count 113→91, fixed the exact
reported tendril) **also fails that same swath-wide test** (savanna/badlands
2→1) despite being unambiguously correct. The metric, not either version of
`SeamWidener`, was unreliable for judging a single local change.

Re-enabled in `RegionSolver.cleanUp` (the second, `repelFrom`-aware version
— strictly more capable than the first, no reason to ship the simpler one
now that both are known safe). Verified on the correct metrics: 0 illegal
adjacencies (the only real hard invariant), all tests pass, and spur count
*also* improved as a side effect (91→78) — widened pinch clusters end up
more solid, not just wider.

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

**Follow-up, found in real game data (not synthetic export output) once the
new `/mineutzeworldgen exportbiomes` command could scan an actually-
generated world**: a lone forest cell dangling off a solid mass, with
exactly `MIN_COHESION_TO_OPEN` (2) Moore-neighbors of support — one
orthogonal contact plus its one diagonal, nothing more. Erosion correctly
identified it as a spur (not part of any full 2x2), but re-dilation
reclaimed it right back using the biome's own normal cohesion threshold,
same bare minimum a genuine spur needs to have been flagged in the first
place. Fixed: re-dilation now runs against a stricter-cohesion view of the
graph (`PatchOpener.stricterCohesion`, +1 Moore-neighbor for every opened
biome, biomes exempt from opening left untouched) instead of the biome's
own profile — a confirmed ordinary blob boundary scores 3 on re-dilation
(see `GrowthEngine.satisfiesCohesion`'s doc), so requiring one more than the
opened threshold blocks the bare-minimum reattachment without blocking real
regrowth. Measured on the same seed-42 export: spur count 113 → 91 (a real
~19% reduction, well past the ~1% the plain fixed-point loop achieved on its
own). `PatchOpenerTest`'s protrusion case now asserts the exact two cells
that regrew in the live-game report stay gone.

Measured effect is real but narrower than hoped: solves short tendrils, does
much less for a cell backed by a solid block on one axis but pinched on the
other — see the `PinchResolver` "Fixed" entry above for that specific
mechanism and its fix.

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
