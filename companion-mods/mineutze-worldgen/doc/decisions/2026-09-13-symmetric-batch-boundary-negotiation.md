# 2026-09-13 — Symmetric batch-boundary negotiation (no new grid, no new shape — organic batches stay organic, only WHO decides first changes)

Status: **Phase 1 (isolated seam prototype) VERIFIED** - `SeamResolution`/
`SeamResolutionTest` (commit `15d04460`) directly confirmed identity+size+
geometry are byte-identical regardless of seam-resolution order, against
the real 48-biome catalog. **Phase 2 (live persistence mechanism) LOCKED** -
see "Phase 2, LOCKED" section below - designed, not yet implemented.
**Phase 3 (canonical batch-extent boundary) is a separate, real, newly-found
follow-up, NOT yet designed** - see its own section at the end. Branch
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

**Not yet implemented** - this section locks in the DESIGN (superseding
Candidates A/B/C below as abstract alternatives - C's own shape is what
this formalizes), not the code. Next step is building it against
`realizeCluster`/`growCluster` directly, with the same measure-first
discipline as every other step this session (regression suite, plus a real
adversarial order-dependence test at the live-path level, not just
`SeamResolutionTest`'s isolated one).

## Candidate mechanisms (historical - A/B were real alternatives considered before C/the Phase-2 lock-in above, kept for the record, not live options anymore)

**A. Wider solve, narrower commit.** Grow the batch one ring larger than
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
