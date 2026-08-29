from pathlib import Path

ROOT    = Path(__file__).parent.parent.parent
SCRIPTS = ROOT / "pythonScripts"
WEBUI   = ROOT / "webui"

# extractors
EXTRACT_FROM_JARS       = SCRIPTS / "extractors/extract_from_jars.py"
EXTRACT_STRUCTURE_LOOT  = SCRIPTS / "extractors/extract_structure_loottables.py"
EXTRACT_BIOME_TAGS      = SCRIPTS / "extractors/extract_biome_tags.py"
EXTRACT_BIOME_BLOCKS    = SCRIPTS / "extractors/extract_biome_blocks.py"
EXTRACT_ITEM_TAGS       = SCRIPTS / "extractors/extract_item_tags.py"
EXTRACT_ENTITY_TEXTURES = SCRIPTS / "extractors/extract_textures.py"
EXTRACT_ITEM_TEXTURES   = SCRIPTS / "extractors/extract_item_textures.py"
EXTRACT_DIMENSION_BIOMES= SCRIPTS / "extractors/extract_dimension_biomes.py"
EXTRACT_FROM_LOG        = SCRIPTS / "extractors/extract_from_log.py"

# database
GENERATE_SQL            = SCRIPTS / "generate_sql.py"
CHECK_CONSISTENCY       = SCRIPTS / "check_consistency.py"

# graph
EXPORT_TO_NEO4J         = SCRIPTS / "export_to_neo4j.py"

# worldgen
EXTRACT_NOISE                = SCRIPTS / "worldgen/extract_noise_to_json.py"
SCAN_TERRABLENDER            = SCRIPTS / "worldgen/scan_terrablender.py"
PARSE_BOP_NOISE              = SCRIPTS / "worldgen/parse_bop_source.py"
PARSE_BWG_NOISE              = SCRIPTS / "worldgen/parse_bwg_source.py"
PARSE_NULLSCAPE              = SCRIPTS / "worldgen/parse_nullscape.py"
EXTRACT_PREHISTORIC_EXPANSION = SCRIPTS / "worldgen/extract_prehistoric_expansion.py"
CREATE_MINEUTZ_OCEANS         = SCRIPTS / "worldgen/create_mineutz_oceans.py"
COPY_BIOME                    = SCRIPTS / "worldgen/copy_biome.py"
MERGE_BIOME_PARAMETERS        = SCRIPTS / "worldgen/merge_biome_parameters.py"
COMPLETE_BIOME_PLANNER        = SCRIPTS / "worldgen/complete_biome_planner.py"
GENERATE_FILTER_REGION        = SCRIPTS / "worldgen/generate_filter_region.py"
GENERATE_DIMENSION_JSONS      = SCRIPTS / "worldgen/generate_dimension_jsons.py"
GENERATE_STRUCTURE_PLANNER    = SCRIPTS / "worldgen/generate_structure_planner.py"
GENERATE_STRUCTURE_TAGS       = SCRIPTS / "worldgen/generate_structure_tags.py"
GENERATE_BIOME_ADJACENCY_PLANNER = SCRIPTS / "worldgen/generate_biome_adjacency_planner.py"
