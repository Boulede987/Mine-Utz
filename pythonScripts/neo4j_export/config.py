import sys
from pathlib import Path

_scripts_dir = str(Path(__file__).parent.parent)
if _scripts_dir not in sys.path:
    sys.path.insert(0, _scripts_dir)

from lib.paths import LISTS, ROOT  # noqa: E402

NEO4J_URI      = "bolt://localhost:7687"
NEO4J_USER     = "neo4j"
NEO4J_PASSWORD = "password"   # <── change this

BATCH_SIZE     = 500
WEBUI_TEXTURES = ROOT / "webui" / "textures"
