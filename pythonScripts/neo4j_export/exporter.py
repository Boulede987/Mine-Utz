from .readers import read_ids
from .textures import export_textures
from .nodes import (
    clear_graph, create_constraints,
    export_items, export_entities, export_biomes, export_structures, export_dimensions,
)
from .edges import (
    export_recipes, export_entity_loot, export_chest_loot,
    export_biome_spawns, export_structure_biomes, export_dimension_biomes,
    export_structure_spawns, export_block_drops,
)


def export(driver) -> None:
    with driver.session() as session:
        print("Exporting textures...")
        texture_map = export_textures()

        print("Clearing existing graph...")
        clear_graph(session)

        print("Creating constraints...")
        create_constraints(session)

        print("Loading items...")
        item_ids = read_ids('items.txt')
        export_items(session, item_ids, texture_map)

        print("Loading entities...")
        entity_ids = read_ids('mobs.txt')
        export_entities(session, entity_ids, texture_map)

        print("Loading biomes...")
        biome_ids = read_ids('biomes.txt')
        export_biomes(session, biome_ids)

        print("Loading structures...")
        structure_ids = read_ids('structures.txt')
        export_structures(session, structure_ids)

        print("Loading dimensions...")
        dim_ids = read_ids('dimensions.txt')
        export_dimensions(session, dim_ids)

        print("Loading recipes...")
        export_recipes(session, item_ids)

        print("Loading entity loot...")
        export_entity_loot(session, entity_ids, item_ids)

        print("Loading structure chest loot...")
        export_chest_loot(session, structure_ids, item_ids)

        print("Loading biome spawns...")
        export_biome_spawns(session, biome_ids, entity_ids)

        print("Loading structure biomes...")
        export_structure_biomes(session, structure_ids, biome_ids)

        print("Loading dimension biomes...")
        export_dimension_biomes(session, dim_ids, biome_ids)

        print("Loading structure spawns...")
        export_structure_spawns(session, structure_ids, entity_ids)

        print("Loading block mining drops...")
        export_block_drops(session, item_ids)

        print("\nExport complete. Open http://localhost:7474 in your browser.")
        print("Example query:")
        print("  MATCH (i:Item {name:'diamond_sword'})<-[:PRODUCES]-(r:Recipe)-[:REQUIRES]->(ing:Item) RETURN *")
