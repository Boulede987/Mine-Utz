"""
collectors/loot.py
==================
Collects loot table data from mod jar JSON files.
"""

from lib.normalise import resolve_count

# Map JSON top-level 'type' field to bucket name
TYPE_TO_BUCKET = {
    'minecraft:entity':    'entities',
    'minecraft:chest':     'chests',
    'minecraft:block':     'blocks',
    'minecraft:gift':      'other',
    'minecraft:fishing':   'other',
    'minecraft:shearing':  'other',
    'minecraft:equipment': 'other',
    'minecraft:generic':   'other',
}

# Fallback: first folder segment after loot_table(s)/
FOLDER_TO_BUCKET = {
    'entities': 'entities',
    'chests':   'chests',
    'blocks':   'blocks',
}


def _resolve_bucket(loot_type_field: str, folder_hint: str) -> str:
    """Determine which bucket a loot table belongs to."""
    if loot_type_field:
        bucket = TYPE_TO_BUCKET.get(loot_type_field.lower())
        if bucket:
            return bucket
    return FOLDER_TO_BUCKET.get(folder_hint, 'other')


def _process_loot_entries(
    entries: list,
    source_id: str,
    bucket: list,
    reference_rows: list,
) -> None:
    for entry in entries:
        entry_type = entry.get('type', '')

        if 'loot_table' in entry_type:
            reference_target = entry.get('value', '')
            if reference_target:
                reference_rows.append(f"{source_id} | {reference_target}")
            continue

        if entry.get('children'):
            _process_loot_entries(entry['children'], source_id, bucket, reference_rows)
            continue

        if not entry_type.endswith('item'):
            continue

        item_id = entry.get('name', '')
        if not item_id:
            continue

        min_count = max_count = 1
        weight    = entry.get('weight', 1)

        for function in entry.get('functions', []):
            if 'set_count' in function.get('function', ''):
                min_count, max_count = resolve_count(function.get('count', 1))

        bucket.append(
            f"{source_id} | {item_id} | min:{min_count} | max:{max_count} | weight:{weight}"
        )


def _select_bucket(bucket_name: str, entity_rows, chest_rows, block_rows, other_rows) -> list:
    if bucket_name == 'entities': return entity_rows
    if bucket_name == 'chests':   return chest_rows
    if bucket_name == 'blocks':   return block_rows
    return other_rows


def make_loot_collector(
    entity_rows: list,
    chest_rows: list,
    block_rows: list,
    other_rows: list,
    reference_rows: list,
) -> callable:
    """
    Returns a collect_loot(namespace, relative_path, data) function
    that appends pipe-separated rows to the appropriate bucket list.

    relative_path is everything after loot_table(s)/
    """
    def collect_loot(namespace: str, relative_path: str, data: dict) -> None:
        folder      = relative_path.split('/')[0]
        source_id   = f"{namespace}:{relative_path.removesuffix('.json')}"
        bucket_name = _resolve_bucket(data.get('type', ''), folder)
        bucket      = _select_bucket(bucket_name, entity_rows, chest_rows, block_rows, other_rows)

        for pool in data.get('pools', []):
            _process_loot_entries(pool.get('entries', []), source_id, bucket, reference_rows)

    return collect_loot
