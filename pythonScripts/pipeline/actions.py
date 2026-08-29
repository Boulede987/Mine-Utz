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
    EXTRACT_NOISE, SCAN_TERRABLENDER, PARSE_BOP_NOISE, PARSE_BWG_NOISE, PARSE_NULLSCAPE, EXTRACT_PREHISTORIC_EXPANSION,
    CREATE_MINEUTZ_OCEANS, COPY_BIOME,
    MERGE_BIOME_PARAMETERS, COMPLETE_BIOME_PLANNER, GENERATE_FILTER_REGION,
    GENERATE_DIMENSION_JSONS,
    GENERATE_STRUCTURE_PLANNER,
    GENERATE_STRUCTURE_TAGS,
    GENERATE_BIOME_ADJACENCY_PLANNER,
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
    section("STEP: Export to Neo4j")
    print("  [REMINDER]  Make sure Neo4j Desktop is running and the database is started.")
    pause("Press Enter when Neo4j is ready...")
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

def step_scan_terrablender():
    return run_script(SCAN_TERRABLENDER, "Auto-scan jars for TerraBlender nether/end biomes (new mods)")

def step_bop_noise():
    return run_script(PARSE_BOP_NOISE, "BiomesOPlenty noise -> worldgen_data/biomesoplenty.json")

def step_bwg_noise():
    return run_script(PARSE_BWG_NOISE, "Oh The Biomes We've Gone noise -> worldgen_data/biomeswevegone.json")

def step_nullscape():
    return run_script(PARSE_NULLSCAPE, "Nullscape End biomes -> worldgen_data/nullscape.json")

def step_prehistoric_expansion():
    return run_script(EXTRACT_PREHISTORIC_EXPANSION, "Shineals Prehistoric Expansion -> worldgen_data/shineals_prehistoric_expansion.json")

def step_merge_biome_parameters():
    return run_script(MERGE_BIOME_PARAMETERS, "Merge biome params -> worldgen_data/merged/")

def step_complete_biome_planner():
    return run_script(COMPLETE_BIOME_PLANNER, "Complete dimension_biome_planner.xlsx with missing biomes")

def step_create_mineutz_oceans():
    return run_script(CREATE_MINEUTZ_OCEANS, "Create mineutz: ocean biome copies (run once per MC version)")

def step_copy_biome():
    return run_script(COPY_BIOME, "Copy any biome into mineutz: namespace (interactive)")

def step_generate_dimension_jsons():
    return run_script(GENERATE_DIMENSION_JSONS, "Generate custom dimension JSONs from planner")

def step_generate_structure_planner():
    return run_script(GENERATE_STRUCTURE_PLANNER, "Generate structure_planner.xlsx")

def step_generate_structure_tags():
    return run_script(GENERATE_STRUCTURE_TAGS, "Generate structure biome tag overrides from planner")

def step_generate_biome_adjacency_planner():
    return run_script(GENERATE_BIOME_ADJACENCY_PLANNER, "Generate biome_adjency_planner.xlsx")

def step_generate_filter_region():
    return run_script(GENERATE_FILTER_REGION, "Generate Nether/End TerraBlender Java (Overworld is hand-maintained BiomeConfigRegistry.java now)")


def step_worldgen_sequence():
    """End-to-end worldgen data refresh: dump → extract → parse → merge → planner →
    Nether/End Java. Overworld placement is no longer generated from this data at all --
    it's the hand-maintained com.mineutze.worldgen.biomesource.BiomeConfigRegistry Java
    class in companion-mods/mineutze-worldgen. This sequence still matters for Nether/End
    and for keeping worldgen_data/merged/ current for whatever authors that config by
    hand."""
    section("STEP: World gen sequence")
    print("  Before starting:")
    print("    1. Load your world and visit the Nether (biome sampling requires it).")
    print("    2. Run  /dump_all_noise  in-game and wait for the success message.")
    pause("  Press Enter when /dump_all_noise is complete...")

    failures = []

    if not run_script(EXTRACT_NOISE, "Extract noise → worldgen_data/"):
        failures.append("noise extraction")
    if not run_script(SCAN_TERRABLENDER, "Auto-scan jars for TerraBlender nether/end biomes"):
        failures.append("terrablender scan")
    if not run_script(PARSE_BOP_NOISE, "BiomesOPlenty noise params"):
        failures.append("BoP noise")
    if not run_script(PARSE_BWG_NOISE, "Oh The Biomes We've Gone noise params"):
        failures.append("BWG noise")
    if not run_script(PARSE_NULLSCAPE, "Nullscape End biomes"):
        failures.append("Nullscape")
    if not run_script(EXTRACT_PREHISTORIC_EXPANSION, "Shineals Prehistoric Expansion"):
        failures.append("Prehistoric Expansion")
    if not run_script(MERGE_BIOME_PARAMETERS, "Merge biome params → worldgen_data/merged/"):
        failures.append("merge")
    if not run_script(COMPLETE_BIOME_PLANNER, "Complete dimension_biome_planner.xlsx"):
        failures.append("complete planner")

    if failures:
        print(f"\n  !! Failed steps: {', '.join(failures)}")
        print("     Fix the issues above before continuing to filter region generation.")
        return False

    print("\n  ── Review ──────────────────────────────────────────────────────────")
    print("  Open dimension_biome_planner.xlsx and mark ✓ or ✗ for any new biomes.")
    print("  CLOSE Excel completely before pressing Enter.")
    pause("  Press Enter when xlsx is saved and closed...")

    if not run_script(GENERATE_FILTER_REGION, "Generate Nether/End TerraBlender Java files"):
        failures.append("filter region")
        print("  !! Nether/End Java generation failed.")
        return False

    print("\n  Worldgen sequence complete.")
    print("  Rebuild the companion mod (deployMod in IntelliJ) to apply Nether/End changes.")
    print("  Overworld placement changes go in BiomeConfigRegistry.java directly --")
    print("  edit it, then deployMod the same way.")
    return True

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
    ("17s","World gen sequence  (dump → parse → merge → planner → Nether/End Java)", step_worldgen_sequence),
    (None, "World gen — individual steps",              None),
    ("17", "All noise params -> worldgen_data/  (run /dump_all_noise first; visit nether beforehand)", step_worldgen_noise),
    ("17b","Auto-scan jars: nether/end biomes from new mods (skips bop/bwg)", step_scan_terrablender),
    ("18", "BiomesOPlenty noise params",                step_bop_noise),
    ("19", "Oh The Biomes We've Gone noise params",     step_bwg_noise),
    ("19b","Nullscape End biomes",                     step_nullscape),
    ("20", "Shineals Prehistoric Expansion biomes",    step_prehistoric_expansion),
    ("20b","Create mineutz: ocean copies (once per MC version)", step_create_mineutz_oceans),
    ("20c","Copy any biome into mineutz: namespace",            step_copy_biome),
    ("21", "Merge biome params -> worldgen_data/merged/", step_merge_biome_parameters),
    ("22", "Complete dimension_biome_planner.xlsx",    step_complete_biome_planner),
    ("23", "Generate Nether/End Java (Overworld: hand-edit BiomeConfigRegistry.java)", step_generate_filter_region),
    ("24", "Generate custom dimension JSONs",          step_generate_dimension_jsons),
    ("25", "Generate structure_planner.xlsx",          step_generate_structure_planner),
    ("26", "Generate structure biome tag overrides",   step_generate_structure_tags),
    ("27", "Generate biome_adjency_planner.xlsx",      step_generate_biome_adjacency_planner),
]
