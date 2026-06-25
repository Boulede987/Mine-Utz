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
//   /dump_food_stats
//   /dump_tool_stats
//   /dump_armor_stats
//   /dump_block_stats
//   /dump_entity_stats
//   /dump_biome_stats
//   /dump_item_extra
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

    function runBlockStatsDump(ctx) {
        const Registries    = Java.loadClass('net.minecraft.core.registries.Registries');
        const blockRegistry = ctx.source.getServer().registryAccess().registryOrThrow(Registries.BLOCK);

        var lines  = ['===== BLOCK STATS DUMP START ====='];
        var total  = 0;
        var errors = 0;

        blockRegistry.keySet().forEach(function(loc) {
            try {
                var opt = blockRegistry.getOptional(loc);
                if (!opt.isPresent()) return;
                var holder = opt.get();
                var block  = holder.value ? holder.value() : holder;
                var state  = block.defaultBlockState();

                var blastRes = null, hardness = null, luminous = null, transparent = null, flammable = null;
                try { blastRes    = block.getExplosionResistance(); } catch(e2) {}
                try { hardness    = block.defaultDestroyTime();     } catch(e2) {}
                try { luminous    = state.getLightEmission() > 0;   } catch(e2) {}
                try { transparent = !state.canOcclude();            } catch(e2) {}
                try { flammable   = state.ignitedByLava();          } catch(e2) {}

                lines.push(loc + ' | ' + blastRes + ' | ' + hardness + ' | ' + luminous + ' | ' + transparent + ' | ' + flammable);
                total++;
            } catch(e) { errors++; }
        });

        lines.push('===== BLOCK STATS DUMP END (' + total + ' total, ' + errors + ' errors) =====');
        console.log(lines.join('\n'));
        ctx.source.sendSuccess(Component.literal('§aDumped ' + total + ' block stats (' + errors + ' errors)'), false);
    }

    function runEntityStatsDump(ctx) {
        const Registries     = Java.loadClass('net.minecraft.core.registries.Registries');
        const DefaultAttribs = Java.loadClass('net.minecraft.world.entity.ai.attributes.DefaultAttributes');
        const Attributes     = Java.loadClass('net.minecraft.world.entity.ai.attributes.Attributes');
        const entityRegistry = ctx.source.getServer().registryAccess().registryOrThrow(Registries.ENTITY_TYPE);

        function safeAttr(supplier, attr) {
            try { return supplier.hasAttribute(attr) ? supplier.getBaseValue(attr) : null; } catch(e) { return null; }
        }

        var lines  = ['===== ENTITY STATS DUMP START ====='];
        var total  = 0;
        var errors = 0;

        entityRegistry.keySet().forEach(function(loc) {
            try {
                var opt = entityRegistry.getOptional(loc);
                if (!opt.isPresent()) return;
                var holder = opt.get();
                var entityType = holder.value ? holder.value() : holder;

                if (!DefaultAttribs.hasSupplier(entityType)) return;
                var supplier = DefaultAttribs.getSupplier(entityType);
                var hp = safeAttr(supplier, Attributes.MAX_HEALTH);
                if (hp === null) return;

                var armor  = safeAttr(supplier, Attributes.ARMOR);
                var atkDmg = safeAttr(supplier, Attributes.ATTACK_DAMAGE);
                var speed  = safeAttr(supplier, Attributes.MOVEMENT_SPEED);
                var kbRes  = safeAttr(supplier, Attributes.KNOCKBACK_RESISTANCE);

                var dims = entityType.getDimensions();
                var w = null, h = null;
                try { w = dims.width(); h = dims.height(); } catch(e2) {
                try { w = dims.width;   h = dims.height;   } catch(e3) {} }
                // Reject non-numeric (Rhino may return method object instead of float)
                if (typeof w !== 'number') w = null;
                if (typeof h !== 'number') h = null;

                lines.push(loc + ' | ' + hp + ' | ' + armor + ' | ' + atkDmg + ' | ' + speed + ' | ' + kbRes + ' | ' + w + ' | ' + h);
                total++;
            } catch(e) { errors++; }
        });

        lines.push('===== ENTITY STATS DUMP END (' + total + ' total, ' + errors + ' errors) =====');
        console.log(lines.join('\n'));
        ctx.source.sendSuccess(Component.literal('§aDumped ' + total + ' entity stats (' + errors + ' errors)'), false);
    }

    function runBiomeStatsDump(ctx) {
        const Registries = Java.loadClass('net.minecraft.core.registries.Registries');

        var lines  = ['===== BIOME STATS DUMP START ====='];
        var total  = 0;
        var errors = 0;

        try {
            var biomeRegistry = ctx.source.getServer().registryAccess().registryOrThrow(Registries.BIOME);

            biomeRegistry.keySet().forEach(function(loc) {
                try {
                    var biome = biomeRegistry.get(loc);
                    if (!biome) return;

                    var temp   = biome.getBaseTemperature();
                    var fogInt = biome.getSpecialEffects().getFogColor();
                    var fogHex = '#' + ('000000' + (fogInt & 0xFFFFFF).toString(16).toUpperCase()).slice(-6);

                    lines.push(loc + ' | ' + temp + ' | ' + fogHex);
                    total++;
                } catch(e) { errors++; }
            });
        } catch(e) {
            errors++;
            lines.push('# ERROR: ' + e);
        }

        lines.push('===== BIOME STATS DUMP END (' + total + ' total, ' + errors + ' errors) =====');
        console.log(lines.join('\n'));
        ctx.source.sendSuccess(Component.literal('§aDumped ' + total + ' biome stats (' + errors + ' errors)'), false);
    }

    function runItemExtraDump(ctx) {
        const ItemStack    = Java.loadClass('net.minecraft.world.item.ItemStack');
        const Registries   = Java.loadClass('net.minecraft.core.registries.Registries');
        const itemRegistry = ctx.source.getServer().registryAccess().registryOrThrow(Registries.ITEM);

        // MC 1.21+ tool component — replaces TieredItem class hierarchy
        var DataComponentsClass = null;
        try { DataComponentsClass = Java.loadClass('net.minecraft.core.component.DataComponents'); } catch(e) {}


        var lines  = ['===== ITEM EXTRA DUMP START ====='];
        var total  = 0;
        var errors = 0;

        itemRegistry.keySet().forEach(function(loc) {
            try {
                var opt = itemRegistry.getOptional(loc);
                if (!opt.isPresent()) return;
                var holder = opt.get();
                var item   = holder.value ? holder.value() : holder;
                var stack  = new ItemStack(item);

                var maxStack   = stack.getMaxStackSize();
                var enchantVal = item.getEnchantmentValue();

                var miningEff = null, miningTier = null;
                // Primary: DataComponents.TOOL (MC 1.21+)
                // defaultMiningSpeed() = 1.0 fallback — actual tier speed is in rules
                if (DataComponentsClass !== null) {
                    try {
                        var toolComp = stack.get(DataComponentsClass.TOOL);
                        if (toolComp !== null) {
                            var rules = toolComp.rules();
                            var maxSpeed    = null;
                            var denyTagName = null;
                            for (var ri = 0; ri < rules.size(); ri++) {
                                var rule = rules.get(ri);
                                // Speed rule -> tier mining efficiency
                                try {
                                    var speedOpt = rule.speed();
                                    if (speedOpt.isPresent()) {
                                        var spd = speedOpt.get();
                                        if (maxSpeed === null || spd > maxSpeed) maxSpeed = spd;
                                    }
                                } catch(e3) {}
                                // Deny-drops rule -> tag name encodes tier level
                                try {
                                    var cfdOpt = rule.correctForDrops();
                                    if (cfdOpt.isPresent() && !cfdOpt.get()) {
                                        var unwrapped = rule.blocks().unwrapKey();
                                        if (unwrapped.isPresent()) {
                                            denyTagName = unwrapped.get().location().toString();
                                        }
                                    }
                                } catch(e3) {}
                            }
                            if (maxSpeed !== null) miningEff = maxSpeed;
                            if (denyTagName !== null) {
                                // Extract tier name: "minecraft:incorrect_for_diamond_tool" -> "diamond"
                                var tierMatch = denyTagName.match(/incorrect_for_(.+?)_tool/);
                                miningTier = tierMatch ? tierMatch[1] : denyTagName;
                            }
                        }
                    } catch(e2) {}
                }
                // Fallback: TieredItem (MC 1.20.x / older NeoForge)
                if (miningEff === null) {
                    try {
                        var TieredItemClass = Java.loadClass('net.minecraft.world.item.TieredItem');
                        if (TieredItemClass.isInstance(item)) {
                            var tier  = TieredItemClass.cast(item).getTier();
                            miningEff = tier.getSpeed();
                            // TieredItem fallback: no tag name available, skip miningTier
                        }
                    } catch(e2) {}
                }

                var rarity = null;
                try { rarity = stack.getRarity().name(); } catch(e2) {
                    try { rarity = item.getRarity(stack).name(); } catch(e3) {}
                }

                lines.push(loc + ' | ' + maxStack + ' | ' + enchantVal + ' | ' + miningEff + ' | ' + miningTier + ' | ' + rarity);
                total++;
            } catch(e) { errors++; }
        });

        lines.push('===== ITEM EXTRA DUMP END (' + total + ' total, ' + errors + ' errors) =====');
        console.log(lines.join('\n'));
        ctx.source.sendSuccess(Component.literal('§aDumped ' + total + ' item extras (' + errors + ' errors)'), false);
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

    event.register(Commands.literal('dump_block_stats').requires(src => src.hasPermission(2)).executes(ctx => {
        try { runBlockStatsDump(ctx); } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
        return 1;
    }));

    event.register(Commands.literal('dump_entity_stats').requires(src => src.hasPermission(2)).executes(ctx => {
        try { runEntityStatsDump(ctx); } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
        return 1;
    }));

    event.register(Commands.literal('dump_biome_stats').requires(src => src.hasPermission(2)).executes(ctx => {
        try { runBiomeStatsDump(ctx); } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
        return 1;
    }));

    event.register(Commands.literal('dump_item_extra').requires(src => src.hasPermission(2)).executes(ctx => {
        try { runItemExtraDump(ctx); } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
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
            runBlockStatsDump(ctx);
            runEntityStatsDump(ctx);
            runBiomeStatsDump(ctx);
            runItemExtraDump(ctx);
            runConsistencyCheck(ctx);
            ctx.source.sendSuccess(Component.literal(`§aDumped everything to logs/latest.log`), false);
        } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
        return 1;
    }));
});
