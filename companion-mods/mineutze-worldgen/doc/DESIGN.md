# Why this companion mod?

## To place constraints on biome adjency

For example, I want to be able to say : 
- deserts are only ever next to savannas and badlands
- underground_deserts are only ever under deserts
- deserts are never next to oceans of any kind
- deserts are never next to beaches

any biome can have adjency constraints that I will configure

## To dictate biome size

deserts -> between 700 and 1000 blocks wide
groves -> between 400 and 500 blocks wide

## To dictate world shape from biome placement

The shape of the terrain is dictated by the fact there is a desert.
Before computing any 3d shape, we compute the distribution. In x,y,z is a warm ocean, then in x1,y1,z1 there is a badland, then in x2,y2,z2 there is a desert. Therefore, x,y,z -> deep, watre ; x1,y1,z1 -> uneaven, rocky ; x2,y2,z2 -> flat, even, large

## To dictate biome parameters

deserts have no rivers, no lakes
forests have rivers

## Why hexagons, not triangles or squares

The mesh a region is solved over is a regular hex lattice (`HexMeshBuilder`),
not Delaunay triangles or a square grid. This superseded an earlier
triangle-mesh design (see
`doc/decisions/2026-08-29-hex-mesh-edge-displacement.md` for the full
rationale) once a real gap in that design surfaced: a Delaunay triangle's
vertex degree averages ~6, so two triangles can share only a *vertex* (not an
edge) and still end up pixel-adjacent after rasterization - a case the CSP's
edge-only legality graph (`TriangleAdjacency`) never checked, which forced
the old rasterizer to re-check legality live, per cell, during growth (the
opposite of the original goal: legality decided once, at mesh-generation
time). Reasons for the hex lattice specifically, in order of how much they
drove the decision:

- **Legality (adjacency + size), *complete*, not just cheap.** A regular hex
  lattice has exactly 3 faces meeting at every vertex. With only 3 faces
  around a point, every pair among them also shares an edge (3 faces, 3
  edges radiating from the vertex, one edge between each consecutive pair) -
  there is no configuration where two hex faces touch at a vertex only, ever,
  by construction. That makes edge-adjacency and vertex-adjacency the *same*
  relation on a hex mesh, so the CSP's edge-only legality check
  (`TriangleIdentitySizeAssignment`) is provably complete: no residual
  vertex-only gap, unlike a triangle mesh. This is also why the live
  rasterizer (`EdgeDisplacementRasterizer`) needs no live legality re-check
  at all - legality was already fully decided at CSP time.
- **One face is one biome patch.** The original design intent was never
  dynamic growth/merging of same-biome cells into bigger patches - it was one
  hex face directly BEING one biome patch, with size achieved by displacing
  that face's own edges (`EdgeDisplacement`, biased by
  `FaceSizeFeasibility`), not by competing for neighboring same-biome
  territory. Self-adjacency (a biome legal next to itself) was the mechanism
  that would have silently allowed that unwanted merge, so it's permanently
  banned (see `BiomeGraph.isAllowed`'s own doc).
- **Determinism.** Same seed, same regular lattice, same face adjacency,
  every run - a regular lattice's vertex positions are pure closed-form
  geometry, not hashed, so there's no floating-point-order or hash-order
  surprise to chase, and no per-cell jitter to reintroduce that uncertainty.
- **Natural look, without giving up the adjacency guarantee.** A perfectly
  regular lattice would read as grid-aligned/blocky on its own - all the
  organic visual variety instead comes from edge displacement (wavy,
  multi-point curves, not one visible bump) and from irregular multi-hex
  biome patch shapes the CSP assigns, not from irregular cell shapes.

The old square macro-grid (`MacroCellPos`, `CellPlacementRules`,
`RegionGridSolver`) remains a separate, finite one-shot export/debug CLI
path, not the live in-game generator - it does check Moore/diagonal
adjacency, but that's a different code path solving a different problem, not
evidence against the hex mesh's own vertex-degree-3 completeness. Since
self-adjacency is now globally banned, that debug path's own growth model
(which still needs same-biome cells to merge into patches) keeps a local,
file-scoped exemption for its own same-biome adjacency checks - a deliberate
difference from the live path, not an oversight.

## Code Concerns

No "reseeding". We don't "try until we make it". We make it work. Until it works, when we fail, we fail loud.

We don't compromise on our desing. Biomes, have correct size and correct adjencies. They have natural shapes (no too thin seams, no straight edges, but weavy natural shapes)

We have a performance concern. This is meant for a live game. Several minutes of solving is hardly acceptable.