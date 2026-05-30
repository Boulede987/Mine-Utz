from .config import LISTS


def read_ids(filename: str) -> set[str]:
    path = LISTS / filename
    if not path.exists():
        return set()
    return {
        line.strip()
        for line in path.read_text(encoding='utf-8').splitlines()
        if line.strip() and not line.startswith('=') and not line.startswith('#')
    }


def read_pipe(filename: str) -> list[list[str]]:
    path = LISTS / filename
    if not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('=') or line.startswith('#'):
            continue
        rows.append([part.strip() for part in line.split('|')])
    return rows


def parse_id(raw: str) -> tuple[str, str] | None:
    if ':' not in raw:
        return None
    namespace, _, name = raw.partition(':')
    return (namespace.strip(), name.strip()) if namespace and name else None


def node_rows(ids: set[str]) -> list[dict]:
    rows = []
    for node_id in ids:
        parsed = parse_id(node_id)
        if parsed:
            namespace, name = parsed
            rows.append({'id': node_id, 'namespace': namespace, 'name': name})
    return rows


def resolve_biome(raw: str, known: set[str]) -> str | None:
    if ':' in raw:
        return raw if raw in known else None
    candidate = f"minecraft:{raw}"
    if candidate in known:
        return candidate
    matches = [biome for biome in known if biome.endswith(f":{raw}")]
    return matches[0] if len(matches) == 1 else None


def parse_kv(raw: str) -> int:
    try:
        return int(raw.split(':', 1)[1])
    except (IndexError, ValueError):
        return 1


def parse_stat_file(filename: str, fields: list[str]) -> dict[str, dict]:
    stats = {}
    for row in read_pipe(filename):
        if len(row) < 1 + len(fields):
            continue
        try:
            stats[row[0]] = {field: float(row[i + 1]) for i, field in enumerate(fields)}
        except ValueError:
            pass
    return stats


def loot_path_to_block_id(source: str) -> str | None:
    if ':' not in source:
        return None
    namespace, _, path = source.partition(':')
    if path.startswith('blocks/'):
        return f"{namespace}:{path[len('blocks/'):]}"
    return None
