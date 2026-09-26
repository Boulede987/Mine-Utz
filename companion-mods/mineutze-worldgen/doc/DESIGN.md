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

## How a world's biomes are decided

Solved once, whole, at world creation - not live, per region, as the player explores. A
biome is a connected cluster of cells on a finite, periodic (toroidal) hex grid; min-conflicts
local search assigns every cell an identity and grows/shrinks clusters until every biome's
size, adjacency legality, shape and border requirements hold at once (`ToroidalSizedLocalSearch`).
The solved pattern is then bent into natural, non-grid-aligned borders by an area-preserving
warp (`DivergenceFreeWarp`) and rasterized to a fixed-resolution map (`BiomeMapRasterizer`),
which is verified directly - not trusted from the warp's own math - before being saved with the
world (`BiomeMapVerifier`, `RealizationSelector`). The saved raster is the only thing read at
play time: one array lookup per column, no solving while the player is near it.

This replaced an earlier live, per-region hex-mesh CSP design (why hexagons over triangles,
edge displacement, macro-grid export path, etc.) that could not be made to solve reliably for
every world seed. See `DECISIONS.md`'s 2026-09-20 "One solved biome plan per world" entry for
the full history and why it was replaced, and
`doc/decisions/2026-09-20-per-world-tile-integration-design.md` for the integration design.

## Code Concerns

No "reseeding". We don't "try until we make it". We make it work. Until it works, when we fail, we fail loud.

We don't compromise on our desing. Biomes, have correct size and correct adjencies. They have natural shapes (no too thin seams, no straight edges, but weavy natural shapes)

We have a performance concern. This is meant for a live game. Several minutes of solving is hardly acceptable.