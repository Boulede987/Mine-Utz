-- ==================================================
-- 05 - Minecraft structures
-- ==================================================

USE MineutzDB;

-- Structure — vanilla 1.21.1  (loottable_id is NULL — populated in loot step)
INSERT INTO Structure (namespace, name, loottable_id) VALUES
  ('minecraft', 'ancient_city', NULL),
  ('minecraft', 'bastion_remnant', NULL),
  ('minecraft', 'buried_treasure', NULL),
  ('minecraft', 'desert_pyramid', NULL),
  ('minecraft', 'end_city', NULL),
  ('minecraft', 'fortress', NULL),
  ('minecraft', 'igloo', NULL),
  ('minecraft', 'jungle_pyramid', NULL),
  ('minecraft', 'mansion', NULL),
  ('minecraft', 'mineshaft', NULL),
  ('minecraft', 'mineshaft_mesa', NULL),
  ('minecraft', 'monument', NULL),
  ('minecraft', 'nether_fossil', NULL),
  ('minecraft', 'ocean_ruin_cold', NULL),
  ('minecraft', 'ocean_ruin_warm', NULL),
  ('minecraft', 'pillager_outpost', NULL),
  ('minecraft', 'ruined_portal', NULL),
  ('minecraft', 'ruined_portal_desert', NULL),
  ('minecraft', 'ruined_portal_jungle', NULL),
  ('minecraft', 'ruined_portal_mountain', NULL),
  ('minecraft', 'ruined_portal_nether', NULL),
  ('minecraft', 'ruined_portal_ocean', NULL),
  ('minecraft', 'ruined_portal_swamp', NULL),
  ('minecraft', 'shipwreck', NULL),
  ('minecraft', 'shipwreck_beached', NULL),
  ('minecraft', 'stronghold', NULL),
  ('minecraft', 'swamp_hut', NULL),
  ('minecraft', 'trail_ruins', NULL),
  ('minecraft', 'trial_chambers', NULL),
  ('minecraft', 'village_desert', NULL),
  ('minecraft', 'village_plains', NULL),
  ('minecraft', 'village_savanna', NULL),
  ('minecraft', 'village_snowy', NULL),
  ('minecraft', 'village_taiga', NULL);
