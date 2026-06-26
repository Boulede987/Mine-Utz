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

## 2. Overworld Feature Injection (Apricorn Trees, Revival Herbs)

**Mechanism:** Cobblemon's biome modifier (`cobblemon:inject_coded`) reads biome tags under `cobblemon:has_feature/*` to decide which biomes receive each placed feature. Features suppressed via this method:

| Tag | Feature | Vanilla biomes affected |
|-----|---------|------------------------|
| `has_feature/apricorns_dense` | Apricorn trees (dense) | All forests, deserts, jungles, taiga, snowy forests |
| `has_feature/apricorns_normal` | Apricorn trees (normal) | Hills, grasslands, shrublands, sparse jungle |
| `has_feature/apricorns_sparse` | Apricorn trees (sparse) | Tundra |
| `has_feature/revival_herbs` | Revival herb | Lush caves |

**Suppression:** The Paxi datapack contains override files at:
```
data/cobblemon/tags/worldgen/biome/has_feature/apricorns_dense.json
data/cobblemon/tags/worldgen/biome/has_feature/apricorns_normal.json
data/cobblemon/tags/worldgen/biome/has_feature/apricorns_sparse.json
data/cobblemon/tags/worldgen/biome/has_feature/revival_herbs.json
```
Each file contains `{"replace": true, "values": []}`. The `replace: true` clears the tag entirely so no biomes receive the feature.

**When Cobblemon updates:** These tag paths are unlikely to change. Re-check `data/cobblemon/tags/worldgen/biome/has_feature/` in the new jar for any new tags and add matching empty overrides if needed.

---

## 3. Hardcoded Overworld Features (Mints, Galarica Nuts, Berry Groves, Saccharine Tree, Leeks, Grains)

**Mechanism:** `cobblemon:inject_coded` injects these placed features without reliably reading any biome tag. The `neoforge:remove_features` biome modifier approach also proved unreliable (NeoForge biome modifier application order is not guaranteed, so `inject_coded` can re-add features after removal). The reliable suppression is overriding the **placed feature JSON itself** to `count: 0` — zero placement attempts per chunk, regardless of what biome modifiers do.

| Placed feature ID | Content |
|---|---|
| `cobblemon:mints` | Mint plants (various colours) |
| `cobblemon:berry_groves` | Berry bush clusters (oran, prism, etc.) |
| `cobblemon:saccharine_tree` | Rainbow saccharine tree |
| `cobblemon:galarica_nuts/galarica_patch` | Galarica nut bushes |
| `cobblemon:medicinal_leek` | Medicinal leek plants |
| `cobblemon:big_root` | Big root blocks |
| `cobblemon:hearty_grains/grains_patch` | Hearty grains (plains + swamp) |

**Suppression — placed feature overrides** (reliable, upstream of all biome modifiers):
```
data/cobblemon/worldgen/placed_feature/berry_groves.json
data/cobblemon/worldgen/placed_feature/saccharine_tree.json
data/cobblemon/worldgen/placed_feature/mints.json
data/cobblemon/worldgen/placed_feature/galarica_nuts.json
data/cobblemon/worldgen/placed_feature/medicinal_leek.json
data/cobblemon/worldgen/placed_feature/big_root.json
data/cobblemon/worldgen/placed_feature/plains_grains.json
data/cobblemon/worldgen/placed_feature/swamp_grains.json
```
Each file keeps the original `feature` reference but replaces `placement` with `[{"type": "minecraft:count", "count": 0}]`. Zero placement attempts per chunk — works regardless of biome modifier ordering.

**Note:** `neoforge:remove_features` (`suppress_cobblemon_features.json`) proved unreliable because NeoForge does not guarantee `inject_coded` runs before datapack biome modifiers. The placed feature override approach is the correct solution.

**When Cobblemon updates:** Re-check `data/cobblemon/worldgen/placed_feature/` in the new jar for any new non-ore entries and add matching count-0 overrides.

---

## Notes

- Both suppressions survive world reloads — no code changes, no mod rebuilds needed.
- Cobblemon ores (evolution stones), mob spawns, and Pokémon spawning are **not** suppressed by these overrides.
- Cobblemon structures (fishing boats, fossils, ruins) use the `has_structure` tag system and are controlled via `structure_planner.xlsx` + step 26 of the pipeline, same as all other mods.
