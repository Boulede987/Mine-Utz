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

## Why triangles, not squares or hexagons

The mesh a region is solved over is made of Delaunay triangles, not a square
grid or a hex grid. Reasons, in order of how much they drove the decision:

- **Legality (adjacency + size).** A triangle has exactly 3 edges, so a face
  can share an edge with at most 3 other faces, always, by construction (see
  `TriangleAdjacency`). That's the smallest possible edge-neighbor count for
  any tiling that has no ambiguous corner/vertex adjacency to worry about
  (hexagons also get 3-per-vertex, but 6 edges; squares get 4 edges plus 4
  corner-only neighbors nothing in the live pipeline is allowed to ignore
  once you're validating a real placement). Fewer, unambiguous neighbors per
  face means the CSP's per-face legality check (`TriangleIdentitySizeAssignment`)
  stays cheap and never needs a separate "what about diagonals" rule - there
  are no diagonals, only edges.
- **Determinism.** Same seed, same Delaunay triangulation, same face
  adjacency, every run - no floating-point-order or hash-order surprises to
  chase.
- **Performance.** Small, bounded neighbor count per face keeps both the CSP
  backtracking and the seam-growth rasterization cheap enough for live
  generation (see the perf concern below).
- **Natural look.** Irregular triangle sizes and orientations (from Delaunay
  over jittered seed points) avoid the grid-aligned, blocky look a square or
  hex grid would bake in, without giving up a hard cap on adjacency.

The old square macro-grid (`MacroCellPos`, `CellPlacementRules`,
`RegionGridSolver`) is a separate, finite one-shot export/debug CLI path, not
the live in-game generator - it does check Moore/diagonal adjacency, but
that's a different code path solving a different problem, not evidence
against the triangle mesh's 3-neighbor cap.

## Code Concerns

No "reseeding". We don't "try until we make it". We make it work. Until it works, when we fail, we fail loud.

We don't compromise on our desing. Biomes, have correct size and correct adjencies. They have natural shapes (no too thin seams, no straight edges, but weavy natural shapes)

We have a performance concern. This is meant for a live game. Several minutes of solving is hardly acceptable.