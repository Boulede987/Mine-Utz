# Cobblemon Worldgen Suppression

Cobblemon injects two categories of worldgen content that this pack suppresses via the Paxi datapack (`minecraft/config/paxi/datapacks/mineutz/`). This file documents what is suppressed and how to update the suppressions when Cobblemon updates.

---

## 1. Pokecenter in Vanilla Villages

**Mechanism:** Cobblemon replaces `data/minecraft/worldgen/template_pool/village/<type>/houses.json` for all 5 village types. Each replacement includes all vanilla house entries plus two copies of a Cobblemon Pokecenter at weight 150 (vanilla houses are weight 2 — effectively guarantees a Pokecenter in every village).

**Suppression:** The Paxi datapack contains override files at:
```
data/minecraft/worldgen/template_pool/village/plains/houses.json
data/minecraft/worldgen/template_pool/village/desert/houses.json
data/minecraft/worldgen/template_pool/village/savanna/houses.json
data/minecraft/worldgen/template_pool/village/snowy/houses.json
data/minecraft/worldgen/template_pool/village/taiga/houses.json
```
Each file is the vanilla-only pool (copied verbatim from the Cobblemon jar with its Cobblemon entries stripped). Paxi loads last, so this override wins over Cobblemon's replacement.

**When Cobblemon updates:**
1. Open the new `Cobblemon-neoforge-*.jar` with a zip tool.
2. Extract each `data/minecraft/worldgen/template_pool/village/<type>/houses.json`.
3. Remove any elements whose `location` contains `cobblemon`.
4. Replace the corresponding files in `data/minecraft/worldgen/template_pool/village/` inside the Paxi datapack.

Or re-run this script from the repo root:
```python
import zipfile, json
from pathlib import Path

jar      = next(Path('minecraft/mods').glob('Cobblemon-neoforge-*.jar'))
paxi     = Path('minecraft/config/paxi/datapacks/mineutz/data')

with zipfile.ZipFile(jar) as z:
    for vtype in ['plains', 'desert', 'savanna', 'snowy', 'taiga']:
        src  = f'data/minecraft/worldgen/template_pool/village/{vtype}/houses.json'
        data = json.loads(z.read(src))
        data['elements'] = [e for e in data['elements'] if 'cobblemon' not in str(e)]
        out  = paxi / f'minecraft/worldgen/template_pool/village/{vtype}/houses.json'
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(data, indent=2), encoding='utf-8')
        print(f'  {vtype}: {len(data["elements"])} entries written')
```

---

## 2. Overworld Feature Injection (Apricorn Trees, Saccharine Trees, Revival Herbs)

**Mechanism:** Cobblemon's biome modifier (`cobblemon:inject_coded`) reads biome tags under `cobblemon:has_feature/*` to decide which biomes receive each placed feature. Features suppressed:

| Tag | Feature | Vanilla biomes affected |
|-----|---------|------------------------|
| `has_feature/apricorns_dense` | Apricorn trees (dense) | All forests, deserts, jungles, taiga, snowy forests |
| `has_feature/apricorns_normal` | Apricorn trees (normal) | Hills, grasslands, shrublands, sparse jungle |
| `has_feature/apricorns_sparse` | Apricorn trees (sparse) | Tundra |
| `has_feature/saccharine_tree` | Saccharine tree | Birch forests, floral, magical, taiga |
| `has_feature/revival_herbs` | Revival herb | Lush caves |

**Also suppressed via NeoForge biome modifier (see section 3):** Mints, galarica nuts, berry groves, medicinal leeks, big roots, and hearty grains are injected by hardcoded Java in `inject_coded` without a tag lookup, so the `has_feature` tag approach does not apply to them.

**Suppression:** The Paxi datapack contains override files at:
```
data/cobblemon/tags/worldgen/biome/has_feature/apricorns_dense.json
data/cobblemon/tags/worldgen/biome/has_feature/apricorns_normal.json
data/cobblemon/tags/worldgen/biome/has_feature/apricorns_sparse.json
data/cobblemon/tags/worldgen/biome/has_feature/saccharine_tree.json
data/cobblemon/tags/worldgen/biome/has_feature/revival_herbs.json
```
Each file contains `{"replace": true, "values": []}`. The `replace: true` clears the tag entirely so no biomes receive the feature.

**When Cobblemon updates:** These tag paths are unlikely to change. Re-check `data/cobblemon/tags/worldgen/biome/has_feature/` in the new jar for any new tags and add matching empty overrides if needed.

---

## 3. Hardcoded Overworld Features (Mints, Galarica Nuts, Berry Groves, Leeks, Grains)

**Mechanism:** `cobblemon:inject_coded` injects these placed features directly without reading any biome tag, so empty tag overrides have no effect. Features suppressed:

| Placed feature ID | Content |
|---|---|
| `cobblemon:mints` | Mint plants (various colours) |
| `cobblemon:berry_groves` | Berry bush clusters |
| `cobblemon:galarica_nuts` | Galarica nut bushes |
| `cobblemon:medicinal_leek` | Medicinal leek plants |
| `cobblemon:big_root` | Big root blocks |
| `cobblemon:plains_grains` | Hearty grains (plains) |
| `cobblemon:swamp_grains` | Hearty grains (swamp) |

**Suppression:** A NeoForge `remove_features` biome modifier in the Paxi datapack at:
```
data/mineutz/neoforge/biome_modifier/suppress_cobblemon_features.json
```
Targets `#minecraft:overworld` and removes all 7 placed features. Runs after all mod biome modifiers so it reliably removes even dynamically injected features.

**When Cobblemon updates:** Re-check `data/cobblemon/worldgen/placed_feature/` in the new jar for any new non-ore entries and add them to the `features` list in the modifier file.

---

## Notes

- Both suppressions survive world reloads — no code changes, no mod rebuilds needed.
- Cobblemon ores (evolution stones), mob spawns, and Pokémon spawning are **not** suppressed by these overrides.
- Cobblemon structures (fishing boats, fossils, ruins) use the `has_structure` tag system and are controlled via `structure_planner.xlsx` + step 26 of the pipeline, same as all other mods.
