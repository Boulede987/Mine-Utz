# Toroidal (2D) patch construction - findings (2026-09-18)

## Goal

Build a genuinely wide-AND-tall periodic patch (not just the row-stack's tall-thin shape) on
a finite, wrapped `ToroidalGrid`, covering all 66 real catalog biomes with each cluster's real
width inside its declared `[min, max]` range.

## What was proven, in order

1. **v1** (position-ordered fill, MAX hard-enforced, MIN only checked post-hoc): failed
   near-instantly at every grid size (8x8 through 32x32), zero real coverage. Raising the
   backtrack budget 100x (5,000 -> 500,000) let 8x8 succeed but with severe coverage failure
   (53/66 missing) and no improvement at all for bigger grids.

2. **v2** (needs-driven min-pursuit via active recruitment, `growToMinimum`, plus a bias
   toward not-yet-used biomes): fixed size correctness but coverage regressed further without
   the bias, then partially recovered with it - and crucially, bigger grids (16x16 and up)
   regressed to failing entirely. Real proof this was a genuine structural dead end, not
   impatience: raising the budget from 50,000 to 1,000,000 backtracks produced an **identical**
   stuck point (127/256, 552/576, 48/1024) - 20x more budget changed nothing.

3. **v3** (demand-ordered two-phase: seed and grow every biome to its own minimum first,
   biggest-demand-first, THEN plain-fill whatever's left): fixed v2's dead end - Phase 1
   reliably seeded all 66 biomes on 24x24 and 32x32. But Phase 2 then got stuck almost
   immediately (88/576, 104/1024) despite a full 50,000-backtrack budget: gap cells surrounded
   by several different, already-FIXED Phase 1 clusters with no jointly-legal biome among all
   66 - and Phase 2's own backtracking could never reach back into Phase 1, which was frozen
   by the time Phase 2 ran.

4. **v4** (one real backtrack stack shared by both stages, so backtracking genuinely CAN
   reach back into seeding decisions): fixed the freeze - real, measured proof: backtrack
   counts started scaling with grid size (12,193 / 33,036 / 50,000-exhausted) instead of
   exhausting after 1-3 like v3. But every run still died at the exact same point: right at
   the seed-to-fill boundary. Two different forward-checking strengthenings during Phase 1
   (local-boundary-only, then whole-grid) produced the *identical* failure point both times -
   real proof the dead cell wasn't being created during Phase 1 at all; it was Phase 2's own
   first few decisions, with Phase 1 already frozen and unreachable.

5. **v4.1** (rank seed positions by how constrained they already are, instead of
   first-legal-in-shuffled-order): did not move the wall either. Real, measured: 16x16 hit
   genuine exhaustion (13,102 of a 30,000 budget used - stopped because it ran out of things
   to try, not because it ran out of patience). Four straight variants dying at the exact same
   structural point (the seed/fill boundary itself) was the real signal: the boundary itself,
   not the heuristics on either side of it, was the problem.

6. **v5** (drop the seed/fill split entirely - one real MRV loop over the whole grid from the
   start, with `growToMinimum` called unconditionally after every placement - safe no-op when
   already at/above minimum, real recruitment when not): this was the real breakthrough for
   the *structural* dead end. All four grid sizes now reach **complete fill** (64/64, 256/256,
   576/576, 1024/1024) for the first time in the whole investigation. Remaining failures are
   pure coverage failures (some of the 66 biomes never made it in), not mid-construction
   freezes.

7. **v5 coverage investigation**: the missing biomes were the SAME ~31-32/66 every run, not a
   near-miss. Verified against the real catalog (`biome-graph.json`, 66 nodes / 439 edges):
   missing biomes have measurably lower real adjacency degree (avg 11.16 legal neighbor types)
   than the ones that made it in (avg 15.32) - narrow-adjacency "specialist" biomes were
   consistently losing their few compatible slots to wide-adjacency "generalist" biomes.
   Fixed by ordering not-yet-used candidates by ascending real legal-neighbor count (spend a
   specialist's rare opportunity when it appears, computed once per build via
   `computeLegalNeighborCounts`).

8. **Real correctness bug found and fixed**: the exclusion map used to avoid immediately
   retrying a just-failed `(position, biome)` combination was a plain, never-cleared
   `Map<ToroidalPos, Set<BiomeId>>`. Once backtracking unwound PAST the point where an
   exclusion was recorded, the exclusion stayed in force anyway - a textbook chronological-
   backtracking incompleteness bug: the search could report "exhausted" while a real solution
   still existed, because a candidate that only failed in an old, already-abandoned context
   stayed permanently barred. Fixed with trail-scoped exclusions (`Exclusion.
   validWhileActionActive`): each exclusion remembers which stack action was on top when it
   was recorded, tracked via `activeActionIds`, and is only still honored while that action
   remains on the stack - once the search backtracks past it, the exclusion is treated as if
   it never happened. Real, measured effect: search depth increased substantially (8x8 no
   longer exhausted at 2,201 backtracks - it now runs past 5,000 without exhausting).

## Current honest state

With both the coverage-ordering fix and the exclusion-completeness fix in place, a real
200,000-backtrack run (about 20 minutes of wall-clock time total across all four sizes) was
still inconclusive:

- 8x8 and 16x16: reach complete fill but still fail coverage even after 200,000 backtracks
  (budget-limited, not proven impossible).
- 24x24 and 32x32: got WORSE under the fixed exclusion logic - they no longer even reach
  complete fill within budget (520/576, 857/1024). Likely explanation: the old (buggy)
  exclusion map was *effectively* pruning large parts of the search space, even though that
  pruning was logically unsound - removing it made the search honest but also made each
  backtrack markedly more expensive at scale (859s of wall time for 32x32 alone; MRV rescans
  every undecided cell against all 66 biomes on every single decision).

## Conclusion

The seed/fill phase-boundary freeze (v1 through v4.1's shared failure mode) is real and
**solved** by v5's unification. The coverage-ordering bias and the exclusion-completeness bug
were both real, are both fixed, and both measurably improved search depth. What remains is a
genuine **search-efficiency ceiling**: blind trial-and-backtrack, even MRV-ordered and
bias-corrected, does not obviously converge within a practical backtrack budget for full
66-biome coverage on a real toroidal grid against this catalog's adjacency structure.

Getting further would mean real constraint propagation (arc-consistency / AC-3-style pruning
applied proactively, not just discovered via backtracking after the fact) rather than another
parameter or heuristic adjustment - a substantial new piece of work, not attempted here.
Stopping this specific line of investigation at this point per 2026-09-18 direction.
