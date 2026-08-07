# Session handoff — 2026-08-07 — mineutze-worldgen

Branch: `worldgen/multi-source-gap-fill`. Nothing committed this session — working tree has all changes below, uncommitted.

## What changed

**`worldgen-core/.../biomegraph/BiomeGraph.java`** — added eager construction-time validation: BFS all-pairs check that every non-adjacent biome pair is bridgeable within 3 hops in the allow-graph (`MAX_BRIDGE_HOPS`, must manually stay in sync with `GapFiller.MAX_REASSIGN_DEPTH + 1`). Catches "impossible in principle" graph misconfigurations at construction time instead of a runtime crash. Does NOT catch runtime donor-scarcity (see GapFiller changes) — that's a placement-density problem, not graph structure.

**`worldgen-core/.../regionsolve/GapFiller.java`** (the bulk of this session) — three-tier dead-end resolver, because the "universal filler" invariant (one biome always legal everywhere) was removed earlier this session at user's request, which broke termination guarantees:
1. `makeLegalHere` — bounded (depth-2) recursive neighbor reassignment, connectivity/size-safe. Fixed a real bug here: original version returned success after fixing only the *first* blocking neighbor, leaving others unresolved — could silently commit an actual illegal adjacency. Now resolves every blocking neighbor before declaring success, full rollback on failure.
2. `dropSmallestBlockingPatch` — when tier 1 exhausted, undo the smallest directly-blocking patch entirely (as if never placed), retry the whole fill. Capped at 3 (`MAX_PATCH_DROP_RETRIES`) — raising the cap empirically just relocates remaining crashes further into the region rather than eliminating them (tested up to 8), so it's not a complete fix on its own.
3. `resolveRemaining`'s `force` mode — unconditional last resort: directly commit whichever biome best fits the dead-end cell's neighbors, ignoring legality, for that one cell. **Caught and fixed a real bug in my own first attempt**: originally implemented as "clear the blocking neighbors and let the next fill pass re-resolve" — this looped, because the next pass usually just refilled the same cells with the same biomes (nothing else had changed), hit a 1028-iteration safety cap on seed 42. Fixed by committing the cell's value directly and permanently instead of depending on another pass.

**`worldgen-core/.../regionsolve/RegionSolver.java`, `PatchOpener.java`, `SeparatorOrganicizer.java`** — plumbing changes to support the above (`GapFiller.fillGaps` gained a `MacroGridConfig grid` param; `SeparatorOrganicizer.minCellsFor`/`donorComponentSizeWithout` made package-visible for `GapFiller` reuse).

**`worldgen-core/.../biomegraph/BiomeGraph.java`, `BiomeNode.java`, `ModBiomeGraph.java`** — removed the universal-filler concept entirely (no `universalFiller` field/flag anywhere). Plains lost its "legal next to everything" status; badlands lost its `PLAINS-BADLANDS` rule specifically (only remaining ally: desert) at user's explicit request ("Plains will have real rules too!").

**`src/main/.../integration/ModOverworldBiomeSource.java`** — removed the plains-based `fallback` field/behavior that depended on the old universal-filler guarantee; now throws if `resolved == null` (should be unreachable given GapFiller's new guarantee).

**Test files** (`BiomeGraphTest`, `GapFillerTest`, `PatchOpenerTest`, `SeparatorWidenerTest`, `RegionSolverTest`, `TestBiomeNodes`, others) — mechanically updated for signature changes, plus new tests: `isolatedNodeWithNoBridgeFailsFast`, `pairBeyondBridgeHopsFailsFastEvenWhenGraphIsFullyConnected`, `aDeadEndCellGetsResolvedByReassigningANeighborInstead` (exercises the multi-blocking-neighbor case specifically). All 69 `worldgen-core` tests pass.

**`doc/KNOWN_ISSUES.md`** — new "Fixed" entry at the top documenting the full universal-filler-removal → three-tier-resolver arc, including both bugs caught and fixed mid-session, with real numbers.

**`doc/fix_dieas.md`** (new, untracked, typo in filename — not mine, left as user created it) — pasted AI chat responses proposing a "topology-first, geometry-second" architecture (abstract Delaunay/Voronoi seed graph → CSP coloring with backtracking+AC-3 → THEN geometry). Discussed but not started — see Next steps.

## Decisions made

- **Universal-filler invariant removed permanently, not replaced with an internal-only filler.** User: "I don't like the idea of a 'filler biome'. Plains will have real rules too!" The termination guarantee moved to `GapFiller` instead of `BiomeGraph`.
- **Dead-end resolution scope: reassign neighbors, extend to multi-hop, but keep it bounded (depth 2) — not general unbounded backtracking.** General backtracking was tried once before (old per-position candidate-biome backtracking in `RegionSolver`) and abandoned for fighting the wrong problem (over-subscription, not adjacency). This is a different, narrower thing (bounded local search), not a repeat of that mistake.
- **Patch-drop retry scope: narrowest possible — swap only the offending patch(es), not the whole region's placement.** User explicitly chose this over a whole-region re-solve when asked.
- **Fail-loud is NOT overridden for the shipped game — the failure mode was eliminated by design instead.** User corrected my initial (wrong) framing directly: "We never crash because our mod will not have a situation that guarantees a crash. No bad end possible. But, when we encounter an exception that should not happen, we do crash loudly." The three-tier `GapFiller` resolution isn't a "graceful fallback that avoids a would-be crash" — its last tier is a proven-always-successful terminal step, not a caught exception. Any genuine invariant violation (a real bug, not this specific failure mode) still crashes exactly as before — nothing catches or suppresses unexpected exceptions. Corrected in memory (`feedback_fail_loud.md` addendum) — this is an important distinction to get right next session, not a relaxation of the rule.
- **Static graph validation is necessary but not sufficient — deliberately did not try to make it catch runtime donor-scarcity.** That would require reasoning about placement density, which the graph alone can't know. Left as an explicitly-accepted limitation, not a gap to close later without a different mechanism.

## State of known issues

- **Resolved this session**: the crash-on-generation bug (badlands/forest/plains dead-ends, 100% crash rate across 6 seeds pre-fix) — fully fixed, verified zero crashes across all 6 previously-failing seeds, illegal-adjacency count from the last-resort tier is small (0-58 cells out of 9216 per seed, 0-0.6%).
- **Still open, unchanged from before this session** (see `doc/KNOWN_ISSUES.md` "Open" section): `GrowthCohesionProfile.directionalRunMajor/Minor` deadlocks whenever minor≥2 (`ELONGATED` aliased to `COMPACT` as workaround); `PatchOpener` doesn't fix long seams between two patches (only short tendrils); hard straight-line seam at every region boundary; `PatchSizeEnforcer`/`ConnectedComponents` are border-blind; cross-region solve is order-dependent; `BudgetPlanner` is adjacency-blind; no per-biome rarity/weight.
- **Not verified this session**: whether the last-resort force-commit tier's rare illegal adjacencies are visually acceptable in-game (only checked programmatically via the export CLI's JSON output, never looked at it rendered in Minecraft). Worth an in-game look before considering this fully closed.

## Next steps

1. **Decide whether to commit this session's work.** Nothing is committed yet on `worldgen/multi-source-gap-fill`. Recommend committing before starting anything else — this is a large, coherent, fully-tested change and letting it sit uncommitted risks losing the "why" if the next session starts elsewhere.
2. **Look at the force-commit tier's output in-game**, not just the export CLI, to confirm the rare illegal seams are actually invisible/acceptable at play scale.
3. **Topology-first CSP redesign** (`doc/fix_dieas.md`) — discussed, not started. This would replace much of `SeedPlacer`/`RegionSolver`'s placement flow with: seed scatter → Delaunay/Voronoi abstract graph → CSP coloring (backtracking + MRV + AC-3) on that small graph → THEN geometry (weighted Voronoi/flood-fill), with `GapFiller` shrinking from workhorse to rare-edge-case repair. Would make this session's whole crash class structurally impossible instead of patched around. Explicitly flagged as a real architecture fork, bigger than this session's work — spec it out properly before touching code, per user's own request ("we'll build it in another session").
4. Lower priority, already-open: the long-seam-between-two-patches issue (`KNOWN_ISSUES.md` #1) and the `directionalRunMajor/Minor` deadlock (#0) remain unaddressed.

## CLAUDE.md update suggestions

None of the below currently exist in `companion-mods/mineutze-worldgen`'s CLAUDE.md (verify path/existence — didn't check this session). Candidates, for user approval:

- `MAX_BRIDGE_HOPS` in `BiomeGraph` and `MAX_REASSIGN_DEPTH` in `GapFiller` are manually coupled (`BiomeGraph` has no dependency on `regionsolve`, so this can't be enforced by the compiler) — a comment marks this in both places, but worth a CLAUDE.md line so it's not missed if either constant changes independently later: *"If GapFiller.MAX_REASSIGN_DEPTH changes, BiomeGraph.MAX_BRIDGE_HOPS must change too (MAX_REASSIGN_DEPTH + 1) — no compiler link between the two modules."*
- JDK path for this project, since `./gradlew` fails with a confusing "requires JVM 17, this is Java 8" error otherwise: *"Build with `JAVA_HOME=/c/Users/alixm/.jdks/ms-21.0.7` — system default `java` is 1.8, incompatible with NeoForge's userdev plugin."*
