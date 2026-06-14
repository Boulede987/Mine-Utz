// KubeJS Registry Dumper - 1.21.1 / NeoForge / KubeJS 6
// Place in: kubejs/server_scripts/dump_registries.js
// Reload with: /kubejs reload server_scripts
//
// Commands:
//   /dump_structures
//   /dump_biomes
//   /dump_dimensions
//   /dump_items
//   /dump_blocks
//   /dump_mobs
//   /dump_enchantments
//   /dump_effects
//   /dump_sounds
//   /dump_entity_loottables
//   /dump_check
//   /dump_all
//
// Results go to: logs/latest.log
// Search for "===== DUMP START =====" to find them.

ServerEvents.commandRegistry(event => {
    const { commands: Commands } = event;

    function runDump(ctx, registry, label) {
        let groups = {};
        ctx.source.getServer().registryAccess()
            .registryOrThrow(Java.loadClass('net.minecraft.core.registries.Registries')[registry])
            .keySet()
            .forEach(loc => {
                let str = loc.toString();
                let namespace = str.split(':')[0];
                if (!groups[namespace]) groups[namespace] = [];
                groups[namespace].push(str);
            });

        let lines = [`===== ${label} DUMP START =====`];
        let total = 0;
        Object.keys(groups).sort().forEach(namespace => {
            lines.push(`\n--- ${namespace} ---`);
            groups[namespace].sort().forEach(entry => {
                lines.push(entry);
                total++;
            });
        });
        lines.push(`\n===== ${label} DUMP END (${total} total) =====`);

        console.log(lines.join('\n'));
        ctx.source.sendSuccess(Component.literal(`§aDumped ${total} ${label.toLowerCase()}s to logs/latest.log`), false);
    }

    function runDimDump(ctx) {
        let groups = {};
        ctx.source.getServer().levelKeys().forEach(k => {
            let str = k.location().toString();
            let namespace = str.split(':')[0];
            if (!groups[namespace]) groups[namespace] = [];
            groups[namespace].push(str);
        });

        let lines = ['===== DIMENSION DUMP START ====='];
        let total = 0;
        Object.keys(groups).sort().forEach(namespace => {
            lines.push(`\n--- ${namespace} ---`);
            groups[namespace].sort().forEach(entry => {
                lines.push(entry);
                total++;
            });
        });
        lines.push(`\n===== DIMENSION DUMP END (${total} total) =====`);

        console.log(lines.join('\n'));
        ctx.source.sendSuccess(Component.literal(`§aDumped ${total} dimensions to logs/latest.log`), false);
    }

    function runEntityLootDump(ctx) {
        const Registries     = Java.loadClass('net.minecraft.core.registries.Registries');
        const entityRegistry = ctx.source.getServer().registryAccess()
            .registryOrThrow(Registries.ENTITY_TYPE);

        let lines   = ['===== ENTITY LOOTTABLE DUMP START ====='];
        let total   = 0;
        let missing = 0;

        entityRegistry.keySet().forEach(loc => {
            const entityType = entityRegistry.get(loc);
            if (!entityType) return;

            let lootStr = '';
            try {
                lootStr = entityType.getDefaultLootTable().location().toString();
            } catch(e) {
                lootStr = '';
            }

            if (!lootStr || lootStr === 'minecraft:empty') {
                missing++;
                return;
            }

            lines.push(`${loc.toString()} | ${lootStr}`);
            total++;
        });

        lines.push(`\n===== ENTITY LOOTTABLE DUMP END (${total} total, ${missing} with no loot table) =====`);
        console.log(lines.join('\n'));
        ctx.source.sendSuccess(Component.literal(`§aDumped ${total} entity loot tables to logs/latest.log`), false);
    }

    function runConsistencyCheck(ctx) {
        const Registries     = Java.loadClass('net.minecraft.core.registries.Registries');
        const registryAccess = ctx.source.getServer().registryAccess();

        const entityRegistry = registryAccess.registryOrThrow(Registries.ENTITY_TYPE);
        const itemRegistry   = registryAccess.registryOrThrow(Registries.ITEM);
        const biomeRegistry  = registryAccess.registryOrThrow(Registries.BIOME);
        const structRegistry = registryAccess.registryOrThrow(Registries.STRUCTURE);

        let lines  = ['===== CONSISTENCY CHECK START ====='];
        let issues = 0;

        // ── 1. Registry counts ────────────────────────────────────────────────
        lines.push('\n--- Registry counts ---');
        lines.push(`  Entities   : ${entityRegistry.keySet().size()}`);
        lines.push(`  Items      : ${itemRegistry.keySet().size()}`);
        lines.push(`  Biomes     : ${biomeRegistry.keySet().size()}`);
        lines.push(`  Structures : ${structRegistry.keySet().size()}`);

        // ── 2. Entities with no loot table (informational) ────────────────────
        let noLoot = [];
        entityRegistry.keySet().forEach(loc => {
            const entityType = entityRegistry.get(loc);
            if (!entityType) return;
            let lootStr = '';
            try { lootStr = entityType.getDefaultLootTable().location().toString(); } catch(e) {}
            if (!lootStr || lootStr === 'minecraft:empty') noLoot.push(loc.toString());
        });
        lines.push(`\n--- Entities with no loot table (informational): ${noLoot.length} ---`);
        noLoot.sort().forEach(e => lines.push(`  INFO: ${e}`));

        // ── 3. Collect namespaces per registry ────────────────────────────────
        let entityNS = new Set();
        let itemNS   = new Set();
        let biomeNS  = new Set();
        let structNS = new Set();
        entityRegistry.keySet().forEach(loc => entityNS.add(loc.toString().split(':')[0]));
        itemRegistry.keySet().forEach(loc => itemNS.add(loc.toString().split(':')[0]));
        biomeRegistry.keySet().forEach(loc => biomeNS.add(loc.toString().split(':')[0]));
        structRegistry.keySet().forEach(loc => structNS.add(loc.toString().split(':')[0]));

        // Helper: filter a Set into a sorted array (Rhino has no spread operator)
        function setDiff(setA, setB) {
            let result = [];
            setA.forEach(function(v) { if (!setB.has(v)) result.push(v); });
            result.sort();
            return result;
        }

        // ── 4. Namespaces with entities but no items ──────────────────────────
        let entityOnly = setDiff(entityNS, itemNS);
        lines.push(`\n--- Namespaces with entities but no items: ${entityOnly.length} ---`);
        entityOnly.forEach(function(ns) { lines.push(`  WARN: ${ns}`); issues++; });

        // ── 5. Namespaces with items but no entities (informational) ──────────
        let itemOnly = setDiff(itemNS, entityNS);
        lines.push(`\n--- Namespaces with items but no entities (informational): ${itemOnly.length} ---`);
        itemOnly.forEach(function(ns) { lines.push(`  INFO: ${ns}`); });

        // ── 6. Namespaces with biomes but no items ────────────────────────────
        let biomeOnly = setDiff(biomeNS, itemNS);
        lines.push(`\n--- Namespaces with biomes but no items: ${biomeOnly.length} ---`);
        biomeOnly.forEach(function(ns) { lines.push(`  WARN: ${ns}`); issues++; });

        // ── 7. Namespaces with structures but no biomes ───────────────────────
        let structOnly = setDiff(structNS, biomeNS);
        lines.push(`\n--- Namespaces with structures but no biomes: ${structOnly.length} ---`);
        structOnly.forEach(function(ns) { lines.push(`  WARN: ${ns}`); issues++; });

        lines.push(`\n===== CONSISTENCY CHECK END (${issues} warnings) =====`);
        console.log(lines.join('\n'));
        ctx.source.sendSuccess(Component.literal(
            issues > 0
                ? `§e${issues} consistency warnings — check logs/latest.log`
                : `§aNo consistency issues found`
        ), false);
    }

    function getAttrAmount(stack, attrId) {
        var mods = stack.getAttributeModifiers().modifiers();
        for (var i = 0; i < mods.size(); i++) {
            var entry  = mods.get(i);
            var keyOpt = entry.attribute().unwrapKey();
            if (!keyOpt.isPresent()) continue;
            if (keyOpt.get().location().toString() === attrId)
                return entry.modifier().amount();
        }
        return null;
    }

    function runFoodStatsDump(ctx) {
        const ItemStack    = Java.loadClass('net.minecraft.world.item.ItemStack');
        const Registries   = Java.loadClass('net.minecraft.core.registries.Registries');
        const itemRegistry = ctx.source.getServer().registryAccess().registryOrThrow(Registries.ITEM);

        let lines  = ['===== FOOD STATS DUMP START ====='];
        let total  = 0;
        let errors = 0;

        itemRegistry.keySet().forEach(loc => {
            try {
                var opt = itemRegistry.getOptional(loc);
                if (!opt.isPresent()) return;
                var holder = opt.get();
                var item  = holder.value ? holder.value() : holder;
                var stack = new ItemStack(item);
                var food  = item.getFoodProperties(stack, null);
                if (!food) return;
                lines.push(`${loc} | ${food.nutrition()} | ${food.saturation()}`);
                total++;
            } catch(e) { errors++; lines.push(`# ERROR ${e}`); }
        });

        lines.push(`===== FOOD STATS DUMP END (${total} total, ${errors} errors) =====`);
        console.log(lines.join('\n'));
        ctx.source.sendSuccess(Component.literal(`§aDumped ${total} food items (${errors} errors)`), false);
    }

    function runToolStatsDump(ctx) {
        const ItemStack    = Java.loadClass('net.minecraft.world.item.ItemStack');
        const Registries   = Java.loadClass('net.minecraft.core.registries.Registries');
        const itemRegistry = ctx.source.getServer().registryAccess().registryOrThrow(Registries.ITEM);

        let lines  = ['===== TOOL STATS DUMP START ====='];
        let total  = 0;
        let errors = 0;

        itemRegistry.keySet().forEach(loc => {
            try {
                var opt = itemRegistry.getOptional(loc);
                if (!opt.isPresent()) return;
                var holder = opt.get();
                var item   = holder.value ? holder.value() : holder;
                var stack  = new ItemStack(item);
                var atkDmg = getAttrAmount(stack, 'minecraft:generic.attack_damage');
                var atkSpd = getAttrAmount(stack, 'minecraft:generic.attack_speed');
                if (atkDmg === null && atkSpd === null) return;

                lines.push(`${loc} | ${atkDmg} | ${atkSpd} | ${stack.getMaxDamage()}`);
                total++;
            } catch(e) { errors++; lines.push(`# ERROR ${e}`); }
        });

        lines.push(`===== TOOL STATS DUMP END (${total} total, ${errors} errors) =====`);
        console.log(lines.join('\n'));
        ctx.source.sendSuccess(Component.literal(`§aDumped ${total} tool/weapon items (${errors} errors)`), false);
    }

    function runArmorStatsDump(ctx) {
        const ItemStack    = Java.loadClass('net.minecraft.world.item.ItemStack');
        const Registries   = Java.loadClass('net.minecraft.core.registries.Registries');
        const itemRegistry = ctx.source.getServer().registryAccess().registryOrThrow(Registries.ITEM);

        let lines  = ['===== ARMOR STATS DUMP START ====='];
        let total  = 0;
        let errors = 0;

        itemRegistry.keySet().forEach(loc => {
            try {
                var opt = itemRegistry.getOptional(loc);
                if (!opt.isPresent()) return;
                var holder = opt.get();
                var item      = holder.value ? holder.value() : holder;
                var stack     = new ItemStack(item);
                var armor     = getAttrAmount(stack, 'minecraft:generic.armor');
                if (armor === null) return;

                var toughness = getAttrAmount(stack, 'minecraft:generic.armor_toughness');
                var kbRes     = getAttrAmount(stack, 'minecraft:generic.knockback_resistance');
                lines.push(`${loc} | ${armor} | ${toughness !== null ? toughness : 0} | ${kbRes !== null ? kbRes : 0} | ${stack.getMaxDamage()}`);
                total++;
            } catch(e) { errors++; lines.push(`# ERROR ${e}`); }
        });

        lines.push(`===== ARMOR STATS DUMP END (${total} total, ${errors} errors) =====`);
        console.log(lines.join('\n'));
        ctx.source.sendSuccess(Component.literal(`§aDumped ${total} armor items (${errors} errors)`), false);
    }

    event.register(Commands.literal('dump_structures').requires(src => src.hasPermission(2)).executes(ctx => {
        try { runDump(ctx, 'STRUCTURE', 'STRUCTURE'); } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
        return 1;
    }));

    function runDebugDump(ctx) {
        const ItemStack    = Java.loadClass('net.minecraft.world.item.ItemStack');
        const Registries   = Java.loadClass('net.minecraft.core.registries.Registries');
        const itemRegistry = ctx.source.getServer().registryAccess().registryOrThrow(Registries.ITEM);
        let lines = ['===== DEBUG START ====='];

        itemRegistry.keySet().forEach(loc => {
            if (loc.toString() !== 'minecraft:diamond_sword') return;
            lines.push('loc: ' + loc + ' (typeof ' + (typeof loc) + ')');

            try {
                var opt = itemRegistry.getOptional(loc);
                lines.push('opt.isPresent: ' + opt.isPresent());
                if (!opt.isPresent()) return;
                var holder = opt.get();
                lines.push('holder: ' + holder + ' (typeof ' + (typeof holder) + ')');
                var item = (holder.value) ? holder.value() : holder;
                lines.push('item: ' + item + ' (typeof ' + (typeof item) + ')');

                try {
                    var stack = new ItemStack(item);
                    lines.push('stack.isEmpty: ' + stack.isEmpty());
                    var am = stack.getAttributeModifiers();
                    lines.push('am: ' + am);
                    lines.push('mods.size: ' + am.modifiers().size());
                    if (am.modifiers().size() > 0) {
                        var e0 = am.modifiers().get(0);
                        lines.push('entry[0].attribute: ' + e0.attribute().unwrapKey().get().location());
                        lines.push('entry[0].amount: ' + e0.modifier().amount());
                    }
                } catch(e2) { lines.push('ItemStack/am error: ' + e2); }
            } catch(e) { lines.push('getOptional error: ' + e); }
        });

        lines.push('===== DEBUG END =====');
        console.log(lines.join('\n'));
        ctx.source.sendSuccess(Component.literal('§aDebug done, check logs'), false);
    }

    event.register(Commands.literal('dump_debug').requires(src => src.hasPermission(2)).executes(ctx => {
        try { runDebugDump(ctx); } catch(e) { ctx.source.sendFailure(Component.literal(`§cDebug failed: ${e}`)); }
        return 1;
    }));

    event.register(Commands.literal('dump_biomes').requires(src => src.hasPermission(2)).executes(ctx => {
        try { runDump(ctx, 'BIOME', 'BIOME'); } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
        return 1;
    }));

    event.register(Commands.literal('dump_dimensions').requires(src => src.hasPermission(2)).executes(ctx => {
        try { runDimDump(ctx); } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
        return 1;
    }));

    event.register(Commands.literal('dump_items').requires(src => src.hasPermission(2)).executes(ctx => {
        try { runDump(ctx, 'ITEM', 'ITEM'); } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
        return 1;
    }));

    event.register(Commands.literal('dump_blocks').requires(src => src.hasPermission(2)).executes(ctx => {
        try { runDump(ctx, 'BLOCK', 'BLOCK'); } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
        return 1;
    }));

    event.register(Commands.literal('dump_mobs').requires(src => src.hasPermission(2)).executes(ctx => {
        try { runDump(ctx, 'ENTITY_TYPE', 'MOB'); } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
        return 1;
    }));

    event.register(Commands.literal('dump_enchantments').requires(src => src.hasPermission(2)).executes(ctx => {
        try { runDump(ctx, 'ENCHANTMENT', 'ENCHANTMENT'); } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
        return 1;
    }));

    event.register(Commands.literal('dump_effects').requires(src => src.hasPermission(2)).executes(ctx => {
        try { runDump(ctx, 'MOB_EFFECT', 'EFFECT'); } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
        return 1;
    }));

    event.register(Commands.literal('dump_sounds').requires(src => src.hasPermission(2)).executes(ctx => {
        try { runDump(ctx, 'SOUND_EVENT', 'SOUND'); } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
        return 1;
    }));

    event.register(Commands.literal('dump_entity_loottables').requires(src => src.hasPermission(2)).executes(ctx => {
        try { runEntityLootDump(ctx); } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
        return 1;
    }));

    event.register(Commands.literal('dump_check').requires(src => src.hasPermission(2)).executes(ctx => {
        try { runConsistencyCheck(ctx); } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
        return 1;
    }));

    event.register(Commands.literal('dump_food_stats').requires(src => src.hasPermission(2)).executes(ctx => {
        try { runFoodStatsDump(ctx); } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
        return 1;
    }));

    event.register(Commands.literal('dump_tool_stats').requires(src => src.hasPermission(2)).executes(ctx => {
        try { runToolStatsDump(ctx); } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
        return 1;
    }));

    event.register(Commands.literal('dump_armor_stats').requires(src => src.hasPermission(2)).executes(ctx => {
        try { runArmorStatsDump(ctx); } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
        return 1;
    }));

    event.register(Commands.literal('dump_all').requires(src => src.hasPermission(2)).executes(ctx => {
        try {
            runDump(ctx, 'STRUCTURE',   'STRUCTURE');
            runDump(ctx, 'BIOME',       'BIOME');
            runDimDump(ctx);
            runDump(ctx, 'ITEM',        'ITEM');
            runDump(ctx, 'BLOCK',       'BLOCK');
            runDump(ctx, 'ENTITY_TYPE', 'MOB');
            runDump(ctx, 'ENCHANTMENT', 'ENCHANTMENT');
            runDump(ctx, 'MOB_EFFECT',  'EFFECT');
            runDump(ctx, 'SOUND_EVENT', 'SOUND');
            runEntityLootDump(ctx);
            runFoodStatsDump(ctx);
            runToolStatsDump(ctx);
            runArmorStatsDump(ctx);
            runConsistencyCheck(ctx);
            ctx.source.sendSuccess(Component.literal(`§aDumped everything to logs/latest.log`), false);
        } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
        return 1;
    }));
});
