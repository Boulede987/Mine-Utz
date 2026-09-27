# TODO

## Cell-resolution scaling (atomWidthBlocks 300 -> 100, 9x cell count)

Context: `DECISIONS.md`'s 2026-09-27 "Lowering atomWidthBlocks to 100 needs real algorithm work"
entry. Goal is a shapeable beach with real shape freedom, which needs finer grid resolution than
production's 300-block atoms. Simple tuning (steps, attempt count, cycle length) plateaus around
cost ~40 at 9x the cells - a diffusive single-cell-flip-propagation problem, not a patience one.

Tried and REJECTED (both made things worse, not better - see `DECISIONS.md` and commit history):
- More parallel seed attempts at a shallow step budget instead of fewer deep ones.
- Scaling the annealing schedule's `cycleSteps` alone.
- Scaling `NUCLEATION_PROBABILITY`/`DEFAULT_RELABEL_PROBABILITY` 9x to match production's per-cell
  event density - measured WORSE (cost 102 -> 146, wall time 122s -> 212s at the same 80,000,000-step
  budget): these moves are heavier per-attempt than a plain single-cell flip, and diverting more
  budget to them starves the fine local correction the plateau regime actually needs.

Still to try, one at a time, each measured against the real catalog via
`AtomWidthResolutionProbeRunner` (`./gradlew :worldgen-core:atomWidthResolutionProbe`) before moving
to the next:

- [ ] **Local-patch move**: a third move type between single-cell flip and whole-cluster relabel -
  reassigns a small multi-cell neighborhood (e.g. radius-2) together. Middle ground that might speed
  up local restructuring without the overhead/disruption relabel/nucleation showed.
- [ ] **Coarse-to-fine multigrid solve**: aggregate groups of real cells into "super-cells," run the
  same search at roughly production's proven ~1,600-super-cell scale for the global layout, then
  refine locally at full resolution within each already-roughly-correct region. Mechanistically
  targets the diagnosed problem directly (long-range decisions happen where propagation distance is
  short; fine refinement only ever fixes small local detail). Real design + implementation work, the
  most promising direction, not a quick probe.
- [ ] **Region-based divide-and-conquer** (solve blocks somewhat independently, reconcile boundaries
  after): real risk flagged in `DECISIONS.md` - close in shape to the old hex-mesh region CSP already
  tried and deleted because per-region solving couldn't be made reliable for every world seed. Only
  worth attempting if it genuinely differs this time (e.g. seeded from the multigrid coarse solve
  above, or reconciled via shared boundary state), not a plain repeat.
