-- ==================================================
-- 12 - Minecraft structure spawn overrides
-- ==================================================

USE MineutzDB;

-- StructureSpawnTable — entities that spawn inside structures
INSERT INTO StructureSpawnTable (structure_namespace, structure_name, entity_namespace, entity_name) VALUES
  ('minecraft', 'fortress', 'minecraft', 'blaze'),
  ('minecraft', 'fortress', 'minecraft', 'magma_cube'),
  ('minecraft', 'fortress', 'minecraft', 'skeleton'),
  ('minecraft', 'fortress', 'minecraft', 'wither_skeleton'),
  ('minecraft', 'fortress', 'minecraft', 'zombified_piglin'),
  ('minecraft', 'monument', 'minecraft', 'guardian'),
  ('minecraft', 'pillager_outpost', 'minecraft', 'pillager'),
  ('minecraft', 'swamp_hut', 'minecraft', 'cat'),
  ('minecraft', 'swamp_hut', 'minecraft', 'witch');
