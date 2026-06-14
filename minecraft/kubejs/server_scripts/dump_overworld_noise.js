// Dumps multi_noise biome parameter lists to logs/latest.log as JSON.
//
// /dump_overworld_noise  — overworld only (legacy, ~7600 entries)
// /dump_all_noise        — all dimensions; also reads live nether biome source so
//                          TerraBlender-added nether biomes are included.
//                          Visit the nether at least once before running.
//
// After: python pythonScripts/worldgen/extract_noise_to_json.py

ServerEvents.commandRegistry(event => {
    const { commands: Commands } = event;

    event.register(
        Commands.literal('dump_overworld_noise')
            .requires(src => src.hasPermission(2))
            .executes(ctx => {
                const Regs  = Java.loadClass('net.minecraft.core.registries.Registries');
                const RL    = Java.loadClass('net.minecraft.resources.ResourceLocation');
                const pReg  = ctx.source.getServer().registryAccess()
                                 .registryOrThrow(Regs.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
                const pList = pReg.get(RL.parse('minecraft:overworld'));

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

                ctx.source.sendSuccess(Component.literal('§aDumped ' + entries.length + ' entries to logs/latest.log'), false);
                return 1;
            })
    );

    // ── /dump_all_noise — every registered parameter list + live nether ───────
    // Registry captures TerraBlender overworld regions. Nether is read from the
    // live level biome source because TerraBlender injects nether biomes there
    // at level creation, not into the parameter-list registry.
    event.register(
        Commands.literal('dump_all_noise')
            .requires(src => src.hasPermission(2))
            .executes(ctx => {
                const Regs        = Java.loadClass('net.minecraft.core.registries.Registries');
                const RL          = Java.loadClass('net.minecraft.resources.ResourceLocation');
                const ResourceKey = Java.loadClass('net.minecraft.resources.ResourceKey');
                const pReg        = ctx.source.getServer().registryAccess()
                                       .registryOrThrow(Regs.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);

                const toF     = v => Math.round(v / 10000.0 * 10000) / 10000;
                const toRange = p => [toF(p.min()), toF(p.max())];

                var result       = {};
                var dimCount     = 0;
                var totalEntries = 0;

                // Registry-based dump (overworld + any mods that register parameter lists)
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

                ctx.source.sendSuccess(Component.literal('§7Registry: ' + dimCount + ' dim(s), ' + totalEntries + ' entries'), false);

                // Live nether level — overrides registry entry with actual biome source
                // (captures TerraBlender-added nether biomes injected at level creation)
                try {
                    var netherLevel = null;
                    ctx.source.server.getAllLevels().forEach(function(lvl) {
                        var dimStr = lvl.dimension.toString();
                        if (dimStr === 'minecraft:the_nether' || dimStr === 'minecraft:nether') {
                            netherLevel = lvl;
                        }
                    });
                    if (!netherLevel) {
                        ctx.source.sendSuccess(Component.literal('§eNether not loaded — visit nether first for full nether biome dump'), false);
                    } else {
                        // possibleBiomes() is on BiomeSource (parent) — works even through Blueprint wrapper.
                        // Returns IDs only (no noise params). Params for mod biomes come from parse_bop_source.py.
                        var possible = netherLevel.getChunkSource().getGenerator().getBiomeSource().possibleBiomes();
                        var netherEntries = [];
                        possible.forEach(function(holder) {
                            netherEntries.push({
                                biome:      holder.unwrapKey().get().location().toString(),
                                parameters: null
                            });
                        });
                        netherEntries.sort(function(a, b) { return a.biome < b.biome ? -1 : 1; });
                        // Always write under 'minecraft:nether' to match registry key
                        totalEntries -= (result['minecraft:nether'] || []).length;
                        if (!result['minecraft:nether']) dimCount++;
                        result['minecraft:nether'] = netherEntries;
                        totalEntries += netherEntries.length;
                        ctx.source.sendSuccess(Component.literal('§aNether: ' + netherEntries.length + ' biomes (IDs only, no params)'), false);
                    }
                } catch (e) {
                    ctx.source.sendSuccess(Component.literal('§cNether error: ' + e), false);
                }

                console.log('===== ALL NOISE PARAMS START =====');
                console.log(JSON.stringify(result));
                console.log('===== ALL NOISE PARAMS END =====');

                ctx.source.sendSuccess(Component.literal('§aDone: ' + dimCount + ' dim(s), ' + totalEntries + ' total entries → logs/latest.log'), false);
                return 1;
            })
    );

});
