"""
worldgen/app_coverage.py
=========================
Coverage tab: bar chart and patch fragmentation table.
"""

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import streamlit as st

from worldgen.biome_colour import build_rgb_image, compute_biome_coverage, compute_patch_stats

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


def _render_patch_stats_guide() -> None:
    with st.expander('Column guide'):
        st.markdown(
            '| Column | What it tells you |\n'
            '|---|---|\n'
            '| **Coverage %** | Total map area this biome wins |\n'
            '| **Patches** | Number of disconnected regions |\n'
            '| **Median patch %** | Typical patch size — half are smaller, half bigger |\n'
            '| **Largest patch %** | Biggest single contiguous region |\n'
            '| **Avg patch %** | Mean patch size (Coverage ÷ Patches) |\n'
            '| **Concentration** | Largest patch ÷ Coverage. Near **1.0** = one big blob. Near **0** = many tiny fragments. |'
        )


def _build_patch_stats_rows(
    sorted_biomes: list[str],
    coverage:      dict[str, float],
    patch_stats:   dict[str, dict],
) -> dict:
    coverage_pcts  = [round(coverage[biome] * 100, 2) for biome in sorted_biomes]
    patch_counts   = [patch_stats.get(biome, {}).get('patch_count', 0) for biome in sorted_biomes]
    median_pcts    = [round(patch_stats.get(biome, {}).get('median_patch_pct', 0.0), 3) for biome in sorted_biomes]
    largest_pcts   = [round(patch_stats.get(biome, {}).get('largest_patch_pct', 0.0), 2) for biome in sorted_biomes]
    avg_pcts       = [
        round(cov / patches, 3) if patches > 0 else 0.0
        for cov, patches in zip(coverage_pcts, patch_counts)
    ]
    concentrations = [
        round(largest / cov, 2) if cov > 0 else 0.0
        for largest, cov in zip(largest_pcts, coverage_pcts)
    ]
    return {
        'Biome':           sorted_biomes,
        'Coverage %':      coverage_pcts,
        'Patches':         patch_counts,
        'Median patch %':  median_pcts,
        'Largest patch %': largest_pcts,
        'Avg patch %':     avg_pcts,
        'Concentration':   concentrations,
    }


def _render_patch_stats_table(
    sorted_biomes: list[str],
    coverage:      dict[str, float],
    patch_stats:   dict[str, dict],
) -> None:
    st.subheader('Patch Fragmentation')
    _render_patch_stats_guide()
    st.dataframe(
        _build_patch_stats_rows(sorted_biomes, coverage, patch_stats),
        use_container_width=True, hide_index=True,
    )


def render_coverage_tab(nearest_entry_grid, entries: list[dict]) -> None:
    _, biome_colors = build_rgb_image(nearest_entry_grid, entries)
    coverage        = compute_biome_coverage(nearest_entry_grid, entries)
    patch_stats     = compute_patch_stats(nearest_entry_grid, entries)

    sorted_biomes = sorted(coverage, key=coverage.__getitem__, reverse=True)
    coverage_pcts = [coverage[biome] * 100 for biome in sorted_biomes]

    _render_coverage_chart(sorted_biomes, coverage_pcts, biome_colors)
    st.divider()
    _render_patch_stats_table(sorted_biomes, coverage, patch_stats)
