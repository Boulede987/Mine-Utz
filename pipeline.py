"""
pipeline.py — Mineutze data pipeline runner.
Run from the repo root: python pipeline.py
"""

import subprocess
import sys
from pathlib import Path

ROOT    = Path(__file__).parent
SCRIPTS = ROOT / 'pythonScripts'
PY      = sys.executable


def run(script: str, label: str | None = None) -> bool:
    name = label or script
    print(f"\n  >> {name}")
    result = subprocess.run([PY, str(SCRIPTS / script)])
    if result.returncode != 0:
        print(f"  !! FAILED (exit {result.returncode})")
        return False
    return True


def pause(instruction: str):
    print(f"\n  [ACTION REQUIRED]  {instruction}")
    input("  Press Enter when done...")


def hr(title: str = ""):
    width = 60
    if title:
        pad = (width - len(title) - 2) // 2
        print(f"\n{'=' * pad} {title} {'=' * (width - pad - len(title) - 2)}")
    else:
        print("\n" + "=" * width)


# ── step functions ────────────────────────────────────────────

JAR_EXTRACTORS = [
    ("extract_from_jars.py",           "Jar scanner (recipes, loot, biomes, mobs…)"),
    ("extract_structure_loottables.py", "Structure loot tables (.nbt in jars)"),
    ("extract_biome_tags.py",           "Biome tags (vanilla + mod jars)"),
    ("extract_biome_blocks.py",         "Biome blocks"),
    ("extract_item_tags.py",            "Item tags"),
    ("extract_textures.py",             "Textures"),
    ("extract_dimension_biomes.py",     "Dimension biomes"),
]


def step_jar_extract() -> bool:
    hr("STEP: Extract from jars")
    ok = True
    for script, label in JAR_EXTRACTORS:
        if not run(script, label):
            ok = False
    return ok


def step_ingame_dump():
    hr("STEP: In-game dump")
    print("  Load your world and make sure you have op (level 2+).")
    pause("Run  /dump_all  in-game, then wait for the success message.")


def step_log_extract() -> bool:
    hr("STEP: Extract from log")
    return run("extract_from_log.py", "Parse kubejs server.log")


def step_generate_sql() -> bool:
    hr("STEP: Generate SQL")
    return run("generate_sql.py", "Generate all INSERT scripts")


def step_consistency() -> bool:
    hr("STEP: Consistency check")
    return run("check_consistency.py", "Run integrity checks")


def step_full_pipeline():
    hr("FULL PIPELINE")
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

    hr()
    if failures:
        print(f"  Pipeline done with failures: {', '.join(failures)}")
    else:
        print("  Pipeline complete.")


# ── menu ─────────────────────────────────────────────────────

MENU = """\

  ╔══════════════════════════════════════╗
  ║      Mineutze Pipeline Runner        ║
  ╚══════════════════════════════════════╝

  ── Full ────────────────────────────────
   1  Full pipeline (all steps in order)

  ── Extraction ──────────────────────────
   2  All jar extractors
   3  In-game /dump_all  (pause + prompt)
   4  Extract from log   (log must be fresh)

  ── Individual jar extractors ───────────
   5  From jars  (recipes, loot, spawns…)
   6  Structure loot tables
   7  Biome tags
   8  Biome blocks
   9  Item tags
  10  Textures
  11  Dimension biomes

  ── Database ────────────────────────────
  12  Generate SQL
  13  Consistency check

  ── Graph ───────────────────────────────
  14  Export to Neo4j

   0  Exit
"""

ACTIONS = {
    "1":  step_full_pipeline,
    "2":  step_jar_extract,
    "3":  step_ingame_dump,
    "4":  step_log_extract,
    "5":  lambda: run("extract_from_jars.py",           "Jar scanner"),
    "6":  lambda: run("extract_structure_loottables.py", "Structure loot tables"),
    "7":  lambda: run("extract_biome_tags.py",           "Biome tags"),
    "8":  lambda: run("extract_biome_blocks.py",         "Biome blocks"),
    "9":  lambda: run("extract_item_tags.py",            "Item tags"),
    "10": lambda: run("extract_textures.py",             "Textures"),
    "11": lambda: run("extract_dimension_biomes.py",     "Dimension biomes"),
    "12": step_generate_sql,
    "13": step_consistency,
    "14": lambda: run("export_to_neo4j.py",              "Export to Neo4j"),
}


def main():
    while True:
        print(MENU)
        choice = input("  Choice: ").strip()
        if choice == "0":
            print("  Bye.")
            break
        action = ACTIONS.get(choice)
        if action is None:
            print("  Unknown option.")
            continue
        action()
        input("\n  Press Enter to return to menu...")


if __name__ == "__main__":
    main()
