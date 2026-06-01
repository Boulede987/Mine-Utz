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
- [Custom Dimensions](#custom-dimensions)
- [Configuration Mods](#configuration-mods)
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

## Custom Dimensions

Custom dimensions are delivered as a [Paxi](https://modrinth.com/mod/paxi) datapack so they load automatically without players needing to install anything separately. The datapack lives at:

```
minecraft/config/paxi/datapacks/mineutz/
```

### File structure

Each dimension requires exactly two JSON files:

```
data/mineutz/
├── dimension_type/
│   └── <name>.json   # Physics and environment properties
└── dimension/
    └── <name>.json   # World generator (terrain noise + biome list)
```

The dimension's registry ID becomes `mineutz:<name>`.

### dimension_type JSON

Controls how the dimension behaves physically and visually. The current custom dimensions both use overworld-equivalent settings:

```json
{
  "ultrawarm": false,
  "natural": true,
  "piglin_safe": false,
  "bed_works": true,
  "respawn_anchor_works": false,
  "has_skylight": true,
  "has_ceiling": false,
  "has_raids": false,
  "coordinate_scale": 1.0,
  "ambient_light": 0.0,
  "min_y": -64,
  "height": 384,
  "logical_height": 384,
  "effects": "minecraft:overworld",
  "infiniburn": "#minecraft:infiniburn_overworld",
  "monster_spawn_block_light_limit": 0,
  "monster_spawn_light_level": 7
}
```

Key fields to adjust per dimension:

| Field | Effect |
|---|---|
| `ultrawarm` | `true` → water evaporates (Nether behaviour) |
| `has_skylight` | `false` → always dark (Nether / End) |
| `has_ceiling` | `true` → bedrock ceiling |
| `bed_works` | `false` → beds explode |
| `effects` | Sky, fog and ambient sound profile (`minecraft:overworld / the_nether / the_end`) |
| `coordinate_scale` | Position multiplier vs overworld (8.0 = Nether-style 1:8 ratio) |
| `monster_spawn_light_level` | Max light level where monsters spawn (default 7) |

### dimension JSON

Defines the world generator and the biome list. The `biomes` array uses the same multi-noise parameter system as the vanilla overworld — each entry maps a climate parameter range to a biome ID.

```json
{
  "type": "mineutz:zombie_city",
  "generator": {
    "type": "minecraft:noise",
    "biome_source": {
      "type": "minecraft:multi_noise",
      "biomes": [
        {
          "biome": "minecraft:plains",
          "parameters": {
            "temperature":     [-0.15, 0.2],
            "humidity":        [-0.35, 0.1],
            "continentalness": [-0.11, 1.0],
            "erosion":         [-1.0, 1.0],
            "depth":           [0.0, 0.0],
            "weirdness":       [-1.0, 1.0],
            "offset": 0.0
          }
        }
      ]
    },
    "settings": "minecraft:overworld"
  }
}
```

`"type"` must match the dimension's own registry ID (`mineutz:<name>`).

### Managing the biome list

The `biomes` array in each dimension JSON is managed **manually**. Use `dimension_biome_planner.xlsx` to plan which biomes go in which dimension, then edit the dimension JSON directly.

When you need the noise parameters for a specific vanilla biome as a copy-paste reference, run:

1. Load the game, run `/dump_overworld_noise` in-game
2. Exit the game
3. From the repo root:
   ```bash
   python pythonScripts/worldgen/extract_overworld_noise.py
   ```
4. This writes `worldgen_data/overworld_noise_reference.json` — the complete biome_source block for all overworld biomes. Copy individual biome entries from it into your dimension JSON.

> This script no longer writes to the Paxi dimension files. Dimension JSONs are only edited by hand.

### Adding a new custom dimension

1. **Create the dimension type file**
   Copy an existing `dimension_type/<name>.json` and adjust the fields for your dimension's physics. Save it as `data/mineutz/dimension_type/<new_name>.json`.

2. **Create the dimension file**
   Copy an existing `dimension/<name>.json`. Set `"type"` to `"mineutz:<new_name>"`. Populate the `biomes` array with your chosen biome IDs and their noise parameters — use `dimension_biome_planner.xlsx` to plan assignments.

3. **Register the dimension in Paxi's load order** (if required)
   Check `minecraft/config/paxi/datapack_load_order.json` — the `mineutz` datapack should already be listed; no changes needed unless you add a second datapack.

4. **Launch the game and verify**
   Run `/dump_dimensions` and check that `mineutz:<new_name>` appears in `logs/latest.log`. Teleport to the dimension to confirm:
   ```
   /execute in mineutz:<new_name> run tp @s ~ ~ ~
   ```

5. **Update the registry dump**
   Copy the new dimension ID into `lists/dimensions.txt` and commit alongside the new JSON files.

### Lost Cities city generation

[Lost Cities](https://modrinth.com/mod/the-lost-cities) generates ruined city structures at world-gen time. It is configured to run in specific dimensions via `minecraft/config/lostcities-server.toml`:

```toml
dimensionsWithProfiles = [
    "mineutz:zombie_city=mineutz_custom_profile",
    "lostcities:lostcity=default",
    "lostworlds:abyss=biosphere_caves"
]
```

Format: `"<dimension_id>=<profile_name>"`. Each entry enables city generation in that dimension using the named profile. Profiles live in `minecraft/config/lostcities/profiles/`.

**`mineutz_custom_profile.json`** — the custom profile for `zombie_city`. Key settings vs the stock `onlycities` profile:

| Setting | mineutz_custom_profile | onlycities | Effect |
|---|---|---|---|
| `cityChance` | 0.03 | 0.2 | Much sparser cities |
| `cityMaxRadius` | 128 | 256 | Smaller city footprints |
| `buildingMinFloors` | 4 | 0 | All buildings always tall |
| `buildingMaxFloors` | 10 | 8 | Taller maximum |
| `buildingMaxCellars` | 5 | 3 | Deeper basements |
| `generateSpawners` | false | true | No vanilla spawners (spawn rules handled by InControl instead) |

To create a custom profile, use the GUI in Minecraft World Generation directly.
To add city generation to a new custom dimension, add a `"<id>=<profile>"` entry to `dimensionsWithProfiles` and create a matching profile JSON if needed.

### Existing custom dimensions

| Dimension ID | Type file | Lost Cities profile | Notes |
|---|---|---|---|
| `mineutz:zombie_city` | `dimension_type/zombie_city.json` | `mineutz_custom_profile` | Sparse ruined cities, tall buildings |
| `mineutz:pokemon_dimension` | `dimension_type/pokemon_dimension.json` | *(none)* | Overworld-equivalent physics |

---

## Configuration Mods

These mods exist purely to configure or constrain the pack's behaviour. They don't add content — they shape it. All their config files live under `minecraft/config/` and are tracked in Git.

| Mod | Purpose | Config location |
|---|---|---|
| [AttributeSetter](https://modrinth.com/mod/attributesetter) | Modify default entity/item attributes (health, damage, follow range, etc.) via datapacks | `config/attributesetter/` |
| [In Control!](https://modrinth.com/mod/in-control) | JSON rule files controlling where and how mobs spawn, their loot, and XP rewards | `config/incontrol/` |
| [Toast Control](https://www.curseforge.com/minecraft/mc-mods/toast-control) | Suppress or restyle toast notifications (recipe unlocks, tutorials, advancements) | `config/toastcontrol.toml` |
| [Better Days](https://github.com/wendall911/BetterDays) | Configurable day/night cycle length and smooth sleep time-skip | `config/betterdays-common.toml` |
| [Item Obliterator](https://modrinth.com/mod/item-obliterator) | Disable unwanted items: removes them from creative tabs, recipes, trades, and JEI/REI | `config/item_obliterator.json5` |
| [Biome Replacer](https://modrinth.com/mod/biome-replacer) | Swap or remove biomes at world-gen time via a simple `old > new` properties file | `config/biome_replacer.properties` |
| [Toasty](https://modrinth.com/mod/toasty-mod) | Trigger custom toast notifications on login, item pickup, or entity kill via resource pack JSON | `assets/<namespace>/toasts/` in a resource pack |
| [Eccentric Tome](https://www.curseforge.com/minecraft/mc-mods/eccentric-tome) | Merge multiple guidebooks into one item to reduce inventory clutter | *(no config file — craft-only)* |

> When modifying spawn rules or attribute overrides, re-run the relevant pipeline scripts and update the `lists/` snapshots so the Graph Explorer reflects the current state.

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

### In-game commands

Teleport to a dimension (keeps your current coordinates):
```
/execute in <dimension_id> run tp @s ~ ~ ~
```
Example: `/execute in minecraft:the_nether run tp @s ~ ~ ~`

Use IDs from `lists/ListDimensions.txt` for modded dimensions.

Locate and teleport to the nearest biome or structure (two steps):
```
/locate biome <biome_id>
/locate structure <structure_id>
```
Both print coordinates to chat as a clickable link — click it to auto-fill `/tp @s <x> <y> <z>`, then Enter. `locate structure` only finds structures in the current dimension. Use IDs from `lists/ListBiomes.txt` and `lists/ListStructures.txt` for modded content.

### Log analysis (PowerShell)

Run these from the directory containing `latest.log`:

```powershell
# Errors and exceptions
Select-String -Path latest.log -Pattern "ERROR|FATAL|Exception|Caused by" | Select-Object -First 60

# Mixin-related failures
Select-String -Path latest.log -Pattern "ERROR|FATAL|MixinApply|Caused by|mixin config" | Select-Object -First 50

# Check if the game actually started or crashed
Select-String -Path latest.log -Pattern "Done!|Minecraft Crash|stopping|Exception in thread" | Select-Object -Last 10

# Last exceptions before crash
Select-String -Path latest.log -Pattern "Caused by|Exception" | Select-Object -Last 20

# Read a specific line range (adjust the skip number as needed)
Get-Content latest.log | Select-Object -Skip 27775 | Select-Object -First 50
```