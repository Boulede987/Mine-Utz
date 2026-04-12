"""
sql_gen/10_mc_entity_textures.py
=================================
Generates SQL UPDATE statements to populate Entity.texture_b64.

Runs after 04_mc_entities.py (Entity rows must already exist).
Base64 data is safe to embed directly — it only contains A-Za-z0-9+/= chars.

Source : lists/entity_textures.txt
Output : BDD/Scripts/inserts/10_mc_entity_textures.sql

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/sql_gen/10_mc_entity_textures.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from lib.paths   import LISTS, ROOT
from lib.loaders import load_pipe_file

OUTPUT = ROOT / 'BDD' / 'Scripts' / 'inserts' / '10_mc_entity_textures.sql'

# ── Load ──────────────────────────────────────────────────────────────────────

raw_rows = load_pipe_file(LISTS, 'entity_textures.txt')   # full row strings
print(f'Loaded {len(raw_rows)} entity texture entries.')

# ── Generate UPDATE statements ────────────────────────────────────────────────

updates = []
for row in raw_rows:
    # Split on first ' | ' only — base64 has no '|' but be explicit
    parts = row.split(' | ', 1)
    if len(parts) != 2:
        continue
    entity_id, b64 = parts
    if ':' not in entity_id:
        continue
    ns, name = entity_id.split(':', 1)
    ns   = ns.replace("'", "''")
    name = name.replace("'", "''")
    updates.append(
        f"UPDATE Entity SET texture_b64 = '{b64}'"
        f" WHERE namespace = '{ns}' AND name = '{name}';"
    )

print(f'Generated {len(updates)} UPDATE statements.')

# ── Write ─────────────────────────────────────────────────────────────────────

header = (
    '-- ' + '=' * 50 + '\n'
    '-- 10 - Entity textures (base64-encoded PNG)\n'
    '-- ' + '=' * 50 + '\n\n'
    'USE MineutzDB;\n\n'
)

OUTPUT.parent.mkdir(parents=True, exist_ok=True)
OUTPUT.write_text(header + '\n'.join(updates) + '\n', encoding='utf-8')
print(f'Written -> {OUTPUT}')
