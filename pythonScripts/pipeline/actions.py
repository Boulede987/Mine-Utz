import subprocess
import sys
import webbrowser
from pathlib import Path

from .display import section, pause

ROOT        = Path(__file__).parent.parent.parent
SCRIPTS     = ROOT / "pythonScripts"
PYTHON_EXE  = sys.executable


def run_script(script: str, label: str) -> bool:
    print(f"\n  >> {label}")
    result = subprocess.run([PYTHON_EXE, str(SCRIPTS / script)])
    if result.returncode != 0:
        print(f"  !! FAILED (exit {result.returncode})")
        return False
    return True


# ── individual jar extractor steps ───────────────────────────

def step_from_jars():
    run_script("extractors/extract_from_jars.py", "Jar scanner (recipes, loot, biomes, mobs...)")

def step_structure_loot():
    run_script("extractors/extract_structure_loottables.py", "Structure loot tables (.nbt in jars)")

def step_biome_tags():
    run_script("extractors/extract_biome_tags.py", "Biome tags (vanilla + mod jars)")

def step_biome_blocks():
    run_script("extractors/extract_biome_blocks.py", "Biome blocks")

def step_item_tags():
    run_script("extractors/extract_item_tags.py", "Item tags")

def step_entity_textures():
    run_script("extractors/extract_textures.py", "Entity textures")

def step_item_textures():
    run_script("extractors/extract_item_textures.py", "Item textures")

def step_dimension_biomes():
    run_script("extractors/extract_dimension_biomes.py", "Dimension biomes")


# ── compound steps ────────────────────────────────────────────

_JAR_STEPS = [
    step_from_jars,
    step_structure_loot,
    step_biome_tags,
    step_biome_blocks,
    step_item_tags,
    step_entity_textures,
    step_item_textures,
    step_dimension_biomes,
]

def step_jar_extract():
    section("STEP: Extract from jars")
    return all(s() for s in _JAR_STEPS)

def step_ingame_dump():
    section("STEP: In-game dump")
    print("  Load your world and make sure you have op (level 2+).")
    pause("Run  /dump_all  in-game, then wait for the success message.")

def step_log_extract():
    section("STEP: Extract from log")
    return run_script("extractors/extract_from_log.py", "Parse kubejs server.log")

def step_generate_sql():
    section("STEP: Generate SQL")
    return run_script("generate_sql.py", "Generate all INSERT scripts")

def step_consistency():
    section("STEP: Consistency check")
    return run_script("check_consistency.py", "Run integrity checks")

def step_neo4j_export():
    return run_script("export_to_neo4j.py", "Export to Neo4j")

def step_web_explorer():
    section("STEP: Web Explorer")
    print("  [REMINDER]  Make sure Neo4j Desktop is running and the database is started.")
    print("              The web explorer connects to bolt://localhost:7687 by default.")
    input("  Press Enter when Neo4j is ready...")
    webui = ROOT / "webui"
    port  = 8080
    print(f"  Serving {webui} on http://localhost:{port}")
    print("  Press Ctrl+C to stop the server.")
    webbrowser.open(f"http://localhost:{port}")
    subprocess.run([PYTHON_EXE, "-m", "http.server", str(port), "--directory", str(webui)])

def step_worldgen_noise():
    section("STEP: World gen -- overworld noise")
    print("  Load your world and make sure you have op (level 2+).")
    pause("Run  /dump_all_noise  in-game, then wait for the success message.")
    return run_script("worldgen/extract_noise_to_json.py", "Extract noise -> worldgen_data/")

def step_bop_noise():
    return run_script("worldgen/parse_bop_source.py", "BiomesOPlenty noise -> worldgen_data/biomesoplenty.json")

def step_bwg_noise():
    return run_script("worldgen/parse_bwg_source.py", "Oh The Biomes We've Gone noise -> worldgen_data/biomeswevegone.json")

def step_full_pipeline():
    section("FULL PIPELINE")
    failures = []

    if not step_jar_extract():
        failures.append("jar extraction")

    step_ingame_dump()

    if not step_log_extract():
        failures.append("log extraction")

    if not step_generate_sql():
        failures.append("SQL generation")

    if not step_consistency():
        failures.append("consistency check")

    section()
    if failures:
        print(f"  Pipeline done with failures: {', '.join(failures)}")
    else:
        print("  Pipeline complete.")


# ── step registry ─────────────────────────────────────────────
# Each entry: (key, label, callable)
# key=None → section header (not selectable)

STEPS = [
    (None, "Full",                                      None),
    ( "1", "Full pipeline (all steps in order)",        step_full_pipeline),
    (None, "Extraction",                                None),
    ( "2", "All jar extractors",                        step_jar_extract),
    ( "3", "In-game /dump_all  (pause + prompt)",       step_ingame_dump),
    ( "4", "Extract from log   (log must be fresh)",    step_log_extract),
    (None, "Individual jar extractors",                 None),
    ( "5", "From jars  (recipes, loot, spawns...)",     step_from_jars),
    ( "6", "Structure loot tables",                     step_structure_loot),
    ( "7", "Biome tags",                                step_biome_tags),
    ( "8", "Biome blocks",                              step_biome_blocks),
    ( "9", "Item tags",                                 step_item_tags),
    ("10", "Entity textures",                           step_entity_textures),
    ("11", "Item textures",                             step_item_textures),
    ("12", "Dimension biomes",                          step_dimension_biomes),
    (None, "Database",                                  None),
    ("13", "Generate SQL",                              step_generate_sql),
    ("14", "Consistency check",                         step_consistency),
    (None, "Graph",                                     None),
    ("15", "Export to Neo4j",                           step_neo4j_export),
    ("16", "Launch web explorer  (opens browser)",      step_web_explorer),
    (None, "World gen",                                 None),
    ("17", "All noise params -> worldgen_data/  (run /dump_all_noise first)", step_worldgen_noise),
    ("18", "BiomesOPlenty noise params",                step_bop_noise),
    ("19", "Oh The Biomes We've Gone noise params",     step_bwg_noise),
]
