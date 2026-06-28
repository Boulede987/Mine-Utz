"""
worldgen/app_coverage.py
=========================
Coverage tab: bar chart of biome area coverage.

Shows what fraction of the preview map each biome wins.  Coverage % is a
statistically meaningful proxy for real-world distribution — it is stable
across seeds.  Patch count, avg/median/largest patch size, and concentration
are NOT shown: those are measured in the fixed-seed 128×128 preview and do
not predict patch sizes in any actual Minecraft world (different seed, and the
preview resolution does not match Minecraft's biome noise spatial scale).
"""

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import streamlit as st

from worldgen.biome_colour import build_rgb_image, compute_biome_coverage

COVERAGE_BAR_HEIGHT_PER_BIOME = 0.28
COVERAGE_CHART_WIDTH_INCHES   = 10
COVERAGE_CHART_MIN_HEIGHT     = 4


def _render_coverage_chart(
    sorted_biomes: list[str],
    coverage_pcts: list[float],
    biome_colors:  dict[str, tuple],
) -> None:
    colors       = [biome_colors.get(biome, (0.5, 0.5, 0.5)) for biome in sorted_biomes]
    chart_height = max(COVERAGE_CHART_MIN_HEIGHT, len(sorted_biomes) * COVERAGE_BAR_HEIGHT_PER_BIOME)
    figure, axis = plt.subplots(figsize=(COVERAGE_CHART_WIDTH_INCHES, chart_height))
    axis.barh(sorted_biomes, coverage_pcts, color=colors)
    axis.set_xlabel('Coverage (%)', fontsize=10)
    axis.invert_yaxis()
    axis.tick_params(axis='y', labelsize=8)
    figure.tight_layout()
    st.pyplot(figure, use_container_width=True)
    plt.close(figure)


def render_coverage_tab(nearest_entry_grid, entries: list[dict]) -> None:
    st.caption(
        'Coverage % = fraction of the preview map each biome wins. '
        'Stable across seeds — a reliable signal for whether a biome is over- or under-represented. '
        'Patch count and patch size are not shown: they are measured in a fixed-seed 128×128 preview '
        'and do not correspond to patch sizes in any actual Minecraft world.'
    )

    _, biome_colors = build_rgb_image(nearest_entry_grid, entries)
    coverage        = compute_biome_coverage(nearest_entry_grid, entries)
    sorted_biomes   = sorted(coverage, key=coverage.__getitem__, reverse=True)
    coverage_pcts   = [coverage[biome] * 100 for biome in sorted_biomes]

    _render_coverage_chart(sorted_biomes, coverage_pcts, biome_colors)
