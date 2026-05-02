-- ==================================================
-- 15 - Minecraft item subtypes (Tool, Armor, Food)
-- ==================================================

USE MineutzDB;

-- Tool — classified via item tags; attack stats from /dump_tool_stats when available
INSERT INTO Tool (item_namespace, item_name, miningEfficiency, miningLevel, attackDamage, attackSpeed, attackRange, durability, enchantability) VALUES
  ('minecraft', 'diamond_axe', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'diamond_hoe', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'diamond_pickaxe', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'diamond_shovel', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'diamond_sword', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'golden_axe', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'golden_hoe', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'golden_pickaxe', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'golden_shovel', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'golden_sword', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'iron_axe', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'iron_hoe', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'iron_pickaxe', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'iron_shovel', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'iron_sword', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'netherite_axe', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'netherite_hoe', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'netherite_pickaxe', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'netherite_shovel', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'netherite_sword', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'stone_axe', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'stone_hoe', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'stone_pickaxe', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'stone_shovel', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'stone_sword', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'wooden_axe', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'wooden_hoe', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'wooden_pickaxe', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'wooden_shovel', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'wooden_sword', NULL, NULL, NULL, NULL, NULL, NULL, NULL);

-- Armor — classified via item tags; stats from /dump_armor_stats when available
INSERT INTO Armor (item_namespace, item_name, armorPoints, armorToughness, durability, enchantability, knockbackResistance) VALUES
  ('minecraft', 'chainmail_boots', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'chainmail_chestplate', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'chainmail_helmet', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'chainmail_leggings', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'diamond_boots', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'diamond_chestplate', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'diamond_helmet', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'diamond_leggings', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'golden_boots', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'golden_chestplate', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'golden_helmet', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'golden_leggings', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'iron_boots', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'iron_chestplate', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'iron_helmet', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'iron_leggings', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'leather_boots', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'leather_chestplate', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'leather_helmet', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'leather_leggings', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'netherite_boots', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'netherite_chestplate', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'netherite_helmet', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'netherite_leggings', NULL, NULL, NULL, NULL, NULL),
  ('minecraft', 'turtle_helmet', NULL, NULL, NULL, NULL, NULL);

-- Food — from /dump_food_stats when available, else item tags
INSERT INTO Food (item_namespace, item_name, hungerRestoration, saturation) VALUES
  ('minecraft', 'beef', NULL, NULL),
  ('minecraft', 'chicken', NULL, NULL),
  ('minecraft', 'cod', NULL, NULL),
  ('minecraft', 'cooked_beef', NULL, NULL),
  ('minecraft', 'cooked_chicken', NULL, NULL),
  ('minecraft', 'cooked_cod', NULL, NULL),
  ('minecraft', 'cooked_mutton', NULL, NULL),
  ('minecraft', 'cooked_porkchop', NULL, NULL),
  ('minecraft', 'cooked_rabbit', NULL, NULL),
  ('minecraft', 'cooked_salmon', NULL, NULL),
  ('minecraft', 'mutton', NULL, NULL),
  ('minecraft', 'porkchop', NULL, NULL),
  ('minecraft', 'pufferfish', NULL, NULL),
  ('minecraft', 'rabbit', NULL, NULL),
  ('minecraft', 'rotten_flesh', NULL, NULL),
  ('minecraft', 'salmon', NULL, NULL),
  ('minecraft', 'tropical_fish', NULL, NULL);
