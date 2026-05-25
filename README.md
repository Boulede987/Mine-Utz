# MineUtze Modpack

A custom Minecraft modpack tracked with [packwiz](https://packwiz.infra.link/) and tested via [Prism Launcher](https://prismlauncher.org/).

---

## Table of Contents

- [Project Structure](#project-structure)
- [What is Packwiz and Why Do We Use It?](#what-is-packwiz-and-why-do-we-use-it)
- [Setup](#setup)
- [Adding Mods](#adding-mods)
- [Testing](#testing)
- [Registry Dumps](#registry-dumps)
- [Graph Explorer](#graph-explorer)
- [Making a Release](#making-a-release)

---

## Project Structure

```
/
├── pack.toml                  # Packwiz pack metadata (MC version, loader, etc.)
├── index.toml                 # Packwiz mod index (auto-managed)
├── mods/                      # Packwiz .pw.toml metadata files (one per mod)
├── minecraft/
│   ├── mods/                  # Actual mod jars (downloaded by Prism, not tracked by Git)
│   ├── config/                # Mod configuration files (tracked)
│   ├── defaultconfigs/        # Default config overrides (tracked)
│   ├── kubejs/                # KubeJS scripts (tracked)
│   ├── local/ftbquests/       # FTB Quests quest book (tracked)
│   └── cobblenav/settings/    # Cobblenav settings (tracked)
├── lists/                     # Extracted data snapshots: items, loot tables, recipes, biomes, etc.
├── pythonScripts/             # Data extraction pipeline (jar scanner → lists/ → Neo4j)
├── webui/                     # Graph Explorer web app (Cytoscape.js + Neo4j)
└── release/                   # Exported .mrpack files for distribution
```

> **Rule of thumb:** if it's something you authored or configured, it's tracked. If it was downloaded or generated at runtime, it's ignored.

---

## What is Packwiz and Why Do We Use It?

Packwiz is a command-line tool that manages a modpack as a collection of plain text metadata files (`.pw.toml`), one per mod. Instead of storing mod `.jar` files in Git (which would be huge and legally murky), packwiz stores just enough information to reproduce the exact mod list: the mod's source (Modrinth or CurseForge), its version, and its hash.

**Why packwiz over just exporting from Prism?**

Prism can export `.mrpack` files directly, but those are binary snapshots — not human-readable, not diff-able, and not easy to update incrementally. Packwiz gives us:

- **Git-friendly tracking** — every mod addition, removal, or update is a clean, readable commit
- **Both sources supported** — Modrinth and CurseForge mods are handled natively
- **Easy updates** — one command to update all mods to their latest compatible versions
- **Reproducible releases** — export a `.mrpack` at any point from a known-good state

Prism is still used for **testing and playing**. Packwiz is the source of truth; Prism is the runtime.

---

## Setup

### Prerequisites

- [Packwiz](https://packwiz.infra.link/) installed and on your PATH
- [Prism Launcher](https://prismlauncher.org/) installed
- Git

### First-time setup

Clone the repo and open a terminal at the root. Prism already manages the `minecraft/` folder as a live instance. Packwiz metadata lives at the repo root in `mods/`.

To verify packwiz can see your pack:

```bash
packwiz status
```

---

## Adding Mods

Always add mods via packwiz so they are tracked in Git. Do **not** drag jars into Prism manually, as those won't be recorded.

### From Modrinth

```bash
packwiz modrinth add <slug-or-url>
```

Example:
```bash
packwiz modrinth add sodium
```

### From CurseForge

```bash
packwiz curseforge add <slug-or-url>
```

### What happens

Packwiz creates a `.pw.toml` file in `mods/` and updates `index.toml`. It automatically selects the correct version for your Minecraft version and mod loader, and will prompt you if dependencies are needed.

After adding mods, commit the changes:

```bash
git add mods/ index.toml
git commit -m "add: <mod name>"
```

### Updating mods

To update a single mod:
```bash
packwiz update <mod-name>
```

To update everything at once:
```bash
packwiz update --all
```

Then commit the result as above.

### Removing a mod

```bash
packwiz remove <mod-name>
```

---

## Testing

Testing is done in Prism Launcher against the live `minecraft/` folder.

1. Make sure Prism is pointing at this repo's `minecraft/` folder as its instance directory.
2. Add or update mods via packwiz as described above.
3. Sync the actual jars into `minecraft/mods/` by running:
   ```bash
   packwiz refresh
   ```
   Then in Prism, use **Download Mods** or let the instance sync on launch if you have packwiz-installer set up.
4. Launch the instance from Prism and test in-game.
5. Config changes made in-game will appear in `minecraft/config/` — commit anything intentional.

> Since `minecraft/mods/*.jar` is gitignored, the actual jars are never committed. Prism handles downloading them from the metadata.

---

## Registry Dumps

The `lists/` folder contains plain-text snapshots of every registry in the modpack — biomes, mobs, structures, dimensions, items, blocks, enchantments, effects, and sounds. These are generated in-game using a KubeJS script and committed to Git, so that when mods are updated, a simple `git diff` immediately shows what was added, removed, or renamed.

The script lives at `minecraft/kubejs/server_scripts/dump_registries.js`. Load it with `/kubejs reload server_scripts`, then use any of the following commands in-game (requires permission level 2):

| Command | Dumps |
|---|---|
| `/dump_biomes` | All biome IDs |
| `/dump_structures` | All structure IDs |
| `/dump_dimensions` | All loaded dimension IDs |
| `/dump_mobs` | All entity type IDs |
| `/dump_items` | All item IDs |
| `/dump_blocks` | All block IDs |
| `/dump_enchantments` | All enchantment IDs |
| `/dump_effects` | All mob effect IDs |
| `/dump_sounds` | All sound event IDs |
| `/dump_all` | Everything above in one go |

Results are printed to `logs/latest.log`. Search for `===== DUMP START =====` to find them. Copy the relevant block out and paste it into the appropriate file under `lists/`, for example `lists/ListBiomes.txt`, `lists/ListMobs.txt`, etc.

### When to update the lists

Re-run the relevant dump commands and update the `lists/` files whenever:
- A mod is added or removed
- A mod is updated to a new version
- You add a new biome, structure, or entity via datapack or KubeJS

Then commit the updated lists alongside the mod/datapack change:

```bash
git add lists/
git commit -m "update lists after <mod name> update"
```

This makes the Git history a reliable audit trail — you can always look back at any commit and know exactly what content was available in the pack at that point, which is especially useful when writing InControl spawn rules or datapack JSON that references specific IDs.

---

## Graph Explorer

A browser-based graph UI for exploring the modpack's data — items, recipes, mobs, biomes, structures, dimensions, and block drops — as an interactive node graph backed by Neo4j.

### Prerequisites

- [Neo4j Desktop](https://neo4j.com/download/) (or any Neo4j 5.x instance) running locally on `bolt://localhost:7687`
- Python 3.11+ with the `neo4j` driver: `pip install neo4j`

### Running the pipeline

The pipeline extracts data from mod jars and loads it into Neo4j. Run these scripts in order from the repo root:

```bash
# 1. Extract data from all mod jars
python pythonScripts/extract_from_jars.py

# 2. (Optional) Extract data that requires loading the game first
#    — biome/dimension mappings, structure loot table references
python pythonScripts/extract_dimension_biomes.py
python pythonScripts/extract_structure_loottables.py

# 3. Load everything into Neo4j
python pythonScripts/export_to_neo4j.py
```

Re-run steps 1 and 3 whenever mods are added or updated.

### Opening the app

Open `webui/index.html` directly in a browser (no server needed — it connects to Neo4j via the bolt protocol).

1. Enter your Neo4j credentials and click **Connect**
2. Type any item, mob, biome, structure, or dimension name in the search box
3. Select a query type (or leave on Auto-detect) and click **Search**

### Features

| Feature | How |
|---|---|
| Recipe chain | Search an item, select "Recipe chain" |
| Full ingredient tree | Select "Recipe tree (recursive)" |
| Item sources (drops, chests, mining) | Select "Item sources" |
| Mob spawns and drops | Search a mob, or select "Mob info" from right-click menu |
| Block drops | Search a block, or select "Block drops" from right-click menu |
| Biome / structure / dimension info | Auto-detected on search |
| Expand a node's neighbors | Double-click or right-click → Expand neighbors |
| Raw Cypher query | Type any Cypher in the Raw Cypher box, Ctrl+Enter to run |
| Pin nodes | Drag a node to lock its position |
| Navigation history | Previous searches saved as chips in the History panel |

### Keyboard shortcuts

| Key | Action |
|---|---|
| `/` | Focus search |
| `Enter` | Run search |
| `Ctrl+Enter` (in Cypher box) | Run raw Cypher |
| `F` | Fit graph to screen |
| `R` | Re-run layout |
| `U` | Unpin all nodes |
| `Del` | Remove selected node |
| `Esc` | Close panel / deselect |

---

## Making a Release

A release is a single `.mrpack` file that bundles the mod list *and* all configs, scripts, and quest data together so players can import it into any compatible launcher.

### Steps

1. **Ensure everything is committed and the pack is in a good state.**

2. **Export from Prism:**
   - Right-click your instance in Prism → **Export Instance**
   - Choose **Modrinth** format
   - In the export dialog, make sure the following folders are **included** as overrides:
     - `config/`
     - `defaultconfigs/`
     - `kubejs/`
     - `local/ftbquests/quests/`
     - `cobblenav/settings/`
   - Save the output to the `release/` folder in this repo, named with a version: `mineutz-v1.0.0.mrpack`

3. **Commit the release:**
   ```bash
   git add release/mineutz-v1.0.0.mrpack
   git commit -m "release: v1.0.0"
   git tag v1.0.0
   ```

> **Note on CurseForge mods:** Mods sourced from CurseForge cannot be bundled directly in the `.mrpack` due to their distribution policy. They will be listed as external downloads. Players using Prism will be prompted to download them automatically; players on other launchers may need a CurseForge account.

## Utilities

Select-String -Path latest.log -Pattern "ERROR|FATAL|Exception|Caused by" | Select-Object -First 60

Select-String -Path latest.log -Pattern "ERROR|FATAL|MixinApply|Caused by|mixin config" | Select-Object -First 50

Select-String -Path latest.log -Pattern "Done!|Minecraft Crash|stopping|Exception in thread" | Select-Object -Last 10

Select-String -Path latest.log -Pattern "Caused by|Exception" | Select-Object -Last 20

Get-Content latest.log | Select-Object -Skip 27775 | Select-Object -First 50