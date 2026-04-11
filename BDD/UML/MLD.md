Rarity(<u>id</u>, tier)

Licence(<u>id</u>, name)

Author(<u>id</u>, name)

Mod(<u>namespace</u>, display_name, version, Licence.id)

Source(<u>id</u>, site, url, Mod.namespace)

DeveloppedBy(<u>Mod.namespace, Author.id</u>)

LootTable(<u>id</u>)

LootTableReference(<u>LootTable.id[parent], LootTable.id[child]</u>)

Biome(<u>namespace, name</u>, mainColor, temperature, Mod.namespace)

Structure(<u>namespace, name</u>, LootTable.id, Mod.namespace)

StructureBiome(<u>Structure.namespace, Structure.name, Biome.namespace, Biome.name</u>)

LootTableStructure(<u>LootTable.id, Structure.namespace, Structure.name</u>)

Item(<u>namespace, name</u>, renewable, stackable, Rarity.id, Mod.namespace)

Tool(<u>Item.namespace, Item.name</u>, miningEfficiency, miningLevel, attackDamage, attackSpeed, attackRange, durability, enchantability)

Armor(<u>Item.namespace, Item.name</u>, armorPoints, armorToughness, durability, enchantability, knockbackResistance)

Food(<u>Item.namespace, Item.name</u>, hungerRestoration, saturation)

Block(<u>Item.namespace, Item.name</u>, blastResistance, hardness, luminous, transparent, flammable)

Station(<u>Item.namespace, Item.name</u>)

Comprises(<u>LootTable.id, Item.namespace, Item.name</u>, minNumber, maxNumber, weight)

Entity(<u>namespace, name</u>, behavior, type, healthPoints, armorPoints, attackDamage, attackSpeed, attackRange, speed, knockbackResistance, weakness, hitboxSize, LootTable.id, Mod.namespace)

LootTableEntity(<u>LootTable.id, Entity.namespace, Entity.name</u>)

BiomeSpawnTable(<u>Biome.namespace, Biome.name, Entity.namespace, Entity.name</u>, weight, minCount, maxCount)

StructureSpawnTable(<u>Structure.namespace, Structure.name, Entity.namespace, Entity.name</u>)

BlockBiomeGenerationTable(<u>Block.namespace, Block.name, Biome.namespace, Biome.name</u>)

BlockStructureGenerationTable(<u>Block.namespace, Block.name, Structure.namespace, Structure.name</u>, number)

Recipe(<u>namespace, name</u>, type, Mod.namespace)

RecipeOutput(<u>Recipe.namespace, Recipe.name, Item.namespace, Item.name</u>, count)

RecipeIngredient(<u>Recipe.namespace, Recipe.name, Item.namespace, Item.name</u>, quantity)

RecipeStation(<u>Recipe.namespace, Recipe.name, Station.namespace, Station.name</u>)
