# KubeJS Context — Mineutze

## Environment
- Minecraft 1.21.1, **NeoForge** (not Fabric)
- KubeJS 6.x on Rhino JS engine
- **JS engine: Rhino** — ES5-ish. No spread (`...`), no `Set` spread, no `??=`, no optional chaining at call sites. Use `forEach`, explicit null checks, `var` or `let`/`const` (both work).

## Script locations
```
minecraft/kubejs/
  server_scripts/   ← recipe events, command registry, server-side logic
  client_scripts/   ← HUD, tooltips, client-side only
  startup_scripts/  ← item/block registration (runs before world load)
```
Reload without restart: `/kubejs reload server_scripts`

## Existing scripts
| File | Purpose |
|------|---------|
| `server_scripts/dump_registries.js` | All `/dump_*` commands |
| `server_scripts/dump_overworld_noise.js` | `/dump_overworld_noise` — dumps multi_noise biome params to `kubejs/dumps/overworld_noise_params.json` |
| `server_scripts/main.js` | Placeholder only |
| `client_scripts/main.js` | Placeholder only |
| `startup_scripts/main.js` | Placeholder only |

## dump_registries.js — command inventory
All commands require op level 2. Output goes to `minecraft/logs/latest.log`.
Search for `===== <LABEL> DUMP START =====` to find output.

| Command | What it dumps | Output format |
|---------|--------------|---------------|
| `/dump_structures` | All structure IDs | `namespace:id` per line |
| `/dump_biomes` | All biome IDs | `namespace:id` per line |
| `/dump_dimensions` | All dimension keys | `namespace:id` per line |
| `/dump_items` | All item IDs | `namespace:id` per line |
| `/dump_blocks` | All block IDs | `namespace:id` per line |
| `/dump_mobs` | All entity type IDs | `namespace:id` per line |
| `/dump_enchantments` | All enchantment IDs | `namespace:id` per line |
| `/dump_effects` | All mob effect IDs | `namespace:id` per line |
| `/dump_sounds` | All sound event IDs | `namespace:id` per line |
| `/dump_entity_loottables` | Entity → loot table path | `entity_id \| loot_table_path` |
| `/dump_food_stats` | Food items | `item_id \| nutrition \| saturation` |
| `/dump_tool_stats` | Tools/weapons | `item_id \| attack_damage \| attack_speed \| max_damage` |
| `/dump_armor_stats` | Armor pieces | `item_id \| armor \| toughness \| knockback_res \| max_damage` |
| `/dump_check` | Consistency check | Namespace cross-checks, warns on entity-only namespaces |
| `/dump_debug` | Debug `minecraft:diamond_sword` attribute modifiers | Dev tool |
| `/dump_all` | Runs all of the above in sequence | — |

## Key API patterns

### Registry access (server_scripts)
```javascript
const Registries     = Java.loadClass('net.minecraft.core.registries.Registries');
const registryAccess = ctx.source.getServer().registryAccess();
const itemRegistry   = registryAccess.registryOrThrow(Registries.ITEM);

// Iterate
itemRegistry.keySet().forEach(loc => {
    let id = loc.toString(); // "namespace:name"
});

// Get item object (two-step because holder API changed in 1.21)
var opt    = itemRegistry.getOptional(loc);
if (!opt.isPresent()) return;
var holder = opt.get();
var item   = holder.value ? holder.value() : holder;
```

### ItemStack + attributes
```javascript
const ItemStack = Java.loadClass('net.minecraft.world.item.ItemStack');
var stack = new ItemStack(item);
var food  = item.getFoodProperties(stack, null); // null if not food
var mods  = stack.getAttributeModifiers().modifiers();
// mods is a Java List — use mods.size() / mods.get(i)
var entry = mods.get(0);
var attrId = entry.attribute().unwrapKey().get().location().toString();
var amount = entry.modifier().amount();
```

### Command registration pattern
```javascript
ServerEvents.commandRegistry(event => {
    const { commands: Commands } = event;
    event.register(
        Commands.literal('my_command')
            .requires(src => src.hasPermission(2))
            .executes(ctx => {
                try {
                    // ... do work ...
                    ctx.source.sendSuccess(() => Component.literal('§aDone'), false);
                } catch(e) {
                    ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`));
                }
                return 1;
            })
    );
});
```

### Dimension dump (special — uses levelKeys, not registryAccess)
```javascript
ctx.source.getServer().levelKeys().forEach(k => {
    let str = k.location().toString();
});
```

### Sending messages from commands
```javascript
// sendSystemMessage: takes a Component directly — use this for step-by-step output
const Component = Java.loadClass('net.minecraft.network.chat.Component');
ctx.source.sendSystemMessage(Component.literal('hello'));

// sendSuccess: takes a Supplier<Component> in 1.21 — use for final success messages
ctx.source.sendSuccess(() => Component.literal('Done'), false);

// sendFailure: takes a Component directly
ctx.source.sendFailure(Component.literal('Error: ' + e));
```

### Accessing worldgen registries (multi_noise biome params)
```javascript
// Registries and ResourceLocation are KubeJS globals — do NOT Java.loadClass them
const registryAccess = ctx.source.server.registryAccess();

// Multi-noise biome source parameter lists (overworld biome placement)
const paramListReg = registryAccess.registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
const paramList    = paramListReg.get(ResourceLocation.parse('minecraft:overworld'));
const pairs        = paramList.parameters().values(); // List<Pair<Climate.ParameterPoint, Holder<Biome>>>

for (let i = 0; i < pairs.size(); i++) {
    const pair  = pairs.get(i);
    const point = pair.getFirst();   // Climate.ParameterPoint
    const biome = pair.getSecond().unwrapKey().get().location().toString();

    // Climate.Parameter.min()/max() return longs quantized as Math.round(float * 10000)
    const toF = v => v / 10000.0;
    const continentalness = [toF(point.continentalness().min()), toF(point.continentalness().max())];
    // Same for .temperature(), .humidity(), .erosion(), .depth(), .weirdness()
    // point.offset() returns a single long (not a Parameter range)
}
```
**Note:** `data/minecraft/worldgen/multi_noise_biome_source_parameter_list/overworld.json` in the Minecraft jar only contains `{"preset":"minecraft:overworld"}` — the expanded parameter list is hardcoded in Java and only accessible at runtime via the registry.

### Rhino gotchas
- **`net.*` is NOT auto-imported** — always use `Java.loadClass('net.minecraft.network.chat.Component')` etc., even for common MC classes. Referencing `net.minecraft...` directly throws `ReferenceError: "net" is not defined`.
- **Do NOT use `'use strict'`.** Causes `TypeError: redeclaration of var X` on script reload.
- **Never use `const`/`let` inside loops.** Rhino treats them as function-scoped `var` — second iteration throws `TypeError: redeclaration of var X`. Use `var` for loop counter and avoid any declarations inside the loop body; access values inline instead.
- **No MC classes are pre-declared globals.** Always load with `Java.loadClass`. Known required loads: `Registries`, `ResourceLocation`, `Component`. Earlier sessions saw these as globals because `dump_registries.js` loaded them first, polluting the shared scope.
- **Never use `java.io.File` / `java.io.FileWriter` in KubeJS scripts.** Direct Java file I/O fails during the `commandRegistry` event (data pack loading phase) and prevents the world from loading. Use `console.log` for all output and parse from `logs/latest.log` with Python.
- **Use `ctx.source.getServer()` not `ctx.source.server`.**
- No spread: use `setA.forEach(function(v) { if (!setB.has(v)) ... })` for set diff
- `Set`/`Map` from Java are not JS Set/Map — use JS `{}` objects or `new Set()` (Rhino has JS Set)
- Template literals work. Arrow functions work. `let`/`const` work.
- `Array.from()` may not work on Java iterables — use `.forEach()` directly

## Pipeline integration
Output from `/dump_all` is parsed by `pythonScripts/extract_from_log.py`.
Log file: `minecraft/logs/latest.log`
Markers: `===== <LABEL> DUMP START =====` … `===== <LABEL> DUMP END … =====`
Sections grouped by `--- namespace ---` lines.

## Adding a new dump command
1. Write `runXxxDump(ctx)` function in `dump_registries.js`
2. Register with `event.register(Commands.literal('dump_xxx').requires(...).executes(...))`
3. Add call to `/dump_all` handler at bottom of the file
4. Add parser in `extract_from_log.py` matching the output format
5. Reload: `/kubejs reload server_scripts`, then run `/dump_xxx` or `/dump_all`
