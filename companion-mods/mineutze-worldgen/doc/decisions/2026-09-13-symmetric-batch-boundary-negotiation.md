# 2026-09-13 — Symmetric batch-boundary negotiation (no new grid, no new shape — organic batches stay organic, only WHO decides first changes)

**Superseded 2026-09-20: this whole live hex-mesh/CSP system was deleted and replaced by a
per-world solved plan; see DECISIONS.md's 2026-09-20 entry. Kept as history, describes deleted code.**

Status: **Phase 1 (isolated seam prototype) VERIFIED** - `SeamResolution`/
`SeamResolutionTest` (commit `15d04460`) directly confirmed identity+size+
geometry are byte-identical regardless of seam-resolution order, against
the real 48-biome catalog. **Phase 2 (live persistence mechanism) design
LOCKED, THREE implementation attempts now built, measured, and REVERTED**
(see "ATTEMPT 1"/"ATTEMPT 2"/"ATTEMPT 3 BUILT AND MEASURED" entries
below). Attempt 3 found and fixed two real, separate bugs along the way
(a dropped upfront-neighbor-seeding step causing a real "query point not
covered" crash; an orphaning bug in the bound itself) and DID fix
Attempt 2's `SeedMeshCacheTest` violation cleanly - but the core "letting
tentative renegotiate at all can turn a solvable neighborhood into a real
CSP dead end" problem survived even at the tightest possible bound (1
ring), confirmed not a tuning problem. **Real next step identified, not
yet built**: make the bounded-seam attempt a first try with a fallback to
today's exact, always-solvable "treat tentative as fixed" behavior on CSP
failure - can never regress below today's baseline solvability by
construction, still gains the real benefit whenever the bounded attempt
succeeds. **Phase 3 (canonical batch-extent boundary) remains a separate,
real, confirmed-by-measurement follow-up, NOT yet designed** - see its own
section at the end. Branch
`worldgen/symmetric-batch-boundary-solve`, forked from the
`worldgen/mesh-geometry-realization` checkpoint commit (`6a10972d`).
Supersedes nothing in `2026-08-29-remove-region-stitching.md` — that doc's
diagnosis was correct and this design keeps its actual fix (see "What this
keeps" below). It extends that doc's own unfinished half.

## Why this exists

User's own framing, verbatim, is the cleanest statement of the problem this
whole project keeps re-discovering symptoms of: *"we are always relying on
geometry that we could perfectly solve 100% of the time if we could solve it
all at once, but since minecraft worlds are infinite, we never have fully
realized geometry."*

**Factual correction, checked directly (matters for how hard to treat this
constraint)**: a Minecraft world is NOT mathematically infinite - it has a
hard world border, default 29,999,984 blocks from spawn per axis (a
~60-million-block-wide square), placed just inside where 32-bit block/chunk
coordinate math would otherwise overflow. But at `HEX_SIZE_BLOCKS=325`
(hexagon area ≈274,422 blocks²), the full bordered area holds **≈13.1
billion hex faces** (computed directly: world area ≈3.6×10^15 blocks²
divided by one hex face's own area). Even at an optimistic 1,000,000
faces/second (far faster than anything ever measured in this codebase - a
real solve is CSP backtracking + AC-3/ternary propagation + a 30-iteration
geometry bisection per face, not O(1)), that's ~3.6 hours minimum; at this
codebase's own actually-measured throughput (tens of thousands of
faces/second for a real CSP+geometry solve), realistically 2-3 DAYS. Bare
face data alone (100 bytes/face, before vertices, adjacency, or CSP domain
state) needs over 1.3 TB of RAM held simultaneously. So even granting a
generous time budget ("a few minutes is fine if needed"), a true global
solve is off by several orders of magnitude on BOTH time and memory - not a
"let it run a while" problem, a genuinely different scale of problem. The
rest of this doc treats "can't ever solve it all at once" as effectively
true, which it is in practice, without overclaiming it's true by
definition.

That's not a new problem this doc introduces — it's the ROOT of every
symptom chased this whole size-fidelity arc, and this project already named
it once before without fully fixing it.

## The disease was already correctly diagnosed once (2026-08-29)

That doc identified TWO separate problems with the old fixed-`RegionPos`
design:

1. **Solve-order asymmetry** — a region solved first has zero knowledge of
   what a not-yet-solved neighbor will place next to it; once solved, that
   neighbor gets ZERO say, just inherits it as fixed. Named as "a real,
   already-documented source of bugs" even then (an illegal adjacency a 3×3
   batch couldn't see; an undersized patch `PatchSizeEnforcer` couldn't fix
   because its true neighbors hadn't solved yet).
2. **Regions had a fixed, pre-imposed SQUARE shape**, cutting a biome's
   natural boundary at an arbitrary grid line unrelated to its own geometry.

**The fix that shipped (`realizeCluster`'s organic BFS cluster growth) only
solved problem #2.** Problem #1 was explicitly named as real and was NOT
fixed — it was moved to a finer grain (per-organically-grown-cluster instead
of per-region) and left there. This session's whole investigation — the
"tentative corner revision" mechanism (134 real revisions/10 trials, traced
directly), the order-dependence bug (99.07%/20,000 trials disagree on
forward vs. reverse exploration order), and the self-adjacent-pair size gap
that a protected-neighbor clamp only partially closed — are all the SAME
root cause (#1, still unfixed) surfacing in different places. Every
incremental fix this arc has narrowed the symptom without touching the
cause.

## What this keeps (do not regress)

- **Shapeless, organically-grown batches.** No fixed grid dictates biome
  shape. A batch is still "whatever `realizeCluster`'s BFS actually grows
  from a query point," not a pre-drawn square. This is the real, correct
  part of the 2026-08-29 fix — reintroducing a hard geometric partition
  would very likely resurrect the exact straight-seam bug that fix removed.
- **Hex vertex-degree-3 as the mechanism that makes CSP-time legality
  provably complete.** The AC-3/ternary-propagation/backtracking ALGORITHM
  itself is untouched — see "Identity needs the same treatment as size"
  below for why WHERE/WHEN it gets invoked does change.

**Correction from an earlier draft of this doc, caught on review**: that
draft claimed identity/adjacency legality was entirely untouched by this
design. That was wrong, and internally inconsistent with this doc's own
motivation — the order-dependence bug this doc opens with ("same seed,
different PERMANENT biome") is an IDENTITY disagreement, not a geometry
one. A design that only symmetrizes geometry/weight resolution (the
original Candidate C) would leave that exact bug unfixed. See below.

## What this changes

**Core idea: separate "what shape gets solved" (stays organic, per above)
from "who gets to decide first" (currently query-arrival-order, the actual
disease).** Two things currently conflated need to split apart:

1. **No new grid at all — reuse the mesh's OWN existing canonical hex
   lattice as the negotiation/cache key.** An earlier draft of this section
   proposed inventing a separate, coarser "canonical grid" purely as a
   cache index (explicitly NOT a shape constraint) — but that still isn't
   needed: `HexGrid.nearestAxialPos` already gives every point a
   deterministic, worldSeed-independent, same-every-time hex face identity.
   That IS the nameable unit 2026-08-29's own "what regions actually earn
   their keep for" section asked for (`FaceFairnessQuota`'s finite area,
   the cache/lock layer's key) — no second, invented grid required, no
   corner-of-a-square-touches-4-things geometry to design around (see the
   corner-case note below — it dissolves once there's no separate grid).
   **This has nothing to do with biome SHAPE, at all, in either version** —
   shape is still purely the organic BFS cluster + weighted-Voronoi +
   edge-displacement pipeline, completely unchanged either way. This item
   is only about which hex face "owns" triggering a negotiation and how a
   negotiation gets looked up in a cache — invisible bookkeeping, never
   drawn, never felt by a player.
2. **Symmetric boundary resolution** instead of first-past-post ownership.
   When an organically-grown batch's frontier reaches a face that's
   genuinely undecided (not permanent, not even tentative — the actual
   asymmetry trigger), the current rule is "pull it into my own free set,
   solve it purely for my own batch's targets, whoever else touches it later
   just inherits my answer forever." The fix needs that shared boundary
   resolved via a rule that gives the SAME answer regardless of which side's
   query happened to run first — this project already built exactly this
   pattern this session, one level down: `SequentialVertexPlacement
   .resolvePairJointly` resolves a self-adjacent PAIR's shared weight via
   one joint search instead of two independent, order-sensitive ones. The
   proposal below generalizes that same shape from "two faces" to "two
   adjacent batches."

## Identity needs the same treatment as size — they're not two separate problems

User's own instinct, directly on point: this algorithm has exactly two
coupled constraints (legal identity/adjacency, legal size), and they need
resolving TOGETHER, not as two independently-symmetrized layers. This is
already how a SINGLE cell works today — `TriangleIdentitySizeAssignment` is
a FUSED identity+size CSP, not two passes — the gap is only ever at the
SEAM between two cells, and it applies to both halves of the fusion, not
just size.

**Mechanism: extend the fused CSP to the seam between two organically-grown
BATCHES, not just each batch's own interior. No coarser grid, no new shape
— see the "no new grid" correction above; "cell" below just means "a hex
face already canonically identified by `HexGrid.nearestAxialPos`," nothing
new.**

1. A batch's own INTERIOR faces (every mesh-adjacent neighbor already
   known, either from this same batch or already-permanent elsewhere) solve
   and commit PERMANENTLY the moment the batch resolves, fused
   identity+size, same as `TriangleFieldGeometryRealization.realize`
   already does for a whole region today. No order-sensitivity here: an
   interior face's true context is already 100% locally available the
   moment it's reached, regardless of when that happens.
2. A batch's own BOUNDARY faces (touching a still-genuinely-undecided
   neighbor — today's actual asymmetry trigger) stay TENTATIVE — solved
   once for a usable preview (needed if a real query lands there before the
   other side is ever touched, see point 4), but not committed
   permanently. This is the SAME "commit once, forever, only once all real
   owners are known" rule the mesh already uses at the FACE level
   (`commitClusterVerticesTentatively`) — this doc just proposes applying
   it to FACE IDENTITY too, not only vertex position.
3. The moment a LATER batch's own growth reaches one of those still-open
   boundary faces, the shared strip (every face on either side still
   tentative) is resolved as ONE fused identity+size CSP call — same
   algorithm `TriangleIdentitySizeAssignment.solve` already runs, same
   AC-3/ternary propagation, just invoked over the SEAM's own free-face set
   with BOTH sides' already-PERMANENT interiors as real fixed context
   (never a stale tentative guess — a tentative face is never treated as
   fixed by anything, exactly like today's rule already works). This is
   why the algorithm itself doesn't change (matches "what this keeps"
   above) — only the SCOPE/TIMING of one more call to it.
4. **Structurally symmetric, not just intended to be**: whichever batch's
   query happens to trigger step 3, the CSP's own inputs are identical
   either way — both interiors are already permanent regardless of visit
   order, the seam's free set is the same set regardless of which side
   "noticed" second, and the CSP's own candidate-ordering hash is
   worldSeed-derived, not visit-order-derived. Same inputs, same
   deterministic algorithm, same output - order-independence should hold
   BY CONSTRUCTION here, not by hoping the numbers land close. **Checked
   directly, not just assumed**: `FaceFairnessQuota` (the CSP's own
   quota-deficit tie-break) was a real suspicion — it sounds like exactly
   the kind of run-history state that would break this. Read the code
   (`FaceFairnessQuota.forRegion`, called fresh inside every single
   `TriangleIdentitySizeAssignment.solve` call): it constructs a brand-new
   instance every call, `counts` starts empty every time, nothing persists
   across separate `solve()` calls. It's scoped entirely to ONE call's own
   free-face set — not a real risk to order-independence after all, ruled
   out by reading the actual code rather than left as a guess.
5. **The honest, irreducible residual case, not glossed over**: if a real
   query needs an answer from a boundary face BEFORE the other side is
   ever touched (likely the common case — most world traversal probably
   doesn't hit both sides of most seams), the tentative single-sided guess
   from step 2 gets promoted PERMANENT the instant Minecraft actually uses
   it — same `promoteFaceToPermanent` mechanism already in the codebase
   today, unmodified. If the other side later does get touched, the seam
   CSP in step 3 no longer has freedom over that specific face (it's real,
   permanent now) — negotiation happens over whatever of the seam is STILL
   open. This isn't a bug this design fails to close; it's the genuine,
   mathematically irreducible cost of any online algorithm with a real
   caller that can't wait — the same "an online algorithm is provably
   worse than an offline one" fact already named earlier in this
   conversation, now scoped down to its true minimal footprint (one forced
   face) instead of spread across the whole mesh.

**A real, deliberate reopening of a prior explicit decision, flagged
plainly, not snuck in**: bringing back a fused CSP+geometry joint solve for
a batch's own interior (step 1) is bounded, cheap, and safe — but note it's
still MORE joint negotiation than the live path runs today anywhere
(`SequentialVertexPlacement`, zero simultaneous negotiation, is the current
PERMANENT live mechanism per the 2026-09-11 "remove the tug" decision). This
design reintroduces joint calibration, just bounded to one batch's interior
plus, now, one seam strip at a time — worth a direct, explicit "does this
supersede the no-tug-of-war principle, or just add one narrow, bounded
exception to it" confirmation, not an assumption either way.

## Phase 2, LOCKED (2026-09-13) — the persistence mechanism `DecidedFaceStore`/`DecidedVertexStore` need for the live path

Concrete answer to "how does step 2 above ('boundary faces stay tentative')
actually work against real, persisted state" — not a new third tier, a
SECOND promotion trigger on the two tiers that already exist.

1. **New auto-promotion rule.** Today a face/vertex only ever promotes to
   PERMANENT when it's the literal target of a real query
   (`promoteFaceToPermanent`, unmodified, stays exactly as-is for that
   case). New, ADDITIONAL trigger: after any cluster solve, for each of
   that cluster's own newly-decided faces, check whether every one of its
   mesh-neighbors ALSO already has a real PERMANENT decision. If so,
   promote it too, immediately - cascading, since promoting one face can
   make an adjacent one newly-eligible (same fixed-point shape
   `promoteVertexIfAllOwnersPermanent` already uses one level down for
   vertices, generalized to faces here). Safe by construction: a face
   whose ENTIRE local neighborhood is already known has, by definition, no
   remaining seam exposure - nothing can ever legitimately need to revise
   it again, so locking it early costs nothing and closes the window
   before anything CAN revise it.
2. **`growCluster`'s stop rule changes.** PERMANENT stays a true hard wall,
   completely unchanged. TENTATIVE (not permanent) stops being an
   unconditional wall - it becomes the seam trigger: pull it into THIS
   solve's own free set and resolve jointly (today's rare, CSP-failure-only
   `treatTentativeAsFixed=false` fallback becomes the NORMAL path, not an
   edge case).
3. **Why this doesn't reopen the ALREADY-REJECTED Option 2** ("tentative
   geometry revision", 317/448, worse - see project memory 2026-09-11).
   Option 2's real flaw: a tentative face could be revised an UNBOUNDED
   number of times, by any later touching query, forever, with nothing
   ever closing that window - real, measured thrashing. THIS design closes
   the window the moment a face's neighborhood first becomes fully known
   (rule 1) - a face can be pulled into a joint seam resolve AT MOST ONCE
   (the one event that finally completes its neighborhood), never
   indefinitely. Same underlying idea Option 2 tried, with the one missing
   piece (a real closing/locking rule) that made Option 2 unsafe now
   present.
4. **What does NOT change**: `DecidedFaceStore`/`DecidedVertexStore`'s own
   public shape (`get`/`getPermanent`/`commitTentative`/`commitPermanent`)
   - `commitPermanent` already accepts an arbitrary map of newly-permanent
   entries and already fails loud on a real disagreement, exactly what the
   new auto-promotion rule needs, unmodified. The only new code is WHERE/
   WHEN `commitPermanent` gets called from (a new post-solve sweep in
   `TriangleFieldGeometryRealization`, checking every newly-decided face's
   own neighbor-completeness), not a store-schema change.

### ATTEMPT 1 (2026-09-13) — "neighbor must be PERMANENT" — TRIED, REGRESSED, REVERTED

Implemented item 1's promotion rule literally as written: a face promotes
once every mesh-neighbor is already PERMANENT. Compiled clean. **Real
regression on the very first test run** - `clustersAgreeRegardlessOfWhichQueryPointIsSolvedFirst`
and `permanentResultsAgreeRegardlessOfExplorationOrderEvenWithTentativeGeometryRevision`
(the exact 2 tests this whole effort targets) went from "wrong answer" to a
NEW `IllegalStateException` dead end - worse, not better.

**Root cause, found by direct reasoning**: this criterion is UNSOUND. On a
freshly-solved cluster (the common case - nothing nearby was ever permanent
before), almost NOTHING satisfies "neighbor is permanent" except possibly
the one literal query-covering face - so a fresh cluster stays ~100%
tentative after its own solve, contrary to this section's own item 1
("interior commits PERMANENT immediately"). Consequence: a SECOND, nearby
query's `growCluster` finds almost none of the first query's territory
permanent, so (correctly, per item 2's new rule) it pulls the FIRST
QUERY'S ENTIRE REACHABLE CLUSTER back into one giant joint free-set instead
of a thin seam - not what was intended. For the test's own deliberately
narrow, tightly-tuned 4-biome graph fixture, re-solving that much bigger,
more constrained combined problem genuinely has no solution.

**The other obvious fix, considered and rejected on paper, not even
built**: "neighbor merely has ANY decision" (tentative counts too) is
ALSO wrong, in the opposite direction - since a fresh cluster's own
boundary is trivially ALWAYS permanent-or-hull by `growCluster`'s own
definition, this would promote an ENTIRE cluster to permanent the instant
it's solved, every time - which achieves nothing new at all: it's
functionally identical to today's existing `treatTentativeAsFixed=true`
asymmetric behavior (whichever query runs first locks everything,
instantly, unconditionally), just relabeled. Reverted (`git checkout --`
on `TriangleFieldGeometryRealization.java`), confirmed back to the known
2-failure baseline.

**Real, important caveat found by tracing the FAILING TESTS' OWN geometry,
not assumed**: `clustersAgreeRegardlessOfWhichQueryPointIsSolvedFirst`
queries two points 900 blocks apart against `CLUSTER_GROWTH_CAP_RADIUS_BLOCKS
=2500`. A disk of radius 2500 centered at EITHER point comfortably contains
the OTHER point - meaning whichever query runs first doesn't grow a
"cluster that meets a seam," it grows ONE cluster that already covers BOTH
points entirely, unconstrained, on totally fresh territory. This is NOT a
seam-asymmetry case at all - it's a `Phase 3` case (the query-point-
anchored disk shape itself, not who negotiates a boundary). **Neither this
failed attempt NOR a correctly-built Candidate A can be expected to fully
fix these 2 specific tests** - their own scenario is dominated by Phase 3,
a separate, not-yet-designed problem. Worth remembering before declaring
any Phase-2-only implementation "done" against these two tests specifically
- they may need Phase 3 too, not just a better Phase 2.

### Candidate A - chosen as the real eligibility criterion, replacing "neighbor tier" entirely

**Wider solve, narrower commit.** Grow the batch one ring larger than
what it actually commits; only the CORE ever becomes tentative/permanent,
the outer ring's decisions are used for accuracy context and then
discarded, left fully open for whichever batch later claims it as core.
Cheap, no cross-batch coordination needed, compatible with today's
architecture almost as-is. Real cost: a boundary-adjacent face may get
solved repeatedly by different overlapping queries before one finally
commits it — wasted work, not wasted correctness. Does NOT achieve true
order-independence, only shrinks how much of a batch's own edge is exposed
to the asymmetry (the new edge, one ring out, still has it). Weakest fix,
cheapest to try first as a real measurement of how much of the gap is
sheer boundary exposure vs. something deeper.

### ATTEMPT 2 (2026-09-13) — Candidate A ("core = doesn't touch this call's own mesh hull") — TRIED, PARTIALLY WORKED, STILL REGRESSED ELSEWHERE, REVERTED

Implemented Candidate A as a one-shot topological split (no cascading BFS
needed, unlike Attempt 1): after every solve, `coreFacesOf` marks any
cluster face with zero hull-touching neighbors as CORE (promotes
immediately via the existing `promoteFaceToPermanent`, vertex cascading
included for free); everything else (touching this call's own mesh edge)
stays EDGE/tentative. Reasoned through carefully before building - a CORE
face's IDENTITY/WIDTH never needs revision once legal (legality, once
true, stays true regardless of what's decided elsewhere); a shared CORNER
touching a still-open EDGE face correctly stays un-promoted until ALL 3 of
its own owners are permanent too, per `DecidedVertexStore`'s ALREADY-
EXISTING rule - so geometry is never prematurely locked even where
identity is. This reasoning held up: no NEW dead end on the narrow 4-biome
test's OWN targeted assertion pattern.

**Real, mixed result - checked the FULL suite, not just the 2 targeted
tests, and a real second regression surfaced:**
- `permanentResultsAgreeRegardlessOfExplorationOrderEvenWithTentativeGeometryRevision`
  genuinely improved - back to its ORIGINAL non-crash "wrong answer"
  failure mode (no longer a NEW dead end, matching Attempt 1's own
  regression being fixed here).
- `clustersAgreeRegardlessOfWhichQueryPointIsSolvedFirst` STILL threw the
  SAME dead end as Attempt 1 - confirms the Phase-3 caveat above by direct
  measurement, not just prediction: this test's own 900-blocks-vs-2500-cap
  geometry is dominated by the disk-anchor problem, not fixable by a
  better Phase 2 alone.
- **NEW, real regression against the REAL 48-biome catalog** (not just the
  narrow synthetic fixture): `ModBiomeGraphTest.overworldLivePathRealizedWidthNeverBreachesItsOwnBiomesLegalSizeRange`
  (already a known-red test) changed from its own established assertion
  failure to a NEW `IllegalStateException` dead end - a real regression on
  production-representative data, not explainable away as "test fixture
  too narrow."
- **NEW, real regression against an EXISTING, deliberate design
  invariant**: `SeedMeshCacheTest.queryingOneCellLeavesMostOfItsOwnClusterMerelyTentative`
  explicitly asserts "most of a fresh cluster stays tentative after only
  one cell was actually queried" (`tentativeCount() > permanentCount()`) -
  a direct encoding of `DecidedFaceStore`'s own original design intent
  ("every OTHER face... stays tentative" - only the literal query target
  ever promotes). Candidate A's whole mechanism deliberately promotes the
  CORE of a fresh cluster immediately - a genuine, large-scale
  architectural contradiction of this existing invariant, not a small
  side effect (measured: tentative=28, permanent=31 - MORE permanent than
  tentative on a single-cell query).

**Reverted** (`git checkout --`, confirmed back to the known 5-failure
full-suite baseline).

**Real, load-bearing finding across BOTH attempts, worth stating plainly**:
the common piece in both - `growCluster` ALWAYS pulling every reachable
TENTATIVE neighbor into the free set, unboundedly, following the flood-
fill however deep it goes - is very possibly too aggressive regardless of
which promotion/eligibility criterion sits downstream of it. Both attempts
changed WHAT gets promoted after the fact; neither bounded HOW MUCH
territory gets pulled INTO one joint solve in the first place. A future
attempt likely needs to bound the INPUT side too (e.g., only pull in
tentative faces within some small ring of the NEW query's own growth,
not unboundedly deep into old, already-tentatively-decided territory) -
not yet designed. Two real implementation attempts now measured worse in
some real way; a third blind attempt at the same shape isn't recommended
without first designing this input-side bound.

### Attempt 3 design (2026-09-13) — bound the INPUT, not the output; needs no promotion-tier change at all

**Core idea**: neither prior attempt bounded how far `growCluster`'s flood-
fill could travel THROUGH existing tentative territory - both let it run
until it hit PERMANENT or hull, however deep that turned out to be. Bound
that directly: cap how many CONSECUTIVE hops through tentative faces one
BFS path may take before treating further tentative faces as FIXED
(exactly like PERMANENT) instead of pulling them in - reset the counter
to zero every time the path passes through genuinely FRESH (undecided)
territory or starts at the seed. Growth through fresh territory itself
stays completely unbounded, unchanged (today's real, correct "first-touch
solve legitimately uses the whole cap radius" behavior).

**Mechanism**: `growCluster`'s frontier carries one extra int per entry -
`tentativeStreak`, the number of consecutive tentative-only hops taken to
reach this face along the path that first discovered it. When a face is
polled: PERMANENT is still the only true, streak-independent wall
(unchanged). Hull-touching still always stops growth (unchanged). A
TENTATIVE face is pulled into the free set (as today's fix intends) ONLY
if `tentativeStreak < MAX_TENTATIVE_SEAM_DEPTH`; the face it's expanded
to gets `tentativeStreak + 1`. Once the bound is reached, a tentative face
is instead added to `fixedContext` (its CURRENT value used as-is, same as
a permanent wall) and growth does NOT continue past it - the flood-fill
simply cannot travel more than `MAX_TENTATIVE_SEAM_DEPTH` rings deep into
any single stretch of pre-existing tentative territory, regardless of how
large that territory actually is. A face reached through FRESH territory
resets the streak to 0 for its own children, so a path that leaves
tentative territory and re-enters fresh ground gets a full new budget.

**Directly fixes the measured failure mode from BOTH prior attempts**: the
actual regression in both cases was "a new query's own solve pulls in an
entire prior cluster's whole reachable territory, not a thin seam" -
bounding consecutive tentative hops makes that structurally impossible by
construction, independent of cluster size, independent of how much
surrounding territory happens to be tentative.

**A genuine simplification found while designing this, not just a smaller
patch**: this needs NO change to promotion/tier logic at all - the
ORIGINAL, UNCHANGED single-tier rule ("only the literal query target ever
promotes to PERMANENT, everything else stays tentative") can stay exactly
as it always was. `SeedMeshCacheTest.queryingOneCellLeavesMostOfItsOwnClusterMerelyTentative`
(the invariant Attempt 2 broke) is untouched by this design - nothing new
ever gets promoted, so it can't contradict that test at all. A face
beyond the seam bound stays formally "tentative" in the store forever,
but is PRACTICALLY protected from revision beyond that bound by every
future query the same way (any later solve reaching toward it from any
direction hits the same `MAX_TENTATIVE_SEAM_DEPTH` cap) - the practical
protection Attempts 1/2 tried to achieve via explicit early promotion
falls out for free here, without formally changing any face's tier.

**Honest, real limits - not full order-independence, checked by reasoning
through the actual scenario before claiming otherwise**: this bounds the
SIZE of any one joint solve (fixing the measured crashes), but does NOT
achieve fully symmetric order-independence for every case. Two real,
separate residuals:
- **The Phase 3 (disk-anchor) case is untouched** - if one query's own
  natural, UNCONSTRAINED first-touch growth already covers another
  nearby query's seed point entirely (as `clustersAgreeRegardlessOfWhichQueryPointIsSolvedFirst`'s
  own 900-vs-2500 geometry does), the bound never even engages - there's
  no pre-existing tentative territory to bound against yet, the first
  query simply decides everything unconstrained, exactly as it does
  today. Confirmed this class of case needs actual Phase 3, not a better
  Phase 2, already established by direct measurement on both prior
  attempts.
- **Even for a genuinely separate, merely-touching pair of clusters, a
  narrower asymmetry remains**: whichever cluster solves FIRST decided
  its own full deep interior with zero outside influence (nothing existed
  yet to react to); whichever solves SECOND must incorporate the first's
  now-fixed deep interior as ground truth when jointly resolving the
  shared boundary band. The boundary ITSELF gets a fresh, jointly-computed
  decision every time (real, not inherited) - but which side's deep
  interior gets treated as immovable ground truth for that joint decision
  still depends on solve order. This is the same "genuine, irreducible
  residual of any online algorithm with a real caller that can't wait"
  already named earlier in this whole investigation, now narrowed to
  exactly the boundary's own two-sided negotiation instead of the entire
  overlap - a real, measurable improvement, not a complete fix, and should
  be reported as such rather than oversold.

**`MAX_TENTATIVE_SEAM_DEPTH` needs tuning, not assuming** - start at 2 (a
real hex-mesh vertex has 3 mutually-adjacent faces, so 2 rings is the
smallest bound that still lets a genuine 2-batch seam fully resolve at a
shared vertex without immediately walling on the very first tentative
face touched), measure the real effect on both the targeted tests and the
full suite (including `overworldLivePathRealizedWidthNeverBreachesItsOwnBiomesLegalSizeRange`,
where the prior regression actually showed up on the real catalog) before
trusting any specific value.

### ATTEMPT 3 BUILT AND MEASURED (2026-09-13) — two real bugs found+fixed along the way, but the core issue survives even at the tightest bound; REVERTED

Implemented exactly as designed above. **A real, separate bug found and
fixed WHILE building this, not a flaw in the bound idea itself**: the
refactor (in all of Attempts 1/2/3) had dropped `growCluster`'s original
"also seed the seed's own immediate ring directly, before classifying the
seed itself" step - without it, a query whose own literal seed face
happened to already be someone else's PERMANENT covering face got an
EMPTY cluster and a needlessly narrow `resolveFromAlreadyDecidedNeighbors`
check, producing a genuine "query point not covered by any nearby face"
crash whenever organic wiggle put the real point inside a NEIGHBOR's
boundary instead. Root-caused by direct comparison against the original
code (not guessed), fixed by restoring the upfront neighbor-seeding step.
**A second real bug, also found and fixed**: the first cut of the bound
stopped BFS expansion entirely once a face crossed the seam-depth bound,
which could ORPHAN genuinely fresh territory reachable only by walking
past that point (present in neither `cluster` nor `fixedContext`) - fixed
by letting the walk continue past a beyond-bound face (cheap, plain
classification) while still never pulling anything past the bound into
the expensive free set.

**With both bugs fixed, real measurement**: `SeedMeshCacheTest
.queryingOneCellLeavesMostOfItsOwnClusterMerelyTentative` (Attempt 2's own
violation) passes clean now, as designed - this mechanism really does need
zero promotion-tier changes. But **the core "new dead end" problem
survives, unaffected by tuning**: `overworldLivePathRealizedWidthNeverBreachesItsOwnBiomesLegalSizeRange`
(real 48-biome catalog) and `permanentResultsAgreeRegardlessOfExplorationOrderEvenWithTentativeGeometryRevision`
both still hit a NEW `IllegalStateException` dead end - tried `MAX_TENTATIVE_SEAM_DEPTH`
at BOTH 2 and 1 (the smallest meaningful value), identical failure shape
either way. `clustersAgreeRegardlessOfWhichQueryPointIsSolvedFirst` still
crashes too, exactly matching the already-confirmed Phase 3
disk-anchor case (expected, unrelated to the bound value). Full suite: 255
tests, 5 failed at both bound values - same COUNT as the known baseline,
but 2 of those 5 changed FAILURE MODE (assertion → crash), a real
regression in kind even though not in count. Reverted (`git checkout --`).

**Real, deeper finding - not a tuning problem, a structural tension**:
even the tightest possible bound (1 ring - the absolute minimum that still
allows ANY seam renegotiation at all) still occasionally turns a
previously-solvable neighborhood into a genuine CSP dead end. Giving the
CSP MORE simultaneous free variables to jointly satisfy - even just a
one-ring seam's worth - can occasionally make a combinatorially-tight
neighborhood UNSOLVABLE where treating everything as fixed (fewer
unknowns, easier problem) would have succeeded. This isn't a bug to tune
away; it's the real cost of allowing joint negotiation AT ALL, independent
of how narrowly it's scoped.

**A real, sound way to reconcile this, not yet tried**: make the bounded-
seam attempt a FIRST TRY, not the only path - if `trySolveCluster` fails
against the bounded free set, fall back to re-growing the SAME seed with
growth's ORIGINAL "treat any already-decided neighbor as fixed"
rule (today's exact, always-solvable baseline behavior) as a guaranteed-
safe recovery. This can never be LESS solvable than today (the fallback
IS today's own mechanism, unchanged), while still gaining the real seam
benefit whenever the bounded joint solve succeeds - which should be the
common case, given genuine dead ends are rare enough that today's system
practically never needs its own two-attempt recovery either. Structurally
identical in SHAPE to the two-attempt strategy this whole arc has been
trying to move away from - but now attempt 1 is the NEW, better mechanism
and attempt 2 is the OLD, safe one, rather than the reverse - a real,
deliberate return to a two-tier structure, not an accidental regression
back to one. Not yet designed in detail or built - flagged as the likely
next real step.

**B. Canonical ownership tie-break.** Before solving, decide who "owns" a
contested boundary face via a fixed, precomputable rule (e.g. a symmetric
hash of both candidate batches' own canonical keys — same trick
`EdgeDisplacement` already uses for two-sided agreement with no
coordination) instead of "whoever's query got there first." Closer to true
order-independence. Real cost/risk: requires knowing what the NEIGHBOR's
own natural batch would claim, which means partially growing/simulating
from the neighbor's side too — more expensive, and getting two independent
BFS growths to agree on a clean partition without gaps or double-claims is
a real correctness risk, not just a performance one.

**C. Deferred, symmetric seam solve over the existing organic batches — LOCKED, see the "Phase 2, LOCKED" section above for the concrete mechanism.** This is
the "Identity needs the same treatment as size" mechanism above, stated as
one candidate: a batch's own interior gets a real fused
identity+size+geometry joint solve the moment it resolves (permanent
immediately — no order-sensitivity, its context is already fully local);
its boundary faces stay tentative until a later batch's own growth reaches
them, at which point the SAME fused CSP (`TriangleIdentitySizeAssignment
.solve`, unmodified algorithm) plus a joint geometry resolution
(generalizing `resolvePairJointly`'s TECHNIQUE — one binary search per
shared boundary element, not one weight for a whole seam) runs once over
the seam strip, symmetric by construction. Batches stay exactly as organic/
shapeless as `realizeCluster` already grows them — this candidate changes
WHEN a boundary face's identity+size gets permanently decided and WHETHER
that decision is joint or unilateral, nothing about biome shape.

## Open questions, not yet answered — for the LOCKED Phase 2 mechanism

- ~~What happens to a query that lands in cell R before cell R's own joint
  solve has ever run?~~ Answered by the lock-in's own step 5 above: a
  single-sided tentative guess, promoted permanent the instant Minecraft
  actually uses it via the existing `promoteFaceToPermanent` - no new
  mechanism needed for this case.
- ~~How big should a canonical cell be?~~ Doesn't apply to the LOCKED
  mechanism at all - there's no canonical cell/grid in it, batches stay
  exactly as organically-shaped as `realizeCluster` already grows them.
  (A related but DIFFERENT sizing question resurfaces for the separate
  Phase 3 follow-up below, about batch EXTENT, not this mechanism.)
- `FaceFairnessQuota`'s fairness math needs reframing over a batch's own
  real (variable, non-square) area — already flagged by 2026-08-29's own
  doc for the organic-batch design in general, not new to this doc.
- **Corrected on user review (2026-09-13): a prior draft invented a
  separate coarser "canonical grid" for cache/negotiation keying, which
  then needed its own answer for "what happens at a grid corner touching 4
  cells" (a real risk, mirroring the ternary-propagation lesson already
  learned once at the face level). Dropping the invented grid (see "no new
  grid" correction above) dissolves that question entirely** — there is no
  separate grid geometry to have corners at all; negotiation happens
  directly over the mesh's own already-solved 3-way vertex structure, the
  same one CSP legality already handles completely. Kept here only so a
  future reader doesn't reintroduce the same invented-grid detour.
- Does a boundary face staying tentative across MULTIPLE separate batches
  (not just 2) before finally getting a joint resolve reintroduce a NEW
  version of "tentative until truly final," with its own version of
  today's revision-leak, one level up (batch-to-batch instead of
  face-to-face)? Likely yes — needs the same kind of direct empirical
  trace this session used to verify the face-level mechanism, not an
  assumption either way.
- Is `SequentialVertexPlacement`'s protected-neighbor clamp (shipped this
  session) still needed under this design? Possibly not AT A NEWLY-JOINTLY-
  RESOLVED SEAM specifically (no late, unrelated 3rd party to protect
  against there anymore) — but likely still needed WITHIN a batch's own
  interior if that interior's own geometry solve stays sequential
  internally rather than fully joint. Don't remove it speculatively;
  re-measure once a real prototype exists.
- ~~`FaceFairnessQuota`'s quota-deficit tie-break~~ — checked directly
  (mechanism step 4 above), fresh per-call state, not a real risk. Removed
  from open questions, kept struck through so a future reader can see it
  was actually checked, not just forgotten.
- Real persistence cost: `SavedDataFaceStore`/`SavedDataVertexStore` already
  persist a growing, unbounded, organically-shaped decision set — does a
  canonical-cell index change what gets persisted, or only how it's keyed
  and negotiated?

## Why this is a design doc, not a plan

Every candidate above trades a known cost for an unproven benefit. This
project's own standing discipline — "always measure, never assume, revert
if worse" — applies at full force here: before implementing any of A/B/C
against the real 48-biome catalog, build the smallest possible isolated
prototype of the SEAM mechanism alone (two adjacent canonical cells, a
handful of faces straddling the boundary, synthetic targets) and directly
verify the claimed property (same final boundary result regardless of which
cell's query arrives first) before touching the live path at all — the same
methodology `TriangleFieldGeometryRealizationTest
.permanentResultsAgreeRegardlessOfExplorationOrderEvenWithTentativeGeometryRevision`
already uses to catch this exact class of bug, just aimed at the new
mechanism before it ships instead of after.

## Phase 3, on the list, NOT yet designed — canonical batch-EXTENT boundary (separate root cause, found while locking in Phase 2)

Found while stress-testing the Phase 2 lock-in above, before writing any
code for it. Real, and likely BIGGER than the seam asymmetry Phase 2 fixes.

**The problem**: `realizeCluster`'s cluster-growth radius
(`CLUSTER_GROWTH_CAP_RADIUS_BLOCKS=2500`) is a fixed-radius disk anchored
on the LITERAL QUERY POINT, not anything canonical. Two different query
points close together each become a BFS seed for a DIFFERENTLY-POSITIONED
disk - even on completely fresh, uncontested territory, with ZERO
cross-batch negotiation involved at all (Phase 2's whole mechanism doesn't
even apply yet at this point - there's nothing tentative nearby to
negotiate with). This means the actual SET of faces fed to one CSP call
(not just their processing order) genuinely differs depending on which
point got there first, and different free-face-SETS can have different
valid CSP solutions even where they overlap on specific shared faces. The
already-measured 99.07%/20,000-trial order-dependence bug (3 close
together points, forward vs. reverse exploration) is most likely dominated
by THIS, not by seam asymmetry - Phase 2 fixes a real, separate problem,
but probably isn't sufficient on its own to close that specific
already-measured number.

**Why this needs its own careful design, not a quick patch**: the obvious
fix (some canonical, position-independent boundary on how far one solve
batch extends) sounds exactly like the "canonical grid" idea already
correctly rejected earlier this session (2026-09-13, "there was never
question of re-adding square regions that completely ruin our natural
looks") - but it is NOT the same proposal. That rejection was specifically
about a grid constraining the RENDERED BIOME SHAPE (a real, correctly-
avoided regression to the 2026-08-29 straight-seam bug). A grid used ONLY
to bound which faces get grouped into ONE joint CSP-solve call - never
touching the weighted-Voronoi/edge-displacement pipeline that actually
draws a biome's polygon - is a narrower, different thing: biome shape
stays exactly as organic either way, only the invisible bookkeeping of
"which call decided this face's identity+size TARGET" would be
grid-keyed. Needs its own dedicated design pass (a real risk of repeating
the same "sounds like squares" scare if not explained carefully up front,
per the lesson from this exact session) before any implementation -
explicitly on the list, not started.
