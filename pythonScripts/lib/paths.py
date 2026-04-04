"""
lib/paths.py
============
Central path constants for all scripts.
"""

from pathlib import Path

# Root of the project: mineutze_DEV_INSTANCE/
ROOT = Path(__file__).parent.parent.parent

MODS_DIR = ROOT / 'minecraft' / 'mods'
LISTS    = ROOT / 'lists'
LOG_FILE = ROOT / 'minecraft' / 'logs' / 'kubejs' / 'server.log'

LISTS.mkdir(parents=True, exist_ok=True)
