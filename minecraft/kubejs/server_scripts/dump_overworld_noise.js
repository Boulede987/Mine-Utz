// Dumps overworld multi_noise biome parameter list to logs/latest.log as JSON
// Run: /kubejs reload server_scripts  then  /dump_overworld_noise
// Then run: python pythonScripts/extract_overworld_noise.py

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
});
