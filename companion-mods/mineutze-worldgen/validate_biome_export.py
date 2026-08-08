#!/usr/bin/env python3
"""
Validates a mineutze-worldgen export (regions.json, sibling of regions.txt/regions.png
from the same export run - export-biomes.ps1 or /mineutzeworldgen exportbiomes).

Reads regions.json, not the ASCII glyph grid: the ASCII file has no adjacency-rule
data, so "illegal adjacency" can't be checked from it alone. regions.json carries the
same cell data plus the graph's actual rules (see JsonExporter's doc comment - it
exists specifically so a program can check exported cells against real rules instead
of guessing). Cell coordinates come straight from JSON, so no need to reparse the
'|'/'-' region-divider formatting the ASCII file adds for human eyes.

Checks:
  1. Illegal adjacency: two orthogonally-adjacent cells with no AdjacencyRule between
     them (same biome is always legal next to itself).
  2. Isolated cells: a cell whose 4-connected same-biome component has size 1.
  3. One-cell-wide pinch points: a cell where both opposite neighbors on an axis
     (north+south, or east+west) are a different biome (or missing) while the cell
     itself has same-biome neighbors on the other axis - the sliver/tendril shape
     PatchOpener/SeamWidener/PatchSizeEnforcer/SeparatorOrganicizer exist to prevent.
     Isolated cells are excluded (reported separately, not as a pinch).

Usage: python validate_biome_export.py <path-to-regions.json>
Exit code: 0 if clean, 1 if any violation found.
"""

import json
import sys
from collections import deque
from pathlib import Path


def load(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def build_allowed(data):
    allowed = set()
    for rule in data["graph"]["rules"]:
        allowed.add(frozenset((rule["a"], rule["b"])))
    return allowed


def is_allowed(allowed, biome_a, biome_b):
    return biome_a == biome_b or frozenset((biome_a, biome_b)) in allowed


def build_cell_map(data):
    cells = {}
    for region in data["regions"]:
        for cell in region["cells"]:
            cells[(cell["x"], cell["z"])] = cell["biome"]
    return cells


NEIGHBOR_OFFSETS = [(1, 0), (-1, 0), (0, 1), (0, -1)]


def find_illegal_adjacencies(cells, allowed):
    violations = []
    seen_pairs = set()
    for (x, z), biome in cells.items():
        for dx, dz in ((1, 0), (0, 1)):  # each undirected pair checked once
            neighbor = cells.get((x + dx, z + dz))
            if neighbor is None:
                continue
            if not is_allowed(allowed, biome, neighbor):
                key = ((x, z), (x + dx, z + dz))
                if key not in seen_pairs:
                    seen_pairs.add(key)
                    violations.append((x, z, biome, x + dx, z + dz, neighbor))
    return violations


def find_components(cells):
    """4-connected same-biome components. Returns list of (biome, set-of-coords)."""
    visited = set()
    components = []
    for start in cells:
        if start in visited:
            continue
        biome = cells[start]
        component = set()
        queue = deque([start])
        visited.add(start)
        while queue:
            x, z = queue.popleft()
            component.add((x, z))
            for dx, dz in NEIGHBOR_OFFSETS:
                neighbor = (x + dx, z + dz)
                if neighbor in visited or neighbor not in cells:
                    continue
                if cells[neighbor] != biome:
                    continue
                visited.add(neighbor)
                queue.append(neighbor)
        components.append((biome, component))
    return components


DIRECTION_OFFSETS = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}


def find_isolated_cells(cells, components):
    isolated = []
    for biome, coords in components:
        if len(coords) != 1:
            continue
        x, z = next(iter(coords))
        neighbors = {}
        for direction, (dx, dz) in DIRECTION_OFFSETS.items():
            nx, nz = x + dx, z + dz
            neighbors[direction] = (nx, nz, cells.get((nx, nz), "(no data)"))
        isolated.append((x, z, biome, neighbors))
    return isolated


def find_pinch_points(cells, components):
    isolated_coords = {next(iter(coords)) for biome, coords in components if len(coords) == 1}
    pinches = []
    for (x, z), biome in cells.items():
        if (x, z) in isolated_coords:
            continue
        north = cells.get((x, z - 1), "(no data)")
        south = cells.get((x, z + 1), "(no data)")
        east = cells.get((x + 1, z), "(no data)")
        west = cells.get((x - 1, z), "(no data)")
        vertical_pinch = north != biome and south != biome
        horizontal_pinch = east != biome and west != biome
        if vertical_pinch:
            pinches.append((x, z, biome, "north-south", (x, z - 1, north), (x, z + 1, south)))
        if horizontal_pinch:
            pinches.append((x, z, biome, "east-west", (x + 1, z, east), (x - 1, z, west)))
    return pinches


def build_report(source_path, cells, components, illegal_adjacencies, isolated_cells, pinch_points):
    lines = []
    lines.append(f"Source: {source_path}")
    lines.append(f"Cells: {len(cells)}, patches: {len(components)}")

    lines.append("")
    lines.append(f"=== Illegal adjacencies: {len(illegal_adjacencies)} ===")
    lines.append("Why: no AdjacencyRule exists between these two biomes (default-deny - every")
    lines.append("pair not explicitly allowed is illegal, same biome is always legal next to itself).")
    for x, z, biome, nx, nz, neighbor in illegal_adjacencies:
        lines.append(f"  ({x},{z}) {biome}  <->  ({nx},{nz}) {neighbor}  [no rule for {biome} / {neighbor}]")

    lines.append("")
    lines.append(f"=== Isolated cells: {len(isolated_cells)} ===")
    lines.append("Why: all 4 orthogonal neighbors are a different biome (or outside the export) -")
    lines.append("this cell is a 1-cell island of its biome, not part of any larger patch.")
    for x, z, biome, neighbors in isolated_cells:
        neighbor_desc = ", ".join(f"{direction}=({nx},{nz}) {nbiome}" for direction, (nx, nz, nbiome) in neighbors.items())
        lines.append(f"  ({x},{z}) {biome}  neighbors: {neighbor_desc}")

    lines.append("")
    lines.append(f"=== One-cell-wide pinch points: {len(pinch_points)} ===")
    lines.append("Why: both neighbors on one axis are a different biome (or outside the export)")
    lines.append("while the cell still connects to same-biome neighbors on the other axis - the")
    lines.append("patch is only 1 cell thick at this point (sliver/tendril shape).")
    for x, z, biome, axis, (ax, az, abiome), (bx, bz, bbiome) in pinch_points:
        lines.append(f"  ({x},{z}) {biome}  pinched along {axis}: ({ax},{az}) {abiome}  /  ({bx},{bz}) {bbiome}")

    ok = not (illegal_adjacencies or isolated_cells or pinch_points)
    lines.append("")
    lines.append("PASS" if ok else "FAIL")
    return "\n".join(lines), ok


def main():
    if len(sys.argv) not in (2, 3):
        print("Usage: python validate_biome_export.py <path-to-regions.json> [output-report.txt]", file=sys.stderr)
        sys.exit(2)

    source_path = sys.argv[1]
    data = load(source_path)
    allowed = build_allowed(data)
    cells = build_cell_map(data)

    if not cells:
        print("No cells found in export - nothing to validate.")
        sys.exit(1)

    illegal_adjacencies = find_illegal_adjacencies(cells, allowed)
    components = find_components(cells)
    isolated_cells = find_isolated_cells(cells, components)
    pinch_points = find_pinch_points(cells, components)

    report, ok = build_report(source_path, cells, components, illegal_adjacencies, isolated_cells, pinch_points)
    print(report)

    if len(sys.argv) == 3:
        output_path = sys.argv[2]
    else:
        output_path = str(Path(source_path).with_name("violations_report.txt"))
    Path(output_path).write_text(report, encoding="utf-8")
    print(f"\nReport written to {output_path}")

    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
