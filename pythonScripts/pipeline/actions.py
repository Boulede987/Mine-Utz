import subprocess
import sys
import webbrowser
from pathlib import Path

from .display import section, pause
from .paths import (
    WEBUI,
    EXTRACT_FROM_JARS, EXTRACT_STRUCTURE_LOOT, EXTRACT_BIOME_TAGS,
    EXTRACT_BIOME_BLOCKS, EXTRACT_ITEM_TAGS, EXTRACT_ENTITY_TEXTURES,
    EXTRACT_ITEM_TEXTURES, EXTRACT_DIMENSION_BIOMES, EXTRACT_FROM_LOG,
    GENERATE_SQL, CHECK_CONSISTENCY, EXPORT_TO_NEO4J,
    EXTRACT_NOISE, PARSE_BOP_NOISE, PARSE_BWG_NOISE,
)

PYTHON_EXE  = sys.executable
WEB_PORT    = 8080


def run_script(script: Path, label: str) -> bool:
    print(f"\n  >> {label}")
    result = subprocess.run([PYTHON_EXE, str(script)])
    if result.returncode != 0:
        print(f"  !! FAILED (exit {result.returncode})")
        return False
    return True


def step_from_jars():
    run_script(EXTRACT_FROM_JARS, "Jar scanner (recipes, loot, biomes, mobs...)")

def step_structure_loot():
    run_script(EXTRACT_STRUCTURE_LOOT, "Structure loot tables (.nbt in jars)")

def step_biome_tags():
    run_script(EXTRACT_BIOME_TAGS, "Biome tags (vanilla + mod jars)")

def step_biome_blocks():
    run_script(EXTRACT_BIOME_BLOCKS, "Biome blocks")

def step_item_tags():
    run_script(EXTRACT_ITEM_TAGS, "Item tags")

def step_entity_textures():
    run_script(EXTRACT_ENTITY_TEXTURES, "Entity textures")

def step_item_textures():
    run_script(EXTRACT_ITEM_TEXTURES, "Item textures")

def step_dimension_biomes():
    run_script(EXTRACT_DIMENSION_BIOMES, "Dimension biomes")


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
    return run_script(EXTRACT_FROM_LOG, "Parse kubejs server.log")

def step_generate_sql():
    section("STEP: Generate SQL")
    return run_script(GENERATE_SQL, "Generate all INSERT scripts")

def step_consistency():
    section("STEP: Consistency check")
    return run_script(CHECK_CONSISTENCY, "Run integrity checks")

def step_neo4j_export():
    return run_script(EXPORT_TO_NEO4J, "Export to Neo4j")

def step_web_explorer():
    section("STEP: Web Explorer")
    print("  [REMINDER]  Make sure Neo4j Desktop is running and the database is started.")
    print("              The web explorer connects to bolt://localhost:7687 by default.")
    input("  Press Enter when Neo4j is ready...")
    print(f"  Serving {WEBUI} on http://localhost:{WEB_PORT}")
    print("  Press Ctrl+C to stop the server.")
    webbrowser.open(f"http://localhost:{WEB_PORT}")
    subprocess.run([PYTHON_EXE, "-m", "http.server", str(WEB_PORT), "--directory", str(WEBUI)])

def step_worldgen_noise():
    section("STEP: World gen -- overworld noise")
    print("  Load your world and make sure you have op (level 2+).")
    pause("Run  /dump_all_noise  in-game, then wait for the success message.")
    return run_script(EXTRACT_NOISE, "Extract noise -> worldgen_data/")

def step_bop_noise():
    return run_script(PARSE_BOP_NOISE, "BiomesOPlenty noise -> worldgen_data/biomesoplenty.json")

def step_bwg_noise():
    return run_script(PARSE_BWG_NOISE, "Oh The Biomes We've Gone noise -> worldgen_data/biomeswevegone.json")

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


# Each entry: (key, label, callable) — key=None means section header
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
