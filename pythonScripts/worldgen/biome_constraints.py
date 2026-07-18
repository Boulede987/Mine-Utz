"""
worldgen/biome_constraints.py
==============================
Persistence for per-biome settings: weight, zone, temperature range, humidity range.

All settings are stored together in worldgen_data/biome_settings.json so a single
file captures the full biome configuration.  Missing keys fall back to defaults at
load time; the app re-saves on every rerun so the file stays current.

resolve_biome_constraints/resolve_all_constraints hold the single copy of the
"saved settings > actual biome defaults > full axis range" priority rule.  Both
app.py (sidebar widget defaults) and entry_pipeline.py (FilterRegion.java codegen)
call these so the live preview and the generated Java can never drift apart.
"""

import json
from pathlib import Path

from worldgen.app_data import WEIGHT_DEFAULT
from worldgen.biome_grid import (
    EROSION_MAX, EROSION_MIN,
    HUMIDITY_MAX, HUMIDITY_MIN,
    SURFACE_DEPTH,
    TEMPERATURE_MAX, TEMPERATURE_MIN,
    WEIRDNESS_MAX, WEIRDNESS_MIN,
    default_zone,
)

BIOME_SETTINGS_FILE = Path('worldgen_data') / 'biome_settings.json'


def load_settings() -> dict[str, dict]:
    """Returns all saved per-biome settings, or an empty dict if the file does not exist."""
    if not BIOME_SETTINGS_FILE.exists():
        return {}
    return json.loads(BIOME_SETTINGS_FILE.read_text(encoding='utf-8'))


def save_settings(settings: dict[str, dict]) -> None:
    BIOME_SETTINGS_FILE.parent.mkdir(parents=True, exist_ok=True)
    BIOME_SETTINGS_FILE.write_text(
        json.dumps(settings, indent=2, ensure_ascii=False),
        encoding='utf-8',
    )


def resolve_biome_constraints(biome_id: str, saved: dict, defaults: dict) -> dict:
    """Resolved constraint dict for one biome: saved settings > actual biome data defaults > full axis range."""
    return {
        'zone':           saved.get('zone', default_zone(biome_id)),
        'temperature_lo': float(saved.get('temperature_lo', defaults.get('temperature_lo', TEMPERATURE_MIN))),
        'temperature_hi': float(saved.get('temperature_hi', defaults.get('temperature_hi', TEMPERATURE_MAX))),
        'humidity_lo':    float(saved.get('humidity_lo',    defaults.get('humidity_lo',    HUMIDITY_MIN))),
        'humidity_hi':    float(saved.get('humidity_hi',    defaults.get('humidity_hi',    HUMIDITY_MAX))),
        'erosion_lo':     float(saved.get('erosion_lo',     defaults.get('erosion_lo',     EROSION_MIN))),
        'erosion_hi':     float(saved.get('erosion_hi',     defaults.get('erosion_hi',     EROSION_MAX))),
        'depth_lo':       float(saved.get('depth_lo',       defaults.get('depth_lo',       SURFACE_DEPTH))),
        'depth_hi':       float(saved.get('depth_hi',       defaults.get('depth_hi',       SURFACE_DEPTH))),
        'weirdness_lo':   float(saved.get('weirdness_lo',   defaults.get('weirdness_lo',   WEIRDNESS_MIN))),
        'weirdness_hi':   float(saved.get('weirdness_hi',   defaults.get('weirdness_hi',   WEIRDNESS_MAX))),
    }


def resolve_all_constraints(
    planner_biomes: list[str],
    saved_settings: dict[str, dict],
    biome_defaults: dict[str, dict],
) -> tuple[dict[str, float], dict[str, dict]]:
    """Returns (weights, constraints) for every planner biome, same priority as the app sidebar."""
    weights:     dict[str, float] = {}
    constraints: dict[str, dict]  = {}
    for biome_id in planner_biomes:
        saved                 = saved_settings.get(biome_id, {})
        weights[biome_id]     = saved.get('weight', WEIGHT_DEFAULT)
        constraints[biome_id] = resolve_biome_constraints(biome_id, saved, biome_defaults.get(biome_id, {}))
    return weights, constraints
