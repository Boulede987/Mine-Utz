-- ============================================================
-- diamonds.sql  —  all diamond-related queries
-- ============================================================
USE MineutzDB;


-- ------------------------------------------------------------
-- 1. Entities that drop diamonds
--    Comprises -> LootTableEntity -> Entity
--    (vanilla: no mob drops diamonds directly)
-- ------------------------------------------------------------
SELECT
    e.name          AS entity,
    c.minNumber     AS min_drop,
    c.maxNumber     AS max_drop,
    c.weight        AS weight
FROM Comprises c
JOIN LootTableEntity lte ON lte.loottable_id = c.loottable_id
JOIN Entity e
    ON  e.namespace = lte.entity_namespace
    AND e.name      = lte.entity_name
WHERE c.item_namespace = 'minecraft'
  AND c.item_name      = 'diamond'
ORDER BY e.name;


-- ------------------------------------------------------------
-- 2. Structure chests that contain diamonds
--    Comprises -> LootTableStructure -> Structure
-- ------------------------------------------------------------
SELECT
    lts.structure_name  AS structure,
    c.minNumber         AS min_drop,
    c.maxNumber         AS max_drop,
    c.weight            AS weight
FROM Comprises c
JOIN LootTableStructure lts ON lts.loottable_id = c.loottable_id
WHERE c.item_namespace = 'minecraft'
  AND c.item_name      = 'diamond'
ORDER BY lts.structure_name, c.weight DESC;


-- ------------------------------------------------------------
-- 3. Biomes where structures with diamond chests generate
--    Comprises -> LootTableStructure -> StructureBiome -> Biome
-- ------------------------------------------------------------
SELECT
    lts.structure_name  AS structure,
    sb.biome_name       AS biome,
    c.minNumber         AS min_drop,
    c.maxNumber         AS max_drop,
    c.weight            AS weight
FROM Comprises c
JOIN LootTableStructure lts
    ON  lts.loottable_id = c.loottable_id
JOIN StructureBiome sb
    ON  sb.structure_namespace = lts.structure_namespace
    AND sb.structure_name      = lts.structure_name
WHERE c.item_namespace = 'minecraft'
  AND c.item_name      = 'diamond'
ORDER BY lts.structure_name, sb.biome_name;


-- ------------------------------------------------------------
-- 4. Biomes where diamond-dropping entities spawn
--    (vanilla: always empty — no mob drops diamonds directly)
-- ------------------------------------------------------------
SELECT
    b.name      AS biome,
    e.name      AS entity,
    bst.weight  AS spawn_weight,
    bst.minCount,
    bst.maxCount
FROM BiomeSpawnTable bst
JOIN Biome b
    ON  b.namespace = bst.biome_namespace
    AND b.name      = bst.biome_name
JOIN Entity e
    ON  e.namespace = bst.entity_namespace
    AND e.name      = bst.entity_name
WHERE e.namespace = 'minecraft'
  AND e.name IN (
      SELECT DISTINCT lte.entity_name
      FROM Comprises c
      JOIN LootTableEntity lte ON lte.loottable_id = c.loottable_id
      WHERE c.item_namespace = 'minecraft'
        AND c.item_name      = 'diamond'
  )
ORDER BY b.name, e.name;


-- ------------------------------------------------------------
-- 5. Recipes that USE diamonds as an ingredient
-- ------------------------------------------------------------
SELECT
    ri.recipe_name  AS recipe,
    r.type          AS recipe_type,
    rs.station_name AS station,
    ro.item_name    AS output,
    ro.count        AS output_count
FROM RecipeIngredient ri
JOIN Recipe r
    ON  r.namespace = ri.recipe_namespace
    AND r.name      = ri.recipe_name
LEFT JOIN RecipeOutput ro
    ON  ro.recipe_namespace = r.namespace
    AND ro.recipe_name      = r.name
LEFT JOIN RecipeStation rs
    ON  rs.recipe_namespace = r.namespace
    AND rs.recipe_name      = r.name
WHERE ri.item_namespace = 'minecraft'
  AND ri.item_name      = 'diamond'
ORDER BY ri.recipe_name;


-- ------------------------------------------------------------
-- 6. Recipes that PRODUCE diamonds
-- ------------------------------------------------------------
SELECT
    ro.recipe_name  AS recipe,
    r.type          AS recipe_type,
    rs.station_name AS station,
    ro.count        AS output_count
FROM RecipeOutput ro
JOIN Recipe r
    ON  r.namespace = ro.recipe_namespace
    AND r.name      = ro.recipe_name
LEFT JOIN RecipeStation rs
    ON  rs.recipe_namespace = r.namespace
    AND rs.recipe_name      = r.name
WHERE ro.item_namespace = 'minecraft'
  AND ro.item_name      = 'diamond'
ORDER BY ro.recipe_name;





SELECT namespace, name, LEFT(texture_b64, 60) AS texture_preview
  FROM Entity
  WHERE namespace = 'minecraft' AND name = 'creeper';