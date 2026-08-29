# 2026-08-29 — Hex mesh + edge displacement + precomputed compatibility

Status: proposed, not yet built. Supersedes the triangle-mesh-plus-competitive-growth
design once implemented (currently live: `SeedGrid` → `DelaunayTriangulation` →
`TriangleAdjacency` → CSP (`TriangleIdentitySizeAssignment`) → `SeamGrowthRasterizer`).

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

## Change 3 — precompute a biome/size compatibility table

The biome catalog and its `AdjacencyRule`s + size ranges are small (~6
biomes in `ModBiomeGraph.DEFAULT`) and static — they don't depend on any
region's actual geometry. Rather than deriving "(biome A, size A) legal next
to (biome B, size B)?" live during every region's CSP run, compute the full
legal/illegal table once, up front, from the biome graph alone.

This gives two wins:
- CSP's per-candidate legality check becomes a table lookup instead of a
  recomputation — a real backtracking-speed win, independent of which
  rasterizer is in use.
- For Change 2's size problem: sizes can be chosen so a face and its
  neighbor are already known to have a jointly-satisfiable size pairing
  *before* any displacement amplitude is picked, instead of discovering a
  size clash after rasterizing.

## Open risks / unknowns (to validate before touching the live path)

- Displaced-polygon rasterization needs prototyping against a small mesh to
  see how it actually looks compared to growth's organic patches — hex
  mesh's fixed macro-topology (unlike growth's ability to let one biome's
  shape wander past its own cell entirely) may look more "warped-polygon"
  and less free-form; needs a visual check, not just a legality argument.
- Vertex-watertightness (Change 1's geometric requirement) needs an actual
  implementation approach (shared displaced vertex positions, or keep
  vertices fixed and only displace edge midpoints) — not yet decided which.
- Change 3's table size/build cost for a larger future biome catalog isn't
  assessed yet (fine at ~6 biomes; revisit if that catalog grows a lot).
- `doc/DESIGN.md`'s current "Why triangles, not squares or hexagons" section
  (added 2026-08-29, commit `ec278b94`) will need revising if this proposal
  is actually built — it currently argues for the triangle mesh this
  proposal replaces.
