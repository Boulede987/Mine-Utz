"""
worldgen/biome_constraints.py
==============================
Persistence for per-biome settings: weight, zone, temperature range, humidity range.

All settings are stored together in worldgen_data/biome_settings.json so a single
file captures the full biome configuration.  Missing keys fall back to defaults at
load time; the app re-saves on every rerun so the file stays current.
"""

import json
from pathlib import Path

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
