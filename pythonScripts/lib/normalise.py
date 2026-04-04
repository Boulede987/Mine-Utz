"""
lib/normalise.py
================
Data normalisation helpers: namespaces, registry names, counts.
"""


def namespace_of(registry_name: str) -> str:
    """Return the namespace portion of a registry name.
    e.g. 'minecraft:stone' -> 'minecraft'
    """
    return registry_name.split(':')[0] if ':' in registry_name else 'unknown'


def namespaces_of(registry_set: set) -> set:
    """Return the set of unique namespaces from a set of registry names."""
    return {namespace_of(name) for name in registry_set}


def add_minecraft_prefix(value: str) -> str:
    """
    Add 'minecraft:' prefix to bare names that have no namespace.
    Preserves tag references (containing '#') and already-namespaced values.
    e.g. 'zombie'          -> 'minecraft:zombie'
         'minecraft:zombie' -> 'minecraft:zombie'
         '#is_forest'      -> '#is_forest'
         'minecraft:#tag'  -> 'minecraft:#tag'
    """
    if ':' in value or value.startswith('#'):
        return value
    return f'minecraft:{value}'


def resolve_count(count_value) -> tuple[int, int]:
    """
    Resolve a Minecraft loot table count field to (min, max).
    Handles:
      1.0                           -> (1, 1)
      {'min': 1.0, 'max': 4.0}     -> (1, 4)
      {'type': '...', 'min': ... }  -> (1, 4)
    """
    if isinstance(count_value, (int, float)):
        value = int(count_value)
        return value, value
    if isinstance(count_value, dict):
        minimum = count_value.get('min', count_value.get('n', 1))
        maximum = count_value.get('max', count_value.get('n', 1))
        return int(minimum), int(maximum)
    return 1, 1
