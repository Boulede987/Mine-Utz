"""
sql_gen/04_mc_entities.py
==========================
Generates SQL inserts for the Entity table — vanilla Minecraft 1.21.1.

Sources : lists/mobs.txt         — namespace:name, one per line
          lists/entity_stats.txt — health/armor/attack/speed/knockbackRes/hitbox
                                   from /dump_entity_stats  (optional)
Filter  : minecraft: namespace only

Still NULL after this: behavior, type
Loot table links are in LootTableEntity (06_mc_loot.py).

Output: BDD/Scripts/inserts/04_mc_entities.sql

Usage
-----
  cd /path/to/mineutze_DEV_INSTANCE
  python pythonScripts/sql_gen/04_mc_entities.py
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from sql_gen.utils import insert_rows, write_sql
from lib.paths import LISTS, SQL_INSERTS
from lib.loaders import load_registry, load_pipe_file

OUTPUT = SQL_INSERTS / '04_mc_entities.sql'


_CAT_TO_BEHAVIOR: dict[str, tuple[str, str]] = {
    'monster':                    ('hostile', 'monster'),
    'creature':                   ('passive', 'animal'),
    'ambient':                    ('passive', 'ambient'),
    'axolotls':                   ('passive', 'animal'),
    'water_creature':             ('passive', 'animal'),
    'water_ambient':              ('passive', 'ambient'),
    'underground_water_creature': ('passive', 'animal'),
}


def _load_entity_behavior() -> dict[str, tuple[str | None, str | None]]:
    """Parse entity_behavior.txt -> {entity_id: (behavior, type)}."""
    result: dict[str, tuple] = {}
    for line in load_pipe_file(LISTS, 'entity_behavior.txt'):
        parts = [p.strip() for p in line.split('|')]
        if len(parts) < 2:
            continue
        entity_id, category = parts[0], parts[1]
        result[entity_id] = _CAT_TO_BEHAVIOR.get(category, (None, None))
    return result


def _load_entity_stats() -> dict[str, dict]:
    """Parse entity_stats.txt into {entity_id: {stat: value}}."""
    result: dict[str, dict] = {}
    def _f(v):
        if v in ('null', 'None', ''):
            return None
        try:
            return float(v)
        except ValueError:
            return None
    for line in load_pipe_file(LISTS, 'entity_stats.txt'):
        parts = [p.strip() for p in line.split('|')]
        if len(parts) < 8:
            continue
        result[parts[0]] = {
            'healthPoints':        _f(parts[1]),
            'armorPoints':         _f(parts[2]),
            'attackDamage':        _f(parts[3]),
            'speed':               _f(parts[4]),
            'knockbackResistance': _f(parts[5]),
            'width':               _f(parts[6]),
            'height':              _f(parts[7]),
        }
    return result


def _build_entity_rows(mc_mobs: list[str], entity_stats: dict, entity_behavior: dict) -> list[tuple]:
    rows = []
    for mob_id in mc_mobs:
        ns, _, name = mob_id.partition(':')
        s = entity_stats.get(mob_id, {})
        w, h = s.get('width'), s.get('height')
        hitbox = f'{w:.4g}x{h:.4g}' if w is not None and h is not None else None
        behavior, typ = entity_behavior.get(mob_id, (None, None))
        rows.append((ns, name,
                     behavior, typ,
                     s.get('healthPoints'),
                     s.get('armorPoints'),
                     s.get('attackDamage'),
                     s.get('speed'),
                     s.get('knockbackResistance'),
                     hitbox))
    return rows


def main() -> None:
    all_mobs = load_registry(LISTS, 'mobs.txt')
    mc_mobs  = sorted(e for e in all_mobs if e.startswith('minecraft:'))
    entity_stats    = _load_entity_stats()
    entity_behavior = _load_entity_behavior()
    print(f'Found {len(mc_mobs)} vanilla entities.  ({len(entity_stats)} with stats, {len(entity_behavior)} with behavior)')

    write_sql(OUTPUT, '04 - Minecraft entities', [
        ('Entity — vanilla 1.21.1  (behavior/type from biome spawners; combat stats from /dump_entity_stats)',
         insert_rows(
             'Entity',
             ['namespace', 'name', 'behavior', 'type',
              'healthPoints', 'armorPoints', 'attackDamage',
              'speed', 'knockbackResistance',
              'hitboxSize'],
             _build_entity_rows(mc_mobs, entity_stats, entity_behavior),
         )),
    ])


main()
