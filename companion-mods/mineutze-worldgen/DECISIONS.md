# Decisions

Current tuning validity - which constants are validated together, against what, and whether that
still holds right now - lives in `worldgen-core/src/main/resources/calibration/calibration-manifest.json`;
run `./gradlew :worldgen-core:calibrationReport` to read it, or `./gradlew :worldgen-core:test` to
enforce it (`CalibrationManifestTest` fails loud on drift). This log stays the history (context,
alternatives considered, evidence at the time) - it does not track current validity on its own.

## 2026-09-27 — Lowering atomWidthBlocks to 100 needs real algorithm work, not simple re-tuning

**Context:** Beach's shape-floor mechanism (`ShapeRule.minPerimeterRatio`, 2026-09-26) is blocked by
beach's own declared minimum (300 blocks = exactly 1 cell at production's `atomWidthBlocks=300`)
having zero shape freedom - a single hexagon's perimeter is fixed, not a range. Investigated whether
lowering `atomWidthBlocks` to 100 (3x finer, `gridSize` scaled 40->120 to preserve the ~10,392-block
tile size, a real 9x cell-count increase, 1,600->14,400) could give small biomes genuine shape
freedom at their own minimum, using the calibration manifest's own newly-tracked constants as the
baseline to probe against.

**Finding:** Simple tuning does not get this resolution to a viable solve within the 5-8 minute
world-creation budget (`DESIGN.md`'s own clarified limit). Measured directly against the real
catalog (`AtomWidthResolutionProbeRunner`, gradle task `atomWidthResolutionProbe`, world seed 42,
schedule's `cycleSteps` scaled 9x alongside cell count):
- Step depth per attempt is the dominant lever, not attempt count: 100 shallow attempts (60,000,000
  steps each) reached only cost 146 in 866s - WORSE than 10 deep attempts (80,000,000 steps) reaching
  cost 102 in 122s. The "many independent cheap tries" dynamic that makes production's 1,600-cell
  reliability real (each attempt has a real chance of converging within budget) does not hold at
  9x the cells - no single shallow attempt gets close, so trying more of them buys nothing.
- Increasing steps per attempt showed real convergence early, then hit a hard wall: 5,000,000 steps
  -> cost 2159; 40,000,000 -> 442; 80,000,000 -> 102; 160,000,000 -> 45; 320,000,000 -> 41 (only 9%
  better for double the steps, in 529.8s - already over budget). Diminishing returns collapsed from
  4.3x to 2.3x to 1.1x across successive doublings - a real plateau, not a patience problem.
- The schedule's `cycleSteps` (1,000,000, tuned at 1,600 cells) scaled proportionally to the 9x cell
  increase (9,000,000) gave only a modest, real improvement (average best cost 1951.2 -> 1842.0
  across 5 seeds at a fixed reduced budget) - not remotely enough alone.

**Conclusion:** the plateau is structural. A single-cell-flip move (the search's main mechanism)
propagates corrective information diffusively across the grid; in a 2D lattice that kind of
equilibration scales with total AREA (cell count), not the sqrt-of-area growth in total cluster
boundary length that a naive "bigger clusters, longer perimeters" estimate would predict - which is
consistent with needing far more than a proportional (9x, or even the observed up-to-8x-and-still-
climbing) step increase to reach the same relative convergence. Reaching this resolution needs a
genuine redesign for a much larger state space (see the coarse-to-fine multigrid direction under
investigation as of this entry), not a config change.

**Alternatives considered:**
- More parallel seed attempts at a shallow step budget instead of fewer deep ones: measured worse,
  not better (see Finding above).
- Scaling the annealing schedule's cycle length alone: measured, real but modest (~6%), not enough.
- A region-based/divide-and-conquer solve (each block of the grid solved somewhat independently,
  boundaries reconciled after): NOT attempted here, and worth naming the real risk explicitly - this
  is close in spirit to the hex-mesh region CSP this project already tried and deleted (see the
  2026-09-20 "One solved biome plan per world" entry's own banner) precisely because per-region
  solving could not be made reliable for every world seed. A new attempt would need to differ in a
  real way (e.g. reconciling via shared boundary state, or seeding from a coarse global solve first),
  not repeat the same independent-regions shape.

**Evidence:** `worldgen-core/src/main/java/com/mineutze/worldgen/plan/AtomWidthResolutionProbeRunner.java`
(gradle task `atomWidthResolutionProbe`); full numbers in commits `5024ce5f` and `4f833740`.

## 2026-09-20 — Groups of biomes and and/or border requirements

**Context:** A requirement named ONE biome, but a beach may touch `ocean` or `lukewarm_ocean`, so one
name was too narrow. The author asked for rules like "one within this group", "one within this group
and one within that group", "one within this group or one within that group", and more.

**Decision:** The catalog gets named `groups` (a name and member biome ids). Each biome's
`requirement` is one text over groups and biome ids: `oceans >= 2 and (forests >= 1 or plains >= 1)`;
`and` binds tighter than `or`, parentheses group, a subject with a colon is a single biome. A share
counts the edges shared with ALL biomes of the group added together. This replaces the flat
`requiredNeighbors` list (a file still holding a non-empty one is refused, never silently dropped).
The search cost stays exact and integer: a share is short by what it lacks, an "and" by the sum of its
parts' shortfalls, an "or" by the smallest of them. The map verifier reads the same requirement over
real border lengths. A requirement that can never be met is rejected unless another "or" branch can
still be met. In the editor, groups and the requirement text are edited in two panels, unreadable
names or text block saving, and removing a group or biome that a requirement still names is refused.
The obsolete graph seeder was deleted: it rewrote the catalog from itself and would have wiped the new fields.

**Alternatives considered:**
- Count the strongest single member of a group instead of the sum: rejected, a beach beside both oceans
  is still a beach on a coast.
- A structured JSON tree for requirements: rejected for a text a person can read and edit in one box;
  it is stored in its canonical printed form and re-read on load.
- Renaming or nesting groups: not built, a group is a flat list and a rename is delete-and-recreate.

**Evidence:** 355 unit tests, including a fuzz test that the search's compiled shortfall equals the
plain reference reading for 300 random requirements, and an HTTP run of every editor endpoint against a
temp copy of the catalog. Stress catalog with three groups and five requirements (beach: warm waters;
snowy beach: cold waters; stony shore: cold ocean; dacite shore: cold waters and a taiga; mangrove
swamp: swamp or floodplain): 16 of 16 world seeds solved and realized (pattern attempts median 3, max 33
of 200; 0.4% worst area drift); an independent script over the saved patterns found all 89 constrained
clusters meeting their requirement, the "or" met through both branches.

## 2026-09-20 — Required borders: a biome's clusters must share a stretch of border with a named biome

**Context:** `requiredNeighbors` (a beach beside the ocean) existed in the catalog schema and the
editor but nothing enforced it; only the deleted old checker read it. The author wants shores and
beaches to touch their water, and to expand the feature later.

**Decision:** A requirement is "every cluster of biome B shares at least N cell-edges with a cluster
of biome X" (`RequiredNeighbor.minCount`, now meaning cell-edges; one edge is about 150 blocks).
One-way: X asks nothing back. It is enforced in three places: `ClusterSizeModel` rejects a
requirement that could never be met (unknown biome, itself, not a legal neighbor, more edges than the
shape rule lets the biome's largest cluster have); the local search prices the shortfall as part of a
cluster's violation, including the clusters of a third biome whose border a cell change takes away;
and `BiomeMapVerifier`/`RealizationSelector` re-check the warped map, holding it to half the planned
border length (`REALIZED_BORDER_FRACTION`, since the warp bends and shortens borders). The editor shows
what the generator would reject as a live error.

**Alternatives considered:**
- Count touching clusters instead of border length: rejected, a beach could touch the ocean at one
  corner.
- Check only after solving: rejected, it would fail worlds instead of steering the search.
- A per-biome shape exception for strips: not built. At 300-block cells a shore cluster is one to
  seven cells and the size range already bounds a strip; measured shapes are blobs hugging the water,
  and the perimeter rule did not get in the way.

**Evidence:** 271 unit tests including exact-cost drift checks; 22 mutants of the new logic all caught.
Shipped catalog with beach requiring the ocean: 4 of 4 world seeds solved (13-57 s). Stress catalog
with four shore biomes each requiring 2 edges (beach-ocean, snowy_beach-cold_ocean,
stony_shore-cold_ocean, dacite_shore-frozen_ocean): 20 of 20 world seeds solved and realized (pattern
attempts median 8, max 34 of 200; pattern seconds median 27.5); an independent script over the
saved patterns found all 92 shore clusters at 2 or more shared edges, half their outline on average.

**Known limits:** (One-biome requirements were widened to groups and and/or in the entry above.) A shore cannot be narrower than one cell (300 blocks
across); a genuinely thin beach needs a border fringe drawn when the map is rendered.

## 2026-09-20 — One solved biome plan per world replaces the hex-mesh region solver

**Context:** The hex-mesh generator solved regions lazily while the player explored, and could not
be made to succeed reliably for every world seed: dead ends appeared under certain queries and were
only ever worked around. Every entry below this one that mentions `regionsolve`, the region CSP,
`WeightedVertexPlacement`, the completeness checks or the dead-end simulations describes that
removed system; it is kept as history, not as a description of the code.

**Decision:** At world creation, solve the whole biome layout once from the world seed (a 40x40 hex
torus of 300-block cells, min-conflicts local search over a joint identity+size model), warp it into
natural borders (area-preserving, divergence-free), verify size, legality and topology on a
2598x2598 raster (4 blocks per sample), and save the raster with the world. Biome lookups are one
array read; the map repeats every 10,392 blocks. Same seed gives the same world. The old system was
deleted (no dead code, no compatibility with old-system worlds); git history keeps it. The package
is `com.mineutze.worldgen.plan`, entry point `WorldBiomePlan`. The graph editor's Deep check and
Coherence proof buttons became one Solve check that builds real plans from the saved graph.

**Alternatives considered:**
- Keep solving lazily while exploring: rejected, several minutes of solving while the player waits
  for chunks is unacceptable; a one-time wait at world creation is fine.
- Complete backtracking or AC-3 on the pattern: never solved the size-coupled model; min-conflicts
  local search does, in under a second at 24x24 and reliably at 40x40. Full investigation (eight
  iterations, a real correctness bug found in the exclusion logic, and the search-efficiency
  ceiling that ended it): `doc/decisions/2026-09-18-toroidal-patch-construction-findings.md`.
- One remixed pattern for all seeds: rejected, per-seed solving was chosen and its reliability
  proven instead (104 of 104 world seeds solved).

**Evidence:** Dev-server run (seed 42): plan created in 32 s on 11 threads, restored in 0.4 s on
restart, `/locate biome` exact and instant, the biomes stored in generated chunks equal the plan at
every quart (0 mismatches in 251,001 checks). Same seed gives byte-identical raster across separate
JVM processes (SHA-256 checked). The user generated a world in the dev-test instance: "generated
quite well, and quickly". Solve check on the shipped catalog: 4 of 4 seeds solved, 16-64 s each.
Design and progress: `doc/decisions/2026-09-20-per-world-tile-integration-design.md`.

## 2026-09-05 — `PRE_GENERATE_RADIUS_BLOCKS`/spawn-area pre-generation sweep

**Context:** Vanilla's stronghold placement (`ConcentricRingsStructurePlacement`,
distance=32/spread=3/count=128) checks all 128 scattered candidate points in ONE burst
(parallel, off-thread) the moment the very first chunk (spawn) is ever generated - before
ANY of the mod's own mesh territory has been jointly resolved. Each point, touched in
isolation, independently locks in its own tiny neighborhood as PERMANENT; a later query
landing in the gap between several of them can find itself boxed in by walls that were
each individually fine but were never resolved TOGETHER - a real, observed crash
(`worldSeed=42`, `TriangleIdentitySizeAssignment` `SearchBudget` exhausted, thrown from
`PlayerRespawnLogic` during world creation itself).

**Decision:** Pre-solve the whole radius as one CONTIGUOUS, step-connected sweep
(`preGenerateSpawnArea`) so every one of vanilla's 128 points lands inside territory
already resolved as one internally-consistent blob, never as an isolated single-point
commit. `PRE_GENERATE_RADIUS_BLOCKS = 25,000`: simulated vanilla's exact placement
formula directly and measured its outermost ring at ~24,192 blocks from spawn (reached
via 8 concentric rings of increasingly many, increasingly distant candidate points) - a
small safety margin above that measured max, not a round guess.
`PRE_GENERATE_STEP_BLOCKS = 2,000`: must stay comfortably under 2x
`TriangleFieldGeometryRealization`'s own `CLUSTER_GROWTH_CAP_RADIUS_BLOCKS` (2500) so
adjacent grid points' own cluster-growth neighborhoods always overlap, guaranteeing the
whole swept area ends up as one single connected region (mirrors why an `ORGANIC_WALK`,
which takes small steps, empirically never dead-ends).

## 2026-09-06 — `READY_TIMEOUT_SECONDS` raised 30→300 after a real production crash

**Context:** The old 30s bound predates the spawn-area pre-generation sweep (originally
sized for "just construct the cache," near-instant) and was never revisited when that
sweep started running synchronously in the same critical section, before
`READY.countDown()`. Real crash (`worldSeed=42`): the sweep measured 31,571ms - just over
the old 30s bound - every worker thread's `awaitReady()` timed out and threw at exactly
30s, one of them reaching `PlayerRespawnLogic`'s unprotected call path and crashing world
creation.

**Decision:** `READY_TIMEOUT_SECONDS = 300` - real margin above any measured pre-gen cost
(order of 10-30s) while still being a finite, fail-loud bound, not an infinite wait - a
genuine mod-loading-order bug (the actual reason this timeout exists at all) still gets
caught, just not mistaken for a legitimately-slow-but-working sweep.

## 2026-09-04 — `signedUnitOffset` discards `Random`'s first draw to fix hash collapse

**Context:** The original version folded `pointIndex` into the seed via one final
`seed = seed * 31 + pointIndex` step, then read the result directly - since that's the
LAST operation before reading, a change of just 1-3 in `pointIndex` only ever perturbs
the seed's lowest few bits, with no subsequent avalanche step to spread that into the
bits the final value actually reads from. Measured directly: the 3 "independent" interior
points on a real edge came out agreeing to 9 decimal places - not independent at all,
just one offset value read 3 times, collapsing the whole multi-point design into a single
smooth bow per edge.

**Decision:** Discard `Random`'s own FIRST draw before using its second.

**Alternatives considered:** One fresh `nextDouble()` call straight from the perturbed
seed - measured to still be too similar (agreeing to ~3 decimal places, better but not
genuinely independent); `Random`'s LCG needs to actually run its multiply-and-add step at
least once on already-mixed state before the tiny `pointIndex` perturbation has fully
diffused into the high bits the returned value is read from.

**Evidence:** With one discarded draw, 5 consecutive `pointIndex` values scatter across
the whole range, no longer clustering.

## 2026-09-04 — `EdgeDisplacement.MAX_RADIUS_FRACTION` universal cap added

**Context:** Found as a real bug, not a defensive guess: when the perpendicular
direction happens to be exactly (anti)parallel to the direction from base to pole, the
point's angle from that pole barely changes at all as it moves - right up until it passes
through the pole itself, where angle is undefined and flips discontinuously. The
bisector-crossing formula's own "same side of the ray" check rejects that crossing as
invalid (dot product lands at exactly zero), leaving the bisector-only computation with
NO limit at all in that direction - measured directly: a synthetic test with the pole
placed exactly above an edge's own midpoint produced a literal `Double.POSITIVE_INFINITY`
safe range for that edge's middle interior point. Not a rare alignment: a REGULAR
hexagon's own center sits exactly on the perpendicular bisector of every one of its
edges, by symmetry, so this hits the middle interior point of essentially every edge in a
near-regular mesh.

**Decision:** `MAX_RADIUS_FRACTION = 0.5` - a universal supplementary cap: a point may
never move closer than this fraction of its own current distance to a pole, regardless of
what the bisector-crossing formula says.

**Evidence:** In the normal (non-degenerate) case this cap is generous enough to rarely
be the tighter of the two bounds anyway (the bisector cap already came out to ~29% of
radius in the hand-verified non-degenerate case this bug was found alongside).

## 2026-09-04 — Dead-end exceptions enriched with full repro context, since there's no logger to fall back on

**Context:** `worldgen-core` has zero logging dependency on purpose (plain fast JUnit,
not NeoForge's "unit testing mode" that boots a whole Minecraft instance), so there's no
separate logger call to fall back on. Found the hard way: a real in-game dead end at
`worldSeed=42`, `RegionPos[-2,8]`, reached only via a structure-placement ring search (far
outside spawn, never exercised by this codebase's own 50-seed 2026-2075 sweep), crashed
with only the region name to go on — not enough to reproduce without re-running the exact
same seed and hoping the same ring search fires again.

**Decision:** Whatever isn't in the exception's own message string is gone once the crash
report closes, so every field a future repro test would need is included directly: the
seed, the region, how many faces were free vs. already fixed by a neighboring region's
own solve, AND the full fixed-face biome assignment itself (the one input a synthetic
single-region test can't fabricate on its own — it depends on whatever neighboring
regions happened to solve first, in whatever order that specific run's worker threads
reached them).

## 2026-09-11 — Sequential, one-face-at-a-time geometry commitment (PERMANENT design principle)

**Context:** Considered whether joint calibration (multiple faces negotiating size
simultaneously, iterating together) or strict sequential commitment (no two faces ever
negotiate simultaneously, full stop) should be the live geometry mechanism.

**Decision:** Sequential, one-face-at-a-time commitment — a DELIBERATE design-principle
choice, not a pure numbers call, kept per explicit user direction even though it measured
WORSE in aggregate. **This is a permanent design principle for this project, not a
tentative choice up for casual revisiting.**

**Alternatives considered:** Joint calibration — the old mechanism's own real live-path
number (219/448, 48.9%) was BETTER in aggregate than sequential's (295/448, 65.8%). Joint
calibration's value was never just resolving coupling, it was giving every face 15 ROUNDS
of repeated correction toward its own target, an iterative-refinement property sequential
commitment does not have (a face decided early, when most neighbors are still baseline
placeholders, never gets to revise once real neighbors are actually decided later).
Rejected anyway.

**Evidence:** Eliminating the tug-of-war is a real, confirmed property of sequential
commitment (the single worst outlier improved, 2.71x vs 3.24x) — a real design-principle
requirement for this project, not just a numbers optimization: a noisy, coupled
foundation makes every FUTURE fix harder to reason about, even when it wins on the
current measurement. See `WeightedVertexPlacement.sequentiallyRealizedVertexPositions`'s
own doc for the full mechanism. **Standing reminder**: the real path back to a better
aggregate number is adding BOUNDED REVISION on top of this sequential foundation (a face
gets refined a few times as more real neighbors resolve, never simultaneously) — NOT
reverting to joint calibration.

## 2026-09-11 — Committed-width threading attempt rejected; achievable-range approach used instead

**Context:** An EARLIER attempt threaded committed neighbor widths through by biasing a
fresh face's own target size down near a tight committed neighbor. Built, measured on the
real live path, and made things WORSE (223/448 vs 219/448 baseline).

**Decision:** Reverted that attempt the same day. Used a completely different consumption
mechanism instead: rather than influencing WHAT TARGET gets picked, compute WHAT'S
ACTUALLY ACHIEVABLE first and hard-exclude/narrow from there (`TriangleIdentitySizeAssignment`'s
mesh-aware `solve` overload, `achievableAreaRangeFor`).

**Alternatives considered:** Target-biasing (the rejected attempt above).

## 2026-08-29 — Hex mesh supersedes the Delaunay-triangle-mesh + competitive-growth path

**Context:** A triangle's vertex degree averages ~6, so two triangles can share only a
vertex (not an edge) and still end up pixel-adjacent — a case the CSP's edge-only
legality graph never checked. That gap was what forced the old rasterizer
(`SeamGrowthRasterizer`/`GrowthGapFiller`) to re-check legality live, per cell, during
growth.

**Decision:** `HexMeshBuilder`'s hex mesh has vertex-degree-3 (exactly 3 faces meet at
every vertex — see `doc/decisions/2026-08-29-hex-mesh-edge-displacement.md`), which makes
edge-adjacency and vertex-adjacency the SAME relation, always — CSP-time legality checking
becomes provably complete, so the rasterizer (`EdgeDisplacementRasterizer`) needs no live
per-cell check at all. `SeamGrowthRasterizer`/`GrowthGapFiller` were deleted outright; the
Delaunay triangulation itself wasn't deleted, just demoted — `DelaunayTriangulation`/
`IndexedTriangle` have zero production callers and live in `src/test` as a generic
(non-hex) `MeshFace` fixture for `TriangleAdjacency`'s own tests (later deleted entirely
in a separate pass, once the hex mesh could stand in as its own test fixture).

## 2026-09-11 — `pickWidth` biases its draw for narrow-tier and committed-neighbor-squeezed biomes

**Context:** A biome whose ENTIRE range sits below `BASELINE_WIDTH_BLOCKS` (a narrow/
small-tier biome, e.g. 300-600) was the real, measured driver of a real "oversized small-
target biome" plateau (e.g. `windswept_hills` up to 2.81x its own target): realized area
is set mostly by surrounding baseline-ish neighbor competition, largely insensitive to
exactly which width the face itself picked, so a SMALLER chosen target only widens the
mismatch while a target closer to baseline narrows it. Separately, a fresh face's width
used to be picked with zero visibility into what its real, already-committed neighbors
locked in — measured directly on the real live path (not the whole-mesh export tool,
which cannot even exercise this): 219/448 (48.9%) real violations, nearly DOUBLE the
whole-mesh tool's own 236/930 (25.4%) — a face committing to a wide target next to an
already-small, already-frozen neighbor has no way to reach it, and that neighbor can
never give back the room since it's already permanent.

**Decision:** `pickWidth`/`biasFor`: a narrow-tier biome's draw skews toward its own
MAXIMUM instead of plain uniform; separately, if ANY real committed neighbor's own width
is below this biome's own range midpoint, bias the draw TOWARD_MIN instead (a genuinely
tight, already-permanent neighbor means drawing from the top of the range asks for more
than the local neighborhood can supply). Deliberately a simple, non-geometric heuristic
(min committed neighbor vs. range midpoint) rather than a real shared-edge/area budget
solve, keeping this class geometry-agnostic.

## 2026-08-23 — Candidate biomes tried in deterministic jittered order, never fixed/alphabetical

**Context:** A real, previously-shipped bug in this codebase's earlier CSP attempt tried
candidates in plain alphabetical order, so any under-constrained face always picked the
same first-alphabetical biome and never backtracked, collapsing a whole real-scale region
to one biome — invisible in small hand-built test fixtures, only surfaced at real scale.

**Decision:** Candidate biomes are tried in a per-`(worldSeed, faceIndex)` DETERMINISTIC
JITTERED order (`candidatesInDeterministicOrder`), plus fairness-biased ranking (see
`FaceFairnessQuota`) — legal candidates ranked by current deficit descending, seeded
jitter breaks ties, result stays deterministic.

**Evidence:** `diverseUnconstrainedMeshDoesNotCollapseToOneBiome` regression test.

## 2026-09-01 — Ternary consistency added; binary AC-3 alone wasn't enough

**Context:** Found by direct measurement, not theory: `worldSeed=2060` against
`ModBiomeGraph.OVERWORLD` at `HEX_SIZE_BLOCKS=325` thrashed for 60+ seconds with binary
propagation alone, recursion depth pinned flat around 230/238 faces the whole time (real
CPU burn, not a hang). Captured its exact residual state directly: 3 mutually-adjacent
faces (a real hex vertex) all narrowed to the same 4-value domain, whose only two legal
pairs were disjoint (no biome legal with members of BOTH pairs) — a real, provable dead
end with zero valid triples, invisible to binary arc consistency (it only ever asks "does
X have some compatible value in Y" one pair at a time, never "does some value work
against BOTH Y and Z simultaneously").

**Decision:** `forwardCheck` also runs ternary consistency (`reviseTriple`) over every
real mesh-vertex triple, interleaved with binary propagation to one joint fixed point (a
shrink from either kind can enable further shrinks of the other).

**Alternatives considered:** Encoding this as a `BiomeGraph` construction-time rule
instead — rejected: checked directly, every one of the 262 real legal pairs in
`OVERWORLD` already extends to at least one real triangle somewhere in the graph (zero
exceptions), including both pairs from the `worldSeed=2060` dead end. The graph itself
was never deficient; the specific triangle's domain got narrowed down to exactly those
two disjoint pairs by unrelated legality pressure from each face's OTHER neighbors, a
purely dynamic, per-instance narrowing no static graph property can predict or rule out
in advance.

## 2026-09-01 — AC-3 propagation replaces plain single-hop forward checking

**Context:** Plain forward checking (correct back when this ran over the deleted
triangle mesh, ≤3 neighbors per face) only re-examines the face just assigned's own
direct neighbors; it never looks further, so a real conflict two or three faces away
went completely undetected until the search physically backtracked its way there.
Measured directly: a real 238-free-face mesh at `worldSeed=2034` against
`ModBiomeGraph.OVERWORLD` took 13 real seconds with plain forward checking and was still
running (300+ CPU-seconds, 222-deep recursion, confirmed via a live thread dump) before
propagation was added. The old justification (AC-3's extra pruning "not worth it" at
degree ≤3) went stale the moment the mesh pivoted to hex (up to 6 neighbors, with
consecutive ones also mutually adjacent via vertex-degree-3 — a dense, near-clique
neighborhood, not 6 independent pairwise checks).

**Decision:** `forwardCheck` runs a real AC-3 worklist: whenever assigning a face (or an
earlier propagation step) shrinks some neighbor's domain, every OTHER neighbor of that
shrunk face gets re-revised too, until nothing changes.

## 2026-09-03 — Flat displacement-distance cap replaced with an adaptive per-face geometric check

**Context:** A flat distance cap picked one number for the whole mesh, safe only because
it was small enough for the worst case seen so far. Self-intersection alone wasn't enough
either: a face can shrink one of its own edges toward zero length while staying perfectly
simple (two adjacent corners merging is not a crossing). Measured directly against the
real 48-biome catalog before this existed: a 30-seed sweep found realized edge lengths as
low as `7e-12` blocks (a literal point) and 95 real illegal biome adjacencies in
`RegionIntegrityValidator`'s own pixel-grid check — a buffer face squeezed thinner than
one macro cell contains zero cell centers, so the rasterizer's point-in-polygon test skips
it there and the two faces on either side end up pixel-adjacent with nothing sampled in
between.

**Decision:** Per-face, per-round geometric check instead: no face's own hexagon may
become self-intersecting, no single edge may collapse below `minEdgeLengthBlocks`, AND its
own realized area may never drop below `MIN_REALIZED_AREA_FRACTION_OF_TARGET` of its own
target — an easy, uncrowded face can move farther than a flat cap would ever allow, while
a genuinely tight face is held back exactly as far as its own real geometry requires.

**Evidence:** This can never happen at a single shared mesh vertex between two illegal-
to-touch biomes (hex vertex-degree-3 means any 2 faces meeting a third at one vertex are
ALSO mesh-adjacent to each other, so the CSP already forbids an illegal pair from ever
sharing one there) — it happens along a STRETCH of a legal buffer face's own boundary
instead, which no legality check (graph-level or self-intersection-level) was ever
positioned to catch. See also DECISIONS.md 2026-09-04 "MAX_VALIDATION_ROUNDS" entry for
the related fixed-point-loop bug this same per-face check surfaced.

## 2026-09-11 — `achievableAreaRangeFor` now consults committed VERTEX positions, not just widths

**Context:** Previously deliberately skipped committed vertex positions (using only
committed WIDTHS), reasoning a slightly-loose (too generous) estimate was safe because
"the real downstream clamps still apply when actual geometry gets computed later" - PROVEN
FALSE by direct measurement (`ModBiomeGraphTest.overworldLivePathRealizedWidthNeverBreachesItsOwnBiomesLegalSizeRange`):
a face fully encased by already-decided neighbors (every one of its own 6 corners already
locked before its own processing turn) has `original == candidate` at every corner in
`finalizedCornersOf` — the downstream clamp becomes a complete no-op, unable to enforce
even the biome's own absolute legal range.

**Decision:** Pass the SAME real, already-locked positions into `achievableAreaRangeFor`
so an encased face's estimate reflects its TRUE remaining freedom (often none) —
`TriangleIdentitySizeAssignment` can then correctly exclude a biome whose range can't
actually be met, failing loud at CSP time (a real, honest dead end) instead of silently
committing an out-of-range shape nothing downstream can still fix.

**Alternatives considered:** Keep width-only estimate, rely on downstream clamps —
rejected per the evidence above (the clamp is a no-op for encased faces).

## Undated (pre-2026-09-11) — `achievableAreaRangeFor` probes at real catalog-scale widths, not extremes

**Context:** An earlier version probed at an astronomically large weight (intended to
find the absolute physical ceiling) — but once a probe weight is large enough, it wins
the weight-DIFFERENCE comparison against ANY real, finite neighbor weight by a near-
identical margin, so the result collapses to the SAME value (the absolute displacement
cap) regardless of whether the real neighbors were tight or generous — silently
re-deriving a constant instead of reflecting real context.

**Decision:** Probe at REAL catalog-scale widths (`probeMinWidthBlocks`/
`probeMaxWidthBlocks` — the smallest/largest width across every biome the caller could
possibly assign) instead of arbitrary extremes.

**Evidence:** Found and fixed BEFORE this method was ever tested against real data.

## 2026-09-11 — `MAX_REALIZED_AREA_FRACTION_OF_TARGET` (1.3) added as the floor's symmetric ceiling

**Context:** Nothing stopped a face's own realized area from exceeding its target by 3x
or more, only the flat, target-INDEPENDENT `MAX_VERTEX_DISPLACEMENT_FRACTION` wall. Found
via the real live in-game path (`SizeFidelityLivePathMeasurement`): a face pinned tight on
ONE side by an already-committed neighbor (which correctly, provably, cannot be moved)
still gets its weight pushed up by calibration trying to reach its own target, and
balloons out on its OTHER, still-open sides well past what its target justifies.

**Decision:** `MAX_REALIZED_AREA_FRACTION_OF_TARGET = 1.3`, mirroring the floor's own
inversion-prevention logic flipped: `Math.max(target * 1.3, baselineArea)` so an
UNDERSIZED target's own ceiling can never fall BELOW baseline area, preserving the
already-established behavior that small-tier biomes commonly land near baseline size from
real surrounding competition.

**Evidence:** 1.3 is the starting value one of the three outside AIs consulted for this
investigation proposed (`doc/IDEAS.txt`, since removed as no longer relevant) — not
re-derived from scratch, tuned from real measurement if that ever helps.

## 2026-09-06 — `MAX_VERTEX_DISPLACEMENT_FRACTION` raise to 0.9 reverted, stays 0.8

**Context:** Tried raising 0.8→0.9. Alone it measured a real improvement (384→349
size-fidelity violations, no illegal-adjacency/starvation regression).

**Decision:** Reverted to 0.8 anyway.

**Alternatives considered:** Keep 0.9 — rejected: COMBINED with
`TriangleIdentitySizeAssignment.SIZE_PICK_BASELINE_BIAS_EXPONENT`'s own baseline-bias, it
breaks `TriangleFieldGeometryRealizationTest.clustersAgreeRegardlessOfWhichQueryPointIsSolvedFirst`
— a real order-dependence regression neither change causes alone, not yet investigated
further. Do not raise this constant again without re-testing that exact combination.

## 2026-09-04 — `MAX_VERTEX_DISPLACEMENT_FRACTION` (0.8) reinstated as a 4th, global condition

**Context:** The original design had this cap as its SOLE clamp, empirically swept to 0.8
as the largest value staying clean of real illegal adjacencies (1.0 measurably broke
correctness). When per-face simplicity/min-edge/min-area conditions were built, this
absolute cap was dropped entirely, on the assumption the new LOCAL checks would be enough.
They were not: a face can be perfectly simple, have every edge comfortably long, and have
plenty of area, while still having grown large enough to reach into a DISTANT, non-mesh-
adjacent face's own territory — none of the three local checks can see that, since none of
them looks at any OTHER face's geometry.

**Decision:** Reinstate `MAX_VERTEX_DISPLACEMENT_FRACTION = 0.8` as a 4th, GLOBAL
condition alongside the three local ones.

**Evidence:** This is very likely the real root cause of the face-polygon-overlap bug
tracked at `ModBiomeGraphTest.overworldHasAKnownFacePolygonOverlapBug` (worldSeed=2031) —
confirmed directly by re-adding this cap and re-testing that exact seed. 0.8 reused, not
re-derived, since the underlying question (how far can any vertex move before risking
territory it has no business reaching) is the same one already answered empirically.

## 2026-09-04 — `MIN_REALIZED_AREA_FRACTION_OF_TARGET` (0.7) added, then capped at baseline

**Context:** A face whose edges are all comfortably long and whose polygon is perfectly
simple can still land badly undersized overall — measured directly: a real 370-block-
target beach face realized only 260.8 blocks (ratio 0.70, roughly half the target area),
not a self-intersection or thin-edge problem, just the calibration loop's own known
residual.

**Decision:** Add a THIRD geometric validity condition: realized area must be ≥ 0.7x the
face's own target area (relative, not absolute, since one shared fraction fits both a
300- and 1000-block biome where one shared absolute number couldn't).

**Alternatives considered:** Apply the 0.7x floor raw — rejected, real bug found via a
pre-existing test breaking: for an OVERSIZED target, `0.7 x targetArea` can exceed the
area of the ORIGINAL undisplaced hexagon itself, breaking the binary search's "always
valid at t=0" foundation, and since area only grows as the search moves toward an
oversized target, an oversized face got ZERO growth once this went uncapped (the search
kept shrinking, which is right for simplicity/min-edge but only worsens an area
shortfall). Fixed by capping the floor at the baseline hexagon's own area.

## 2026-09-04 — `MAX_VALIDATION_ROUNDS` fixed-point loop replaces one independent pass

**Context:** Each face validates ITSELF assuming uniform scaling, but per-vertex minimum-
reconciliation across faces can leave different corners of the SAME face at different
final scales — a shape no single face's own check ever actually validated. Found as a
real bug, not a hypothetical: adding the minimum-edge-length check surfaced a real "5+
cells not covered by any face" failure in
`SeedRegionCacheTest.differentRegionsProduceIndependentResults` that plain simplicity
alone had apparently never triggered.

**Decision:** Re-validate and re-shrink to a real fixed point (`MAX_VALIDATION_ROUNDS =
20`) instead of trusting one independent pass — the same lesson as
`TriangleIdentitySizeAssignment`'s own binary-AC-3-wasn't-enough history, at the geometry
layer instead of the CSP layer.

## 2026-09-02 — Additive weight correction replaces a failed multiplicative attempt

**Context:** `radicalCenter`'s own formula depends on the DIFFERENCE between two weights
(`weightA - weightB`), never their ratio. An earlier version of `calibratedWeights` tried
a multiplicative update (`newWeight = oldWeight * ratio`) — confirmed directly via a
round-by-round trace: weight fell from 22500 to 39 over 15 rounds while realized area
stayed almost flat, because multiplying every face's weight by a similar ratio leaves
their PAIRWISE DIFFERENCES, and therefore the diagram itself, nearly unchanged.

**Decision:** `newWeight = oldWeight + DAMPING_FACTOR * (targetArea - realizedArea)` -
additive, matching what the geometry actually responds to. `DAMPING_FACTOR = 0.5` keeps
two mutually-adjacent faces from overcorrecting off each other's stale state and
oscillating, at the cost of needing more rounds to converge.

**Alternatives considered:** Multiplicative update — rejected per the evidence above.

## 2026-09-02 — Power-diagram vertex placement replaces per-edge additive bias

**Context:** The old per-edge bias mechanism (`FaceSizeFeasibility`, since renamed/
replaced because its whole mechanism changed, not just its tuning) had a real, structural
dead end whenever two adjacent faces wanted the same shrink/grow direction, since a
shared edge genuinely can't satisfy both sides of a single wall at once.

**Decision:** `WeightedVertexPlacement` computes a NEW position for every mesh vertex
directly from each face's own target size via a power diagram (weighted Voronoi diagram)
over the mesh's own face centers — the boundary between two faces is determined ENTIRELY
by comparing weight MAGNITUDES, so two same-tier neighbors with different chosen widths
still get a correctly proportioned boundary; the old formula's "reconciled delta is
exactly zero when both sides want the same direction" degeneracy doesn't exist here.

**Evidence:** Measured directly against the real 48-biome catalog: same-tier neighbors
are 51% of all real legal pairs, and small tier-1 biomes commonly landed 40-80%+
oversized under the old mechanism as a result.


## 2026-09-11 — Desert + deep-ocean family get `selfAdjacencyLimit=1`

**Context:** `SizeFidelityMeasurement` showed raising these 5 biomes' own target range
alone (700-1000 → 900-1500) did NOT fix their undersized realized/target ratio — a
neighbor-competition problem, not a magnitude one. Each hex still measures the same ratio
regardless of its own declared target; the real fix is letting two adjacent same-biome
hexes together cover roughly double the ground.

**Decision:** `DEEP_COLD_OCEAN`, `DEEP_FROZEN_OCEAN`, `DEEP_LUKEWARM_OCEAN`, `DEEP_OCEAN`,
`DESERT` get `selfAdjacencyLimit=1` — each spans up to 2 ordinary hexes as one felt region.

**Alternatives considered:**
- Stretch a single hex's own geometry further instead — rejected, not detailed further in
  the original comment beyond "instead of."
- A "self-adjacency GROUP" extension (deep-ocean variants self-adjacent to EACH OTHER, not
  just to themselves) — deliberately out of scope for this change, left for later.

**Evidence:** See `SizeFidelityMeasurement`'s own real before/after numbers.

## 2026-08-30 — `FROZEN_OCEAN` gains 4 new legal neighbors to fix real starvation

**Context:** `SNOWY_BEACH` sat at degree 3 (the bare structural minimum) and was measured
genuinely starved — zero cells across 10 independent real hex-mesh solves
(`ModBiomeGraphTest.overworldNoBiomeIsStarvedAcrossManyIndependentRealSolves`), the same
failure pattern this graph's own history already saw for `badlands` at degree 1.

**Decision:** Add `AdjacencyRule.allow(FROZEN_OCEAN, SNOWY_BEACH/SNOWY_PLAINS/SNOWY_SLOPES/SNOWY_TAIGA, ...)`
— `FROZEN_OCEAN` and `ICE_SPIKES` were both thematically-adjacent snow/coast biomes
`SNOWY_BEACH` wasn't yet connected to.

**Evidence:** Real 10-independent-solve starvation measurement, cited above.

## 2026-08-23 — `OCEAN`-`SAVANNA` rule closes the last real gap in `ModBiomeGraph.DEFAULT`

**Context:** Real production crash (worldSeed=42) against the geometry-realization
rasterizer of the time (since deleted and replaced by the current hex +
`EdgeDisplacementRasterizer` pipeline). `BADLANDS` and `OCEAN` are legal to EACH OTHER but
shared no THIRD common neighbor at all — badlands' other neighbors are desert/savanna;
ocean's other neighbors are forest/plains, disjoint sets — so any cell whose neighbors
included both badlands and ocean plus two more biomes had no legal placement.

**Decision:** `AdjacencyRule.allow(OCEAN, SAVANNA, ...)`.

**Alternatives considered:**
- `ocean`-`desert` — only closed 3 of 4 gaps.
- `badlands`-`forest` — only closed 3 of 4 gaps, a DIFFERENT one left over.
- `ocean`-`savanna` chosen because it's the only single addition that closes all of them,
  and coastal savanna is a normal, common real-world pairing.

**Evidence:** Verified exhaustively — not just this one pair, every combination up to 6
(the maximum possible, since this graph only has 6 biomes) —
`BiomeGraphTest.modBiomeGraphDefaultPassesItsOwnValidationBound` directly proves zero
unsolvable combinations exist at ANY size, not just reduces how often one is hit.

Historical/rationale narrative for worldgen-core, moved out of inline comments per the
`decision-log` skill. Newest entries first. An entry is a claim about the past, not a
guarantee about the present — verify against current code before relying on one to block
a new proposal.

## 2026-09-11 — Partial-neighborhood completeness check restores coverage the ring check dropped

**Context:** `BiomeGraph.requireEveryLocalNeighborhoodSolvable`'s ring restriction (see
2026-08-23 entry below) correctly handles a face's fully-closed 6-neighbor ring, but a
real production crash (worldSeed=2028) showed a PARTIAL neighborhood — 3 of a face's
neighbors already decided, the rest still free — needs no such ring closure at all.
`stony_peaks`/`snowy_beach`/`cold_ocean` were real, simultaneously-fixed neighbors of one
face despite `stony_peaks` not even being legal to `snowy_beach` directly (they were
non-consecutive around the real hex, so shared no mesh vertex and needed no mutual
legality) — and no single biome was legal to all three at once.

**Decision:** `BiomeGraph.requireEveryPartialNeighborhoodSolvable`/
`findUnsolvablePartialNeighborhoods` — checks every unordered subset of size
`2..maxNeighbors-1`, deliberately WITHOUT requiring members to be mutually legal to each
other (the real distinction from the ring check). Later refined with
`isPlaceableAmongNeighbors` (a real geometric filter: a subset of size 4-5 is only a real
conflict if it can actually be arranged among the hex's 6 ring slots without forcing an
illegal pair into touching positions — checked by brute-force minimum path cover over all
orderings, since subset size is always ≤5).

**Alternatives considered:**
- Extending `requireEveryLocalNeighborhoodSolvable` itself with a size parameter below
  `maxNeighbors` — rejected as a separate method instead, since the ring restriction and
  the "no mutual legality needed" property are opposite requirements, not a shared
  parameter.

**Evidence:** Raw combinatorial check found 1,815,474 of 1,927,879 subsets (sizes 2-5)
unsolvable against the real 48-biome `OVERWORLD` — reduced to 3,208 truly MINIMAL
conflicts (`findMinimalUnsolvablePartialNeighborhoods`, since any superset of an already-
unsolvable subset is trivially also unsolvable and therefore redundant noise), then to
3,118 after the geometric placeability filter removed 90 combinations (73 quads, 17
quints) that could never physically co-occur as neighbors in the first place. Breakdown:
288 pure pairs, 1,968 triples, 628 quads, 234 quints — most conflicts (91%) are genuine
3-way+ gaps, not reducible to any single incompatible pair. Deliberately NOT part of
`forProduction`'s automatic bundle at this scale (same reasoning as the ring check below)
— stays a non-blocking diagnostic, cached against the graph's own rule fingerprint in
`doc/biome-graph-completeness-cache.txt` (regenerate via
`./gradlew :worldgen-core:biomeGraphCompletenessReport`).

One concrete conflict (`stony_peaks`/`snowy_beach`/`cold_ocean`) was fixed with
`AdjacencyRule.allow(SNOWY_BEACH, STONY_SHORE, ...)`, verified working, then deliberately
REVERTED per explicit user instruction: adjacency rules are authored only through the
user's own Excel planner, never directly by Claude. `ModBiomeGraphTest` tracks this as a
known-open gap (`overworldHasAKnownStonyPeaksSnowyBeachColdOceanPartialNeighborhoodGap`),
same convention as `overworldHasTwoKnownCrossTierGaps` below.

## 2026-08-23 — Local-neighborhood completeness check restricted to realizable rings

**Context:** An earlier version of `requireEveryLocalNeighborhoodSolvable` tested every
unordered SUBSET of `maxNeighbors` biomes, including arrangements no real mesh could ever
produce. Traced directly: the first subset it tried alphabetically on the real 48-biome
graph included two biomes with no rule between them at all — an arrangement no hex face's
fully-closed ring could ever actually have, yet the old check demanded some biome be legal
against it anyway (a real false positive).

**Decision:** Restrict the check to REALIZABLE rings only — cycles of `maxNeighbors`
biomes where every CONSECUTIVE pair (wrapping) is itself a legal pair, matching how a real
hex mesh face's neighbors actually relate (vertex-degree-3 means consecutive neighbors
always share an edge with each other too, non-consecutive ones don't).

**Alternatives considered:**
- Keep testing all subsets — rejected: over-tests arrangements no hex face could ever
  physically have as a complete, closed ring.

**Evidence:** Measured directly against the real 48-biome `ModBiomeGraph.OVERWORLD`:
201,552 realizable 6-rings exist, and 47,623 of them (~24%) have no universal candidate —
not one gap to patch, a structural certainty for any thematically narrow graph at this
scale (no realistic catalog has a biome legal against an arbitrary ring of 6 unrelated
others). Demanding zero as a hard gate would make any well-designed real catalog
un-buildable, so this check stays an explicit, non-blocking diagnostic, never part of
`forProduction`'s automatic bundle. Real production validation instead runs actual
generation (`ModBiomeGraphTest`'s own real-solve tests, many seeds) rather than reasoning
about the graph in the abstract.

## 2026-08-07 — Universal-filler concept removed; termination guarantee moves to the CSP's own fail-loud path

**Context:** The graph used to designate one biome (plains) as a permanently-unrestrictable
"universal filler" — always legal next to everything, guaranteeing backtracking always had
a legal fallback. That guarantee was purchased by forbidding plains from ever getting real
adjacency rules, while every other biome could be restricted freely.

**Decision:** Remove the universal-filler concept entirely. Every biome, including plains,
is now a normal, restrictable node. A cell that genuinely has no legal biome given its
neighbors is treated as a real graph misconfiguration and fails loud (via whichever CSP is
live at the time's own `orElseThrow`), rather than this class silently guaranteeing that
can never happen.

**Alternatives considered:**
- Keep a universal filler — rejected: forces one biome to stay permanently unrestrictable
  forever, a real design constraint with no expiry, just to buy a guarantee the CSP can
  provide honestly instead (fail loud on a genuine dead end, proven unreachable offline for
  a well-formed graph via `requireEveryLocalNeighborhoodSolvable`).

## (undated, pre-2026-08) — `MIN_LEGAL_NEIGHBORS` floor set to 3

**Context:** A biome with too few legal neighbors gets starved by
`TriangleIdentitySizeAssignment`'s own fairness-quota candidate ordering — fairness only
reorders candidates already legal for a face, it can't invent legality a narrow biome
doesn't have. `badlands` at degree 1 drove the very first triangle-count skew found in
this graph's own history.

**Decision:** `MIN_LEGAL_NEIGHBORS = 3`, enforced via `requireMinLegalNeighbors` — opt-in
(called by `forProduction`), not constructor-enforced, since roughly half the test suite
deliberately builds narrow chain/bridge/hub graphs (degree 1-2) to isolate one specific CSP
behavior in total isolation.

**Alternatives considered:**
- Enforce in the constructor directly — rejected: would break every deliberately-narrow
  test fixture; real graphs go through `forProduction` instead, which does enforce it.

**Evidence:** 3 is a floor, not a guarantee of fair representation — just the minimum
needed for a biome to plausibly show up across a real mesh at all. Starvation isn't a pure
graph-structure property checkable offline (it depends on real mesh placement, not just
the rules), so this floor is a cheap, unproven-by-itself heuristic — the real,
re-runnable proof that `ModBiomeGraph.DEFAULT` doesn't starve any of its biomes lives in
`ModBiomeGraphTest.noBiomeIsStarvedOnARealProductionScaleSolve`, which runs the actual CSP
against a real mesh instead of reasoning about it in the abstract.

## (undated) — Cross-tier-neighbor check found two real gaps, deliberately not wired into `forProduction`

**Context:** Under the OLD per-edge additive size-bias mechanism (`FaceSizeFeasibility`,
since replaced), two same-tier neighbors produced EXACTLY zero size correction — a provable
dead end. `requireEveryBiomeHasACrossTierNeighbor` was added to catch any biome whose every
legal neighbor shares its own exact `BiomeSizeRange`.

**Decision:** Keep the check (it still correlates with measurably worse size fidelity even
under the new power-diagram mechanism), but do NOT wire it into `forProduction` — the real
catalog fails it today (`deep_lukewarm_ocean` and `mangrove_swamp` both have only same-tier
legal neighbors), and closing it is a game-design call (which differently-sized biome each
should legally border), not this method's job to make.

**Alternatives considered:** none recorded — the check's existence itself was the fix; no
alternative formulation was tried.

**Evidence:** Post-redesign (power-diagram `WeightedVertexPlacement`, which differentiates
by weight MAGNITUDE rather than "which direction do we each want", so the old mechanism's
hard zero no longer provably applies), `deep_lukewarm_ocean` still averaged ~35% undersized
across a real 10-seed sample — the underlying signal remains real and useful even though
the exact "can never reach target, in any mesh" framing is now an overstatement.
