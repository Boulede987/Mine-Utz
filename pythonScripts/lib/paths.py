"""
lib/paths.py
============
Central path constants for all scripts.
"""

from pathlib import Path

# Root of the project: mineutze_DEV_INSTANCE/
ROOT = Path(__file__).parent.parent.parent

MODS_DIR     = ROOT / 'minecraft' / 'mods'
LISTS        = ROOT / 'lists'
LOG_FILE     = ROOT / 'minecraft' / 'logs' / 'kubejs' / 'server.log'
LATEST_LOG   = ROOT / 'minecraft' / 'logs' / 'latest.log'
SQL_INSERTS  = ROOT / 'BDD' / 'Scripts' / 'inserts'
VANILLA_JAR  = (
    Path.home()
    / 'AppData' / 'Roaming' / 'PrismLauncher'
    / 'libraries' / 'com' / 'mojang' / 'minecraft'
    / '1.21.1' / 'minecraft-1.21.1-client.jar'
)

LISTS.mkdir(parents=True, exist_ok=True)
