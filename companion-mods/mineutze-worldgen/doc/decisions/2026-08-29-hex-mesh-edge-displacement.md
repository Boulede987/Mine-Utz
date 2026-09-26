# 2026-08-29 — Hex mesh + edge displacement + precomputed compatibility

**Superseded 2026-09-20: this whole live hex-mesh/CSP system was deleted and replaced by a
per-world solved plan; see DECISIONS.md's 2026-09-20 entry. Kept as history, describes deleted code.**

Status: implemented and live. Superseded the triangle-mesh-plus-competitive-growth
design (`SeedGrid` → `DelaunayTriangulation` → `TriangleAdjacency` → CSP
(`TriangleIdentitySizeAssignment`) → `SeamGrowthRasterizer`/`GrowthGapFiller`, all
deleted) with `HexMeshBuilder` → `TriangleAdjacency` (generalized to any `MeshFace`) →
the same CSP → `FaceSizeFeasibility` → `EdgeDisplacementRasterizer`. Self-adjacency
(a biome legal next to itself) was additionally banned permanently in `BiomeGraph`,
matching the "one hex face is one biome patch" intent this decision was made for -
see that class's own doc for the measurement backing that change.

## Why the current design is being revisited

The live pipeline splits work into a CSP phase (assigns each triangle face a
legal biome + target size, using `TriangleAdjacency`'s ≤3 edge-neighbors) and a
growth phase (`SeamGrowthRasterizer`) that rasterizes those faces into pixels
via competitive, priority-ordered cell claiming — chosen for its organic,
natural-looking boundaries.

That growth phase must re-check legality live, per cell, against all 8
Moore-neighbors, for two reasons the CSP's mesh graph can't cover on its own:

1. **Vertex-only triangle neighbors.** Two triangles that share only a point
   (not an edge) are never linked in `TriangleAdjacency`, but their rasterized
   cells can end up Moore-adjacent near that shared vertex. The CSP never
   legality-checked that pair.
2. **Competitive growth crosses mesh boundaries on purpose.** A face's
   territory can spill past its own triangle into a cell nearer another
   face's frontier — measured necessary to avoid dead zones (an earlier
   version scoped strictly to mesh-neighbors and produced them).

This means the triangle mesh's "decide legality once, at generation time"
promise is only ever partially kept — growth-time checking was never
optional under this design. The original intent was for legality to be fully
decided at mesh-generation time, with the mesh/rasterizer only responsible
for natural-looking shape afterward. Two changes get us there:

## Change 1 — hexagon mesh instead of triangle mesh

Regular hex tiling has exactly 3 faces meeting at every vertex. With only 3
faces around a point, every pair of them also shares an edge (3 faces, 3
edges radiating from the vertex, one edge between each consecutive pair) —
there is no configuration where two faces touch at a vertex only. This is a
structural property of degree-3 vertices, not a convention: it holds for
every vertex in the tiling, not just typical ones.

Triangle mesh fails this (vertex degree runs ~6 on average; opposite
triangles around a point can share the vertex with no edge between them —
this is exactly failure mode 1 above). Square grid fails it too (vertex
degree 4; diagonal pairs share only the corner).

Consequence: with a hex mesh, checking legality on each face's ≤6 edges at
CSP time is a *complete* legality check — no vertex-only case can ever slip
through. Edge/vertex displacement (see Change 2) then needs to keep the 3
edges meeting at a shared vertex converging on the same displaced point (a
geometric watertightness requirement, so patches don't crack apart) — but
that's no longer a legality requirement, since every pair that could touch
there was already CSP-checked.

## Change 2 — edge displacement instead of competitive growth

Once every face's biome + size is CSP-assigned and every pairwise touch
(hex: all of them) is known legal, shape comes from displacing each shared
edge into a wavy curve (e.g. midpoint displacement / noise, keyed
deterministically off world seed + edge identity so both faces sharing an
edge compute the identical curve — no gaps, no overlaps) and rasterizing
each face's own displaced polygon independently. No competition, no
per-cell runtime legality check: legality was already fully decided at CSP
time (Change 1 is what makes that claim actually true, with no residual
gap).

Tradeoff this introduces: a displaced polygon's rasterized area is only
approximately its triangle/hexagon's original geometric area (± displacement
amplitude), so hitting an exact target width (e.g. "desert: 700-1000 blocks
wide") is no longer a direct stopping condition the way growth's
cell-by-cell quota tracking gave for free. Change 3 exists to close that gap
before it ever reaches rasterization.

## Change 3 — per-region, per-edge size feasibility (revised from the original proposal)

The original proposal here was a static, precomputed biome/size compatibility
table. That didn't hold up: `BiomeGraph.isAllowed` is already an O(1) hashmap
lookup and `AdjacencyRule` has no size-coupling field at all — legality never
depends on size, so there was no live-legality-recomputation cost to save,
and no "jointly-satisfiable size pairing" concept to precompute.

What actually shipped instead is `FaceSizeFeasibility`, run once per region
solve between the CSP and the rasterizer: for each free face, compare its
raw undisplaced area (shoelace formula over its hex vertex loop) against its
CSP-assigned target width, distribute the error across its own edges, and
for each shared edge reconcile both faces' desired correction into one
signed bias value (clamped to a safe fraction of edge length so the
displaced polygon can't self-intersect). `EdgeDisplacementRasterizer` then
feeds that bias straight into the same edge-displacement function Change 2
already describes — sizes stay approximate ("close to target"), not exact,
same as `FaceFairnessQuota`'s own quotas were already treated as soft.

## Open risks / unknowns — resolved

- Displaced-polygon rasterization's visual quality: confirmed organic, not
  grid-aligned, via direct PNG renders at production scale (see
  `HexBiomeRasterVisual`) — one hex face reads as one wavy-edged biome patch,
  not a warped-polygon tessellation.
- Vertex-watertightness: solved via `EdgeDisplacement.displacedPolyline`
  hashing each edge by its own endpoint *coordinates* (canonical lexicographic
  order), not indices — both faces sharing an edge compute the identical
  curve regardless of which "direction" each reads it from, so shared
  vertices always land on the same displaced point with no coordination
  needed.
- Change 3's original "compatibility table" framing turned out not to be
  needed at all (see above) — no table-size/build-cost question to revisit.
- `doc/DESIGN.md`'s "Why hexagons, not triangles or squares" section now
  reflects this design (revised from the section that originally argued for
  the triangle mesh this proposal replaced).
