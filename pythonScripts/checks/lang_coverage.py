"""
checks/lang_coverage.py
========================
Reports what fraction of items and mobs have display names in lang files.
"""


def _lang_keys_for_item(registry_name: str) -> set[str]:
    """Return the expected lang keys for an item registry name."""
    if ':' not in registry_name:
        return set()
    namespace, name = registry_name.split(':', 1)
    name = name.replace('/', '.')
    return {f'item.{namespace}.{name}', f'block.{namespace}.{name}'}


def _lang_key_for_mob(registry_name: str) -> str:
    """Return the expected lang key for a mob registry name."""
    return f'entity.{registry_name.replace(":", ".").replace("/", ".")}'


def check(all_items: set, mobs: set, lang_keys: set) -> dict:
    warnings = []
    info     = []
    ok       = []

    items_with_lang = sum(1 for item in all_items if _lang_keys_for_item(item) & lang_keys)
    mobs_with_lang  = sum(1 for mob  in mobs      if _lang_key_for_mob(mob) in lang_keys)

    info.append((f'{items_with_lang} / {len(all_items)} items have a lang entry', []))
    info.append((f'{mobs_with_lang} / {len(mobs)} mobs have a lang entry', []))

    return {'warnings': warnings, 'info': info, 'ok': ok}
