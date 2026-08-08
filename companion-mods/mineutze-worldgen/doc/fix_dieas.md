I would like to explore an alternative.

First, take a square. It is the region.
Inside, divide space into simpler shapes (squares, triangles, whatnot).
This makes it easy to check for bad adjency; we could, for example, enforce that a side has only one neighbour.

Then, to make the world feel natural, displace the points making the sides to more natural shapes than straight lines.

Did you get that?

Architected mesh subdivision strategy with constraint-based identity assignment and vertex displacement
Architected mesh subdivision strategy with constraint-based identity assignment and vertex displacement

Yes, I follow — and this is actually a cleaner formulation than pure Voronoi growth, because it turns "check for bad adjacency" into a property you can guarantee by construction rather than something you verify after the fact. Let me walk through how to make it work end-to-end.

Why this works well

If you subdivide the square into a conforming planar mesh — meaning every internal edge is shared by exactly two faces, with no T-junctions (no vertex sitting mid-edge on a neighboring face) — then "one neighbor per side" isn't a constraint you check, it's a structural guarantee of the mesh itself. The adjacency graph falls straight out of the mesh's edge-face incidence: two cells are neighbors iff they share an edge. This is exactly the CSP graph from before, except now it's fixed before you assign identities, not discovered by flood-filling.

Step 1 — Build the base subdivision (must be conforming)

Two solid options, with a caveat on the first:

Recursive rectangular splits (BSP/guillotine cuts). Simple and intuitive, but plain guillotine subdivision of a rectangle does produce T-junctions in general (a wall can border two smaller rooms on the other side). If you go this route, you need to either restrict splits so every cut spans the full width/height of its parent at each recursion level (classic "always full cuts" BSP — conforming by construction, but produces a fairly regular grid-of-strips look), or explicitly patch T-junctions afterward by inserting a vertex into the longer edge so both sides agree on the shared boundary.
Triangulation (e.g. Delaunay over scattered points in the square). This is conforming automatically — every internal edge borders exactly two triangles, no exceptions, no patching needed. It's the more robust starting point if "clean adjacency" is your top priority, at the cost of starting with lots of small triangular faces instead of a few big rectangles.

Either way, you want your data structure to store edges as shared objects referenced by both adjacent faces (a half-edge / winged-edge structure), not duplicated per-face — this matters a lot in Step 3.

Step 2 — Get to target region sizes via merging, not splitting

A single mesh cell is rarely the size you want. So treat mesh cells as atomic units and merge adjacent cells into super-regions until each region's area falls in its identity's [min, max]. Merging two adjacent cells just deletes their shared edge and unions their face lists — the result is still a conforming mesh, and the adjacency graph updates trivially (contract that edge in the dual graph). This is a much safer operation than ad hoc pixel-growth because you can't create cracks or gaps; you're only ever removing internal boundaries from an already-valid mesh.

Step 3 — Identity assignment as CSP, same as before, but now over the merge process

Two ways to sequence it:

Merge first, color after: grow super-regions to hit size targets using pure geometric heuristics (grow the smallest region, prefer round-ish growth), then run CSP+backtracking with arc consistency over the resulting region-adjacency graph, same as I described earlier.
Merge and color together (usually better): pick a seed cell, tentatively assign it an identity, then greedily absorb neighboring cells only if absorbing them keeps all boundary neighbors' domains non-empty (mini arc-consistency check at each merge) and keeps you under max size. Stop absorbing once you're in [min, max]. This catches contradictions while regions are still small and cheap to undo, rather than discovering a dead end after a big region is already built.

Either way, backtracking here operates on cell-merge decisions, which is much cheaper than backtracking pixel claims.

Step 4 — Displace edges for organic shapes, without breaking adjacency

This is the part where the shared-edge data structure pays off. Displace each edge once, and have both adjacent faces read from that same displaced edge — never displace an edge independently per face, or you'll open cracks between regions. Practical technique:

For each shared edge, add a few control points along it, perturb them with Perlin/simplex noise (perpendicular to the edge, scaled so displacement stays modest relative to edge length) or a random walk, then interpolate with a spline (Catmull-Rom or just quadratic Bezier through the midpoint) to get a wavy boundary instead of a straight line.
Boundary edges of the whole square can be displaced too if you want a non-square world outline, same rule applies (endpoints pinned, interior perturbed).
Corners (vertices shared by 3+ faces) should stay fixed, or if you move them, move them once and let every incident face see the same new position — same shared-object principle.

Since displacement never changes which cells are adjacent, only their visual boundary, this step is purely cosmetic and can't reintroduce the topology bugs you solved in Steps 1–3.

Net comparison to the Voronoi approach

This mesh-and-merge method trades some of the very-organic look of weighted Voronoi for much stronger structural guarantees and simpler debugging — you can visualize the clean mesh at every stage and see that adjacency is correct, rather than trusting a flood-fill not to have painted itself into a corner. If you want, I can prototype this (e.g. Delaunay triangulation → cell merge with CSP → edge displacement) so you can see the pipeline actually run — let me know what environment/language you'd want that in.

displacing the edge modifies the surface aera, no?

Analyzed zero-sum area conservation between adjacent surface patches
Analyzed zero-sum area conservation between adjacent surface patches

Yes, exactly — good catch, and it's an important detail the earlier answer glossed over.

What's actually happening

Since two faces share the same edge object, displacement is zero-sum between that pair: if the wavy edge bulges toward face A, A loses that sliver of area and B gains it (or vice versa). So displacement doesn't create or destroy area globally, but it absolutely redistributes it between neighbors — and any single region touches multiple edges, each perturbed somewhat independently, so its net area after displacement is a small random walk away from its pre-displacement value. For a region with many small edge segments, these perturbations partially cancel out by averaging; for a region with just a few edges, the drift can be proportionally larger.

How to handle it

1. Keep displacement amplitude proportional and small. Cap perpendicular displacement at something like 5-15% of the local edge length (or of the smaller adjacent region's characteristic size). This bounds the worst-case area drift per edge, so as long as your merge step (Step 2/3) targets sizes with a bit of headroom inside [min, max] rather than right at the boundary, the post-displacement area should still land inside the legal range.

2. Use zero-mean noise along each edge. If you displace edge control points with noise that bulges out and dents in roughly symmetrically (e.g. a random walk that's forced back toward the straight-line baseline at the endpoints, or Perlin noise sampled so its mean over the edge is ~0), the expected area change per edge approaches zero even though individual instances will still deviate a bit. This is cheap and doesn't require any recomputation.

3. Verify-and-correct pass (the robust option). After displacing all edges for a region, actually recompute its area (polygon shoelace formula works fine even on the spline-ified boundary — sample it into a polyline first). If it's fallen outside [min, max]:

Cheapest fix: scale down displacement amplitude on that region's edges and redo just that region's wobble (re-roll the noise seed), rather than re-touching the mesh topology.
Fallback: nudge one interior control point on a boundary edge further in/out to compensate — a small local adjustment rather than a redo.

This only needs to run per-region after the fact, it's O(edges) not a global search, and it never touches adjacency (topology is already locked from Step 3), so it's safe to iterate.

4. Order of operations matters. Do displacement after all sizing/CSP decisions are finalized, never interleaved — you want area checks to happen against final target ranges, not against a moving target while merging is still happening.

In practice I'd combine 1+2 as the default (cheap, usually good enough) and add 3 as a correction pass only for regions that end up close to their min/max boundary, since those are the ones actually at risk of tipping over the line.

Will this work if i consider diagonal adjency as well?
.b
a.

With a and b illegal

Examined diagonal adjacency limitations and explored mesh constraint solutions
Examined diagonal adjacency limitations and explored mesh constraint solutions

Good question — this is a real gap, and it needs explicit handling because your adjacency graph so far was built purely from shared edges. Diagonal touch (two faces meeting only at a single vertex, like your a/b example) is a different topological event that edge-adjacency doesn't capture at all.

Where diagonal touch comes from

It happens whenever 4 or more faces meet at a single vertex. Picture your a/b grid: the point where all four cells meet is one vertex shared by 4 faces, and the two diagonal pairs (a-a, b-b if legal, or a-b, a-b if not) touch only at that point, not along an edge. In a mesh made of quads (like a plain grid) this is the default situation everywhere. In a triangulation it's rarer but not impossible — any vertex where 4+ triangles fan around it has some non-consecutive pairs touching only at that point.

So the fix has two parts: prevent it where you can, and detect+constrain it where you can't.

Option 1 — Eliminate it structurally (best if it's feasible for your mesh)

If your base subdivision is a generic triangulation (Delaunay over randomly-jittered points, no perfectly co-circular points), vertices are almost always exactly 3-valent or higher only occasionally, and you can enforce "no vertex has 4+ incident faces" as a mesh-generation constraint — if you ever detect a 4+-valent vertex, do a local edge flip (standard Delaunay operation) to split it back into two 3-valent vertices. This removes diagonal-only touching by construction, same philosophy as your original "conforming mesh guarantees adjacency correctness" idea. Quad/grid-based meshes don't have this option since 4-valent vertices are inherent to the grid — so this fix mainly favors triangulation over grid subdivision.

The catch: your merge step (Step 2/3 from before) can re-introduce 4+-valent vertices even from a clean triangulation, because merging cells deletes internal edges and can leave a vertex where several merged super-regions now meet at a point. So this option alone isn't quite sufficient once merging is in play — you likely need Option 2 as a backstop regardless.

Option 2 — Detect it and add it to the CSP (robust, works for any mesh)

Treat diagonal touching as a second adjacency graph, separate from your edge-adjacency graph, with (possibly) its own compatibility rules — since "a next to b" and "a diagonal to b" might have different legality rules in your system.

After building the mesh (and after every merge step, since merges can create new diagonal touches), walk every vertex and check its incident faces. Order them angularly around the vertex; any two faces that are incident to the vertex but not consecutive in that angular order (and don't already share an edge) are a diagonal pair.
Add these as extra edges in your CSP graph, tagged as "diagonal" constraints, alongside the "edge" constraints. Your arc-consistency/backtracking solver doesn't care about the distinction — it just needs the right compatibility table for each edge type.

This is the general, always-correct solution: it doesn't matter whether the diagonal touch came from the base grid, a triangulation quirk, or a merge — you're checking the actual final geometry for point-contacts and constraining exactly those.

Practical recommendation

Do both, in this order: use a triangulated base mesh with edge-flipping to avoid 4-valent vertices going in, since it prevents most cases cheaply, then run the vertex-incidence scan after every merge as a correctness net, since merges are exactly when new diagonal touches sneak back in. Feed anything the scan finds into the same CSP solver as an extra constraint type.

One more wrinkle: the displacement step

Once identities are locked in and you displace edges for organic boundaries, be careful near these shared vertices — if a vertex is a diagonal touch-point between two illegal identities, you generally want to leave that vertex pinned (not displaced) so you don't accidentally create a new, different diagonal touch by moving it into contact with a third region. If instead you want to visually separate diagonal-touching regions (open a small gap or let a third area wedge in), that's a legitimate design choice, but it means inserting new geometry at that vertex — a small local fix, not a global displacement rule.


---


I would like to explore an alternative.

First, take a square. It is the region.
Inside, divide space into simpler shapes (squares, triangles, whatnot).
This makes it easy to check for bad adjency; we could, for example, enforce that a side has only one neighbour.

Then, to make the world feel natural, displace the points making the sides to more natural shapes than straight lines.

Howeve, I also want to guarantee diagonal adjency legality. Iaf A and B are illegal, this is too:

.a
b.

Did you get that?

Synthesized mesh subdivision strategy with diagonal adjacency constraints
Synthesized mesh subdivision strategy with diagonal adjacency constraints

Yes — I follow. You mean two cells that share only a corner vertex, no edge, like:

. a
b .

a (top-right) and b (bottom-left) touch at a single point. You want that point-contact to be checked against the same compatibility rules as a full-edge adjacency, not silently ignored.

This is actually the right thing to worry about, because with square/grid-like subdivisions it's not an edge case — it's the default situation. Every interior vertex in a square grid is 4-valent (four cells meet at each interior point), so diagonal contact happens everywhere, constantly, not occasionally.

How to handle it structurally

1. Represent the subdivision as a proper planar mesh (DCEL / half-edge structure), not just a grid of cells.
You want, for every vertex, the ability to enumerate the cyclic list of faces (cells) touching it. That's what lets you detect "these two faces share a vertex but not an edge."

2. Build your constraint graph from BOTH relations, not just edge-sharing.
When constructing the graph you feed into the CSP solver, add an edge between two cells if they share a full boundary edge, and add one if they only share a vertex. Functionally this turns your adjacency graph from "rook-move" adjacency into "king-move" adjacency — every 4-cell corner becomes a small clique-ish constraint (both diagonal pairs, not just the direct pairs). This is important to know early: it makes the CSP meaningfully more constrained, so it's worth checking with arc-consistency whether your compatibility rules can even satisfy king-move adjacency on your target grid density — some rule sets that work fine for edge-only adjacency become unsatisfiable under diagonal-inclusive adjacency.

3. The real fix — resolve 4-valent corners before you commit to geometry.
A 4-way corner is topologically ambiguous: you could treat it as an X-crossing (point contact only), or you can "split" it into a tiny edge in one of the two diagonal directions. This gives you a powerful trick:

At each interior vertex, look at the two diagonal pairs (NE–SW and NW–SE, in your example: a-b vs the two empty/other cells).
If one diagonal pair is illegal, resolve the vertex by inserting a hairline edge along the other diagonal — the legal pair. This physically pries the illegal pair apart: they go from touching-at-a-point to not touching at all, separated by a sliver of the legal pair's shared edge.
If neither diagonal pair is legal, that vertex configuration is invalid and needs to be caught upstream during CSP coloring, not patched here.

Do this resolution once, deterministically, right after coloring and before you generate any organic boundary geometry. After this step every corner-contact issue has been converted into either "clean edge adjacency" or "no adjacency at all" — there's no ambiguous point-contact left in the mesh for the displacement step to accidentally mess up.

4. Displacement, once resolved, is now safe — with one rule.
Own each boundary curve by the edge, not by either cell individually, and let both adjacent cells reference the same curve object. When you displace an edge's control points to make it wavy, both neighboring cells move together automatically, so:

You never introduce gaps or overlaps between cells that are supposed to be touching.
You never accidentally turn a "no adjacency" pair into a touching pair, because there's no shared vertex/edge for them to snap along.

If you'd done the wavy displacement before resolving the 4-way corners, you'd risk exactly the failure mode you're worried about — independent jitter at a shared corner point can make two diagonal cells overlap or pinch together unpredictably, effectively creating an accidental adjacency the CSP never approved.

A cheaper alternative worth considering

If corner-adjacency checking feels like a lot of extra machinery, switching your base subdivision from a square grid to a triangulated mesh (e.g. Delaunay) sidesteps the problem almost entirely — generic Delaunay triangulations rarely produce 4-valent vertices (interior vertices are typically degree 5+), so ambiguous point-only contacts become rare exceptions instead of the constant default. You'd trade "squares, easy to reason about" for "triangles, adjacency is nearly always a clean shared edge." Worth prototyping both if you're not committed to square cells specifically.