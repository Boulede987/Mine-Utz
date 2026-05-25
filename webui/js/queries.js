'use strict';

function primaryLabel(labels) {
  for (const candidate of ['Entity', 'Biome', 'Structure', 'Dimension', 'Recipe', 'Block']) {
    if (labels.includes(candidate)) return candidate;
  }
  return 'Item';
}

function detectQueryType(labels) {
  const label = primaryLabel(labels);
  if (label === 'Entity')    return 'mob';
  if (label === 'Item')      return 'recipe';
  if (label === 'Biome')     return 'biome';
  if (label === 'Structure') return 'structure';
  if (label === 'Dimension') return 'dimension';
  if (label === 'Block')     return 'block';
  return 'explore';
}

// Cypher fragment: ranks exact match above contains match
const rankCase = (alias) =>
  `CASE WHEN toLower(${alias}.name) = toLower($searchTerm) OR toLower(${alias}.id) = toLower($searchTerm) OR ${alias}.id = 'minecraft:' + $searchTerm THEN 0 ELSE 1 END AS _rank`;

const AUTOCOMPLETE_CYPHER = `
  MATCH (node)
  WHERE toLower(node.name) STARTS WITH toLower($query)
     OR toLower(node.id)   STARTS WITH toLower($query)
     OR toLower(node.name) CONTAINS toLower($query)
  RETURN node.id AS id, node.name AS name, labels(node) AS labels
  ORDER BY
    CASE WHEN toLower(node.name) STARTS WITH toLower($query) THEN 0 ELSE 1 END,
    size(node.name)
  LIMIT ${AUTOCOMPLETE_LIMIT}
`;

const QUERIES = {
  // ── Items ──────────────────────────────────────────────────────────────────

  recipe: `
    MATCH (item:Item)
    WHERE toLower(item.name) CONTAINS toLower($searchTerm) OR toLower(item.id) CONTAINS toLower($searchTerm)
    WITH item, ${rankCase('item')} ORDER BY _rank LIMIT 1
    OPTIONAL MATCH (item)<-[produces:PRODUCES]-(recipe:Recipe)
    OPTIONAL MATCH (recipe)-[requires:REQUIRES]->(ingredient:Item)
    OPTIONAL MATCH (recipe)-[usesStation:USES_STATION]->(station:Item)
    RETURN item, produces, recipe, requires, ingredient, usesStation, station
  `,

  recipe_tree: `
    MATCH (root:Item)
    WHERE toLower(root.name) CONTAINS toLower($searchTerm) OR toLower(root.id) CONTAINS toLower($searchTerm)
    WITH root, ${rankCase('root')} ORDER BY _rank LIMIT 1
    OPTIONAL MATCH (root)<-[p1:PRODUCES]-(r1:Recipe)
    OPTIONAL MATCH (r1)-[req1:REQUIRES]->(ing1:Item)
    OPTIONAL MATCH (r1)-[us1:USES_STATION]->(st1:Item)
    WITH root, p1, r1, req1, ing1, us1, st1
    OPTIONAL MATCH (ing1)<-[p2:PRODUCES]-(r2:Recipe)
    OPTIONAL MATCH (r2)-[req2:REQUIRES]->(ing2:Item)
    OPTIONAL MATCH (r2)-[us2:USES_STATION]->(st2:Item)
    WITH root, p1, r1, req1, ing1, us1, st1, p2, r2, req2, ing2, us2, st2
    OPTIONAL MATCH (ing2)<-[p3:PRODUCES]-(r3:Recipe)
    OPTIONAL MATCH (r3)-[req3:REQUIRES]->(ing3:Item)
    OPTIONAL MATCH (r3)-[us3:USES_STATION]->(st3:Item)
    RETURN root, p1, r1, req1, ing1, us1, st1,
                 p2, r2, req2, ing2, us2, st2,
                 p3, r3, req3, ing3, us3, st3
    LIMIT 300
  `,

  sources: `
    MATCH (item:Item)
    WHERE toLower(item.name) CONTAINS toLower($searchTerm) OR toLower(item.id) CONTAINS toLower($searchTerm)
    WITH item, ${rankCase('item')} ORDER BY _rank LIMIT 1
    OPTIONAL MATCH (mob:Entity)-[drops:DROPS]->(item)
    OPTIONAL MATCH (structure:Structure)-[contains:CONTAINS]->(item)
    OPTIONAL MATCH (block:Block)-[mined:DROPS]->(item)
    RETURN item, drops, mob, contains, structure, mined, block
  `,

  source_tree: `
    MATCH (root:Item)
    WHERE toLower(root.name) CONTAINS toLower($searchTerm) OR toLower(root.id) CONTAINS toLower($searchTerm)
    WITH root, ${rankCase('root')} ORDER BY _rank LIMIT 1
    OPTIONAL MATCH (mob:Entity)-[drops:DROPS]->(root)
    OPTIONAL MATCH (chest:Structure)-[contains:CONTAINS]->(root)
    OPTIONAL MATCH (block:Block)-[mined:DROPS]->(root)
    OPTIONAL MATCH (root)<-[produces:PRODUCES]-(recipe:Recipe)
    OPTIONAL MATCH (recipe)-[requires:REQUIRES]->(ingredient:Item)
    OPTIONAL MATCH (recipe)-[usesStation:USES_STATION]->(station:Item)
    OPTIONAL MATCH (mob)-[spawnsIn:SPAWNS_IN]->(biome:Biome)-[inDim:IN_DIMENSION]->(dim:Dimension)
    OPTIONAL MATCH (mob)-[foundIn:FOUND_IN_STRUCTURE]->(mobStruct:Structure)
    RETURN root, drops, mob, contains, chest, mined, block,
           produces, recipe, requires, ingredient, usesStation, station,
           spawnsIn, biome, inDim, dim, foundIn, mobStruct
  `,

  // ── Blocks ─────────────────────────────────────────────────────────────────

  block: `
    MATCH (block:Block)
    WHERE toLower(block.name) CONTAINS toLower($searchTerm) OR toLower(block.id) CONTAINS toLower($searchTerm)
    WITH block, ${rankCase('block')} ORDER BY _rank LIMIT 1
    OPTIONAL MATCH (block)-[drops:DROPS]->(item:Item)
    RETURN block, drops, item
  `,

  // ── Entities ───────────────────────────────────────────────────────────────

  mob: `
    MATCH (entity:Entity)
    WHERE toLower(entity.name) CONTAINS toLower($searchTerm) OR toLower(entity.id) CONTAINS toLower($searchTerm)
    WITH entity, ${rankCase('entity')} ORDER BY _rank LIMIT 1
    OPTIONAL MATCH (entity)-[spawnsIn:SPAWNS_IN]->(biome:Biome)-[inDim:IN_DIMENSION]->(dim:Dimension)
    OPTIONAL MATCH (entity)-[drops:DROPS]->(item:Item)
    OPTIONAL MATCH (entity)-[foundIn:FOUND_IN_STRUCTURE]->(structure:Structure)
    RETURN entity, spawnsIn, biome, inDim, dim, drops, item, foundIn, structure
  `,

  // ── World ──────────────────────────────────────────────────────────────────

  biome: `
    MATCH (biome:Biome)
    WHERE toLower(biome.name) CONTAINS toLower($searchTerm) OR toLower(biome.id) CONTAINS toLower($searchTerm)
    WITH biome, ${rankCase('biome')} ORDER BY _rank LIMIT 1
    OPTIONAL MATCH (biome)-[inDim:IN_DIMENSION]->(dim:Dimension)
    OPTIONAL MATCH (mob:Entity)-[spawnsIn:SPAWNS_IN]->(biome)
    OPTIONAL MATCH (structure:Structure)-[foundIn:FOUND_IN]->(biome)
    RETURN biome, inDim, dim, spawnsIn, mob, foundIn, structure
  `,

  structure: `
    MATCH (structure:Structure)
    WHERE toLower(structure.name) CONTAINS toLower($searchTerm) OR toLower(structure.id) CONTAINS toLower($searchTerm)
    WITH structure, ${rankCase('structure')} ORDER BY _rank LIMIT 1
    OPTIONAL MATCH (structure)-[contains:CONTAINS]->(item:Item)
    OPTIONAL MATCH (mob:Entity)-[foundIn:FOUND_IN_STRUCTURE]->(structure)
    OPTIONAL MATCH (structure)-[inBiome:FOUND_IN]->(biome:Biome)-[inDim:IN_DIMENSION]->(dim:Dimension)
    RETURN structure, contains, item, foundIn, mob, inBiome, biome, inDim, dim
  `,

  dimension: `
    MATCH (dim:Dimension)
    WHERE toLower(dim.name) CONTAINS toLower($searchTerm) OR toLower(dim.id) CONTAINS toLower($searchTerm)
    WITH dim, ${rankCase('dim')} ORDER BY _rank LIMIT 1
    OPTIONAL MATCH (biome:Biome)-[inDim:IN_DIMENSION]->(dim)
    OPTIONAL MATCH (mob:Entity)-[spawnsIn:SPAWNS_IN]->(biome)
    OPTIONAL MATCH (structure:Structure)-[foundIn:FOUND_IN]->(biome)
    RETURN dim, inDim, biome, spawnsIn, mob, foundIn, structure
  `,

  // ── Generic ────────────────────────────────────────────────────────────────

  explore: `
    MATCH (node)
    WHERE toLower(node.name) CONTAINS toLower($searchTerm) OR toLower(node.id) CONTAINS toLower($searchTerm)
    WITH node, ${rankCase('node')} ORDER BY _rank LIMIT 1
    MATCH (node)-[rel]-(neighbour)
    RETURN node, rel, neighbour
    LIMIT 60
  `,
};
