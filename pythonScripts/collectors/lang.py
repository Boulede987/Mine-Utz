"""
collectors/lang.py
==================
Collects display names from en_us.json lang files across all mod jars.
"""


def make_lang_collector(lang_dict: dict) -> callable:
    """
    Returns a collect_lang(namespace, data) function
    that merges entries into lang_dict.
    Only stores keys that look like registry references (contain '.' or ':').
    """
    def collect_lang(namespace: str, data: dict) -> None:
        for key, value in data.items():
            if '.' in key or ':' in key:
                lang_dict[key] = value

    return collect_lang


def lang_rows_from_dict(lang_dict: dict) -> list[str]:
    """Convert the lang dict to sorted pipe-separated rows for output."""
    return [
        f"{key} | {value}"
        for key, value in sorted(lang_dict.items())
    ]
