"""
sql_gen/01_mc_bootstrap.py
===========================
Generates SQL inserts for the core bootstrap tables:
  - Rarity     (vanilla tiers)
  - Licence    (Mojang EULA)
  - Author     (Mojang Studios)
  - Mod        (minecraft 1.21.1)
  - Source     (minecraft.net)
  - DeveloppedBy

All data here is hardcoded — it does not depend on the lists/ files.

Output: BDD/Scripts/inserts/01_mc_bootstrap.sql

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/sql_gen/01_mc_bootstrap.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from sql_gen.utils import insert_rows, write_sql
from lib.paths import ROOT

OUTPUT = ROOT / 'BDD' / 'Scripts' / 'inserts' / '01_mc_bootstrap.sql'

# ── Rarity ───────────────────────────────────────────────────────────────────
# Vanilla Minecraft rarity tiers in ascending order.
# IDs are explicit so downstream INSERT scripts can reference them by number.
RARITY_ROWS = [
    (1, 'COMMON'),
    (2, 'UNCOMMON'),
    (3, 'RARE'),
    (4, 'EPIC'),
]

# ── Licence ───────────────────────────────────────────────────────────────────
LICENCE_ROWS = [
    (1, 'Mojang EULA'),
]

# ── Author ────────────────────────────────────────────────────────────────────
AUTHOR_ROWS = [
    (1, 'Mojang Studios'),
]

# ── Mod ───────────────────────────────────────────────────────────────────────
MOD_ROWS = [
    ('minecraft', 'Minecraft', '1.21.1', 1),   # licence_id = 1 (Mojang EULA)
]

# ── Source ────────────────────────────────────────────────────────────────────
SOURCE_ROWS = [
    ('minecraft', 'Official', 'https://minecraft.net'),
]

# ── DeveloppedBy ─────────────────────────────────────────────────────────────
DEVELOPED_BY_ROWS = [
    ('minecraft', 1),   # author_id = 1 (Mojang Studios)
]

# ── Assemble and write ────────────────────────────────────────────────────────

write_sql(OUTPUT, '01 - Minecraft bootstrap (Rarity, Licence, Author, Mod)', [
    ('Rarity tiers',
     insert_rows('Rarity', ['id', 'tier'], RARITY_ROWS)),

    ('Licence',
     insert_rows('Licence', ['id', 'name'], LICENCE_ROWS)),

    ('Author',
     insert_rows('Author', ['id', 'name'], AUTHOR_ROWS)),

    ('Mod — vanilla Minecraft 1.21.1',
     insert_rows('`Mod`', ['namespace', 'display_name', 'version', 'licence_id'], MOD_ROWS)),

    ('Source',
     insert_rows('Source', ['namespace', 'site', 'url'], SOURCE_ROWS)),

    ('DeveloppedBy',
     insert_rows('DeveloppedBy', ['namespace', 'author_id'], DEVELOPED_BY_ROWS)),
])
