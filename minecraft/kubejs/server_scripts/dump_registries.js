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
//   /dump_all
//
// Results go to: logs/latest.log
// Search for "===== DUMP START =====" to find them.

ServerEvents.commandRegistry(event => {
    const { commands: Commands } = event;

    function runDump(ctx, registry, label) {
        // Collect all keys grouped by namespace
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

        // Build the full output as one string
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
        ctx.source.sendSuccess(() => Component.literal(`§aDumped ${total} ${label.toLowerCase()}s to logs/latest.log`), false);
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
        ctx.source.sendSuccess(() => Component.literal(`§aDumped ${total} dimensions to logs/latest.log`), false);
    }

    event.register(Commands.literal('dump_structures').requires(src => src.hasPermission(2)).executes(ctx => {
        try { runDump(ctx, 'STRUCTURE', 'STRUCTURE'); } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
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
            ctx.source.sendSuccess(() => Component.literal(`§aDumped everything to logs/latest.log`), false);
        } catch(e) { ctx.source.sendFailure(Component.literal(`§cFailed: ${e}`)); }
        return 1;
    }));
});