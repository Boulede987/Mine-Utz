// Dumps multi_noise biome parameter lists to logs/latest.log as JSON.
//
// /dump_overworld_noise  — overworld only (legacy, ~7600 entries)
// /dump_all_noise        — all registered parameter lists (overworld + modded dims)
//
// After either command: python pythonScripts/worldgen/extract_noise_to_json.py

ServerEvents.commandRegistry(event => {
    const { commands: Commands } = event;

    event.register(
        Commands.literal('dump_overworld_noise')
            .requires(src => src.hasPermission(2))
            .executes(ctx => {
                const Regs    = Java.loadClass('net.minecraft.core.registries.Registries');
                const RL      = Java.loadClass('net.minecraft.resources.ResourceLocation');
                const pReg    = ctx.source.getServer().registryAccess()
                                   .registryOrThrow(Regs.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
                const pList   = pReg.get(RL.parse('minecraft:overworld'));

                if (!pList) {
                    ctx.source.sendFailure(Component.literal('minecraft:overworld not found in registry'));
                    return 0;
                }

                const pairs   = pList.parameters().values();
                const toF     = v => Math.round(v / 10000.0 * 10000) / 10000;
                const toRange = p => [toF(p.min()), toF(p.max())];

                const entries = [];
                for (var i = 0; i < pairs.size(); i++) {
                    entries.push({
                        biome: pairs.get(i).getSecond().unwrapKey().get().location().toString(),
                        parameters: {
                            temperature:     toRange(pairs.get(i).getFirst().temperature()),
                            humidity:        toRange(pairs.get(i).getFirst().humidity()),
                            continentalness: toRange(pairs.get(i).getFirst().continentalness()),
                            erosion:         toRange(pairs.get(i).getFirst().erosion()),
                            depth:           toRange(pairs.get(i).getFirst().depth()),
                            weirdness:       toRange(pairs.get(i).getFirst().weirdness()),
                            offset:          toF(pairs.get(i).getFirst().offset()),
                        }
                    });
                }

                console.log('===== OVERWORLD NOISE PARAMS START =====');
                console.log(JSON.stringify({ biomes: entries }));
                console.log('===== OVERWORLD NOISE PARAMS END =====');

                ctx.source.sendSuccess(() => Component.literal(
                    '§aDumped ' + entries.length + ' entries to logs/latest.log'
                ), false);
                return 1;
            })
    );

    // ── /dump_all_noise — every registered parameter list ──────────────────
    event.register(
        Commands.literal('dump_all_noise')
            .requires(src => src.hasPermission(2))
            .executes(ctx => {
                const Regs  = Java.loadClass('net.minecraft.core.registries.Registries');
                const pReg  = ctx.source.getServer().registryAccess()
                                 .registryOrThrow(Regs.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);

                const toF     = v => Math.round(v / 10000.0 * 10000) / 10000;
                const toRange = p => [toF(p.min()), toF(p.max())];

                var result   = {};
                var dimCount = 0;
                var totalEntries = 0;

                pReg.keySet().forEach(function(key) {
                    var dimId = key.toString();
                    var pList = pReg.get(key);
                    if (!pList) return;

                    var pairs   = pList.parameters().values();
                    var entries = [];
                    for (var i = 0; i < pairs.size(); i++) {
                        entries.push({
                            biome: pairs.get(i).getSecond().unwrapKey().get().location().toString(),
                            parameters: {
                                temperature:     toRange(pairs.get(i).getFirst().temperature()),
                                humidity:        toRange(pairs.get(i).getFirst().humidity()),
                                continentalness: toRange(pairs.get(i).getFirst().continentalness()),
                                erosion:         toRange(pairs.get(i).getFirst().erosion()),
                                depth:           toRange(pairs.get(i).getFirst().depth()),
                                weirdness:       toRange(pairs.get(i).getFirst().weirdness()),
                                offset:          toF(pairs.get(i).getFirst().offset()),
                            }
                        });
                    }
                    result[dimId] = entries;
                    dimCount++;
                    totalEntries += entries.length;
                });

                console.log('===== ALL NOISE PARAMS START =====');
                console.log(JSON.stringify(result));
                console.log('===== ALL NOISE PARAMS END =====');

                ctx.source.sendSuccess(() => Component.literal(
                    '§aDumped ' + dimCount + ' parameter list(s), ' + totalEntries + ' total entries to logs/latest.log'
                ), false);
                return 1;
            })
    );
});
