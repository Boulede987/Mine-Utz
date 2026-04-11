-- =====================================
-- CLEANUP
-- =====================================
DROP DATABASE IF EXISTS MineutzDB;
CREATE DATABASE MineutzDB;
USE MineutzDB;

-- =====================================
-- INDEPENDENT BASE TABLES
-- =====================================

CREATE TABLE Rarity (
    id   INT PRIMARY KEY AUTO_INCREMENT,
    tier VARCHAR(50) NOT NULL UNIQUE
) ENGINE=InnoDB;

CREATE TABLE Licence (
    id   INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL UNIQUE
) ENGINE=InnoDB;

CREATE TABLE Author (
    id   INT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL
) ENGINE=InnoDB;

-- =====================================
-- MOD
-- =====================================

CREATE TABLE `Mod` (
    namespace    VARCHAR(100) PRIMARY KEY,
    display_name VARCHAR(100),
    version      VARCHAR(50),
    licence_id   INT,
    FOREIGN KEY (licence_id) REFERENCES Licence(id)
) ENGINE=InnoDB;

CREATE TABLE Source (
    id        INT PRIMARY KEY AUTO_INCREMENT,
    namespace VARCHAR(100) NOT NULL,
    site      VARCHAR(100),
    url       VARCHAR(255),
    FOREIGN KEY (namespace) REFERENCES `Mod`(namespace)
) ENGINE=InnoDB;

CREATE TABLE DeveloppedBy (
    namespace VARCHAR(100),
    author_id INT,
    PRIMARY KEY (namespace, author_id),
    FOREIGN KEY (namespace) REFERENCES `Mod`(namespace),
    FOREIGN KEY (author_id) REFERENCES Author(id)
) ENGINE=InnoDB;

-- =====================================
-- LOOT
-- =====================================

CREATE TABLE LootTable (
    id INT PRIMARY KEY AUTO_INCREMENT
) ENGINE=InnoDB;

CREATE TABLE LootTableReference (
    parent_id INT,
    child_id  INT,
    PRIMARY KEY (parent_id, child_id),
    FOREIGN KEY (parent_id) REFERENCES LootTable(id),
    FOREIGN KEY (child_id)  REFERENCES LootTable(id)
) ENGINE=InnoDB;

-- =====================================
-- WORLD
-- =====================================

CREATE TABLE Biome (
    namespace   VARCHAR(100) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    mainColor   VARCHAR(20),
    temperature FLOAT,
    PRIMARY KEY (namespace, name),
    FOREIGN KEY (namespace) REFERENCES `Mod`(namespace)
) ENGINE=InnoDB;

CREATE TABLE Structure (
    namespace    VARCHAR(100) NOT NULL,
    name         VARCHAR(100) NOT NULL,
    loottable_id INT,
    PRIMARY KEY (namespace, name),
    FOREIGN KEY (namespace)    REFERENCES `Mod`(namespace),
    FOREIGN KEY (loottable_id) REFERENCES LootTable(id)
) ENGINE=InnoDB;

CREATE TABLE StructureBiome (
    structure_namespace VARCHAR(100),
    structure_name      VARCHAR(100),
    biome_namespace     VARCHAR(100),
    biome_name          VARCHAR(100),
    PRIMARY KEY (structure_namespace, structure_name, biome_namespace, biome_name),
    FOREIGN KEY (structure_namespace, structure_name) REFERENCES Structure(namespace, name),
    FOREIGN KEY (biome_namespace,     biome_name)     REFERENCES Biome(namespace, name)
) ENGINE=InnoDB;

-- =====================================
-- ITEMS
-- =====================================

CREATE TABLE Item (
    namespace VARCHAR(100) NOT NULL,
    name      VARCHAR(100) NOT NULL,
    rarity_id INT,
    renewable BOOLEAN DEFAULT NULL,
    stackable BOOLEAN DEFAULT NULL,
    PRIMARY KEY (namespace, name),
    FOREIGN KEY (namespace) REFERENCES `Mod`(namespace),
    FOREIGN KEY (rarity_id) REFERENCES Rarity(id)
) ENGINE=InnoDB;

CREATE TABLE Tool (
    item_namespace   VARCHAR(100),
    item_name        VARCHAR(100),
    miningEfficiency FLOAT,
    miningLevel      INT,
    attackDamage     FLOAT,
    attackSpeed      FLOAT,
    attackRange      FLOAT,
    durability       INT,
    enchantability   INT,
    PRIMARY KEY (item_namespace, item_name),
    FOREIGN KEY (item_namespace, item_name) REFERENCES Item(namespace, name)
) ENGINE=InnoDB;

CREATE TABLE Armor (
    item_namespace      VARCHAR(100),
    item_name           VARCHAR(100),
    armorPoints         FLOAT,
    armorToughness      FLOAT,
    durability          INT,
    enchantability      INT,
    knockbackResistance FLOAT,
    PRIMARY KEY (item_namespace, item_name),
    FOREIGN KEY (item_namespace, item_name) REFERENCES Item(namespace, name)
) ENGINE=InnoDB;

CREATE TABLE Food (
    item_namespace    VARCHAR(100),
    item_name         VARCHAR(100),
    hungerRestoration INT,
    saturation        FLOAT,
    PRIMARY KEY (item_namespace, item_name),
    FOREIGN KEY (item_namespace, item_name) REFERENCES Item(namespace, name)
) ENGINE=InnoDB;

CREATE TABLE Block (
    item_namespace  VARCHAR(100),
    item_name       VARCHAR(100),
    blastResistance FLOAT,
    hardness        FLOAT,
    luminous        BOOLEAN DEFAULT NULL,
    transparent     BOOLEAN DEFAULT NULL,
    flammable       BOOLEAN DEFAULT NULL,
    PRIMARY KEY (item_namespace, item_name),
    FOREIGN KEY (item_namespace, item_name) REFERENCES Item(namespace, name)
) ENGINE=InnoDB;

CREATE TABLE Station (
    item_namespace VARCHAR(100),
    item_name      VARCHAR(100),
    PRIMARY KEY (item_namespace, item_name),
    FOREIGN KEY (item_namespace, item_name) REFERENCES Item(namespace, name)
) ENGINE=InnoDB;

-- =====================================
-- ENTITIES
-- =====================================

CREATE TABLE Entity (
    namespace           VARCHAR(100) NOT NULL,
    name                VARCHAR(100) NOT NULL,
    behavior            VARCHAR(100),
    type                VARCHAR(50),
    healthPoints        FLOAT,
    armorPoints         FLOAT,
    attackDamage        FLOAT,
    attackSpeed         FLOAT,
    attackRange         FLOAT,
    speed               FLOAT,
    knockbackResistance FLOAT,
    weakness            VARCHAR(100),
    hitboxSize          FLOAT,
    loottable_id        INT,
    PRIMARY KEY (namespace, name),
    FOREIGN KEY (namespace)    REFERENCES `Mod`(namespace),
    FOREIGN KEY (loottable_id) REFERENCES LootTable(id)
) ENGINE=InnoDB;

CREATE TABLE BiomeSpawnTable (
    biome_namespace  VARCHAR(100),
    biome_name       VARCHAR(100),
    entity_namespace VARCHAR(100),
    entity_name      VARCHAR(100),
    weight           INT DEFAULT 1,
    minCount         INT DEFAULT 1,
    maxCount         INT DEFAULT 1,
    PRIMARY KEY (biome_namespace, biome_name, entity_namespace, entity_name),
    FOREIGN KEY (biome_namespace,  biome_name)  REFERENCES Biome(namespace, name),
    FOREIGN KEY (entity_namespace, entity_name) REFERENCES Entity(namespace, name)
) ENGINE=InnoDB;

CREATE TABLE StructureSpawnTable (
    structure_namespace VARCHAR(100),
    structure_name      VARCHAR(100),
    entity_namespace    VARCHAR(100),
    entity_name         VARCHAR(100),
    PRIMARY KEY (structure_namespace, structure_name, entity_namespace, entity_name),
    FOREIGN KEY (structure_namespace, structure_name) REFERENCES Structure(namespace, name),
    FOREIGN KEY (entity_namespace,    entity_name)    REFERENCES Entity(namespace, name)
) ENGINE=InnoDB;

-- =====================================
-- LOOT RELATIONS
-- =====================================

CREATE TABLE Comprises (
    loottable_id   INT,
    item_namespace VARCHAR(100),
    item_name      VARCHAR(100),
    minNumber      INT DEFAULT 1,
    maxNumber      INT DEFAULT 1,
    weight         INT DEFAULT 1,
    PRIMARY KEY (loottable_id, item_namespace, item_name),
    FOREIGN KEY (loottable_id)              REFERENCES LootTable(id),
    FOREIGN KEY (item_namespace, item_name) REFERENCES Item(namespace, name)
) ENGINE=InnoDB;

CREATE TABLE LootTableStructure (
    loottable_id        INT,
    structure_namespace VARCHAR(100),
    structure_name      VARCHAR(100),
    PRIMARY KEY (loottable_id, structure_namespace, structure_name),
    FOREIGN KEY (loottable_id)                        REFERENCES LootTable(id),
    FOREIGN KEY (structure_namespace, structure_name) REFERENCES Structure(namespace, name)
) ENGINE=InnoDB;

CREATE TABLE LootTableEntity (
    loottable_id     INT,
    entity_namespace VARCHAR(100),
    entity_name      VARCHAR(100),
    PRIMARY KEY (loottable_id, entity_namespace, entity_name),
    FOREIGN KEY (loottable_id)                REFERENCES LootTable(id),
    FOREIGN KEY (entity_namespace, entity_name) REFERENCES Entity(namespace, name)
) ENGINE=InnoDB;

-- =====================================
-- BLOCKS IN WORLD
-- =====================================

CREATE TABLE BlockBiomeGenerationTable (
    block_namespace VARCHAR(100),
    block_name      VARCHAR(100),
    biome_namespace VARCHAR(100),
    biome_name      VARCHAR(100),
    PRIMARY KEY (block_namespace, block_name, biome_namespace, biome_name),
    FOREIGN KEY (block_namespace, block_name) REFERENCES Block(item_namespace, item_name),
    FOREIGN KEY (biome_namespace, biome_name) REFERENCES Biome(namespace, name)
) ENGINE=InnoDB;

CREATE TABLE BlockStructureGenerationTable (
    block_namespace     VARCHAR(100),
    block_name          VARCHAR(100),
    structure_namespace VARCHAR(100),
    structure_name      VARCHAR(100),
    number              INT DEFAULT 1,
    PRIMARY KEY (block_namespace, block_name, structure_namespace, structure_name),
    FOREIGN KEY (block_namespace,     block_name)     REFERENCES Block(item_namespace, item_name),
    FOREIGN KEY (structure_namespace, structure_name) REFERENCES Structure(namespace, name)
) ENGINE=InnoDB;

-- =====================================
-- RECIPES
-- =====================================

CREATE TABLE Recipe (
    namespace VARCHAR(100) NOT NULL,
    name      VARCHAR(200) NOT NULL,
    type      VARCHAR(100),
    PRIMARY KEY (namespace, name),
    FOREIGN KEY (namespace) REFERENCES `Mod`(namespace)
) ENGINE=InnoDB;

CREATE TABLE RecipeOutput (
    recipe_namespace VARCHAR(100),
    recipe_name      VARCHAR(200),
    item_namespace   VARCHAR(100),
    item_name        VARCHAR(100),
    count            INT DEFAULT 1,
    PRIMARY KEY (recipe_namespace, recipe_name, item_namespace, item_name),
    FOREIGN KEY (recipe_namespace, recipe_name) REFERENCES Recipe(namespace, name),
    FOREIGN KEY (item_namespace,   item_name)   REFERENCES Item(namespace, name)
) ENGINE=InnoDB;

CREATE TABLE RecipeIngredient (
    recipe_namespace VARCHAR(100),
    recipe_name      VARCHAR(200),
    item_namespace   VARCHAR(100),
    item_name        VARCHAR(100),
    quantity         INT DEFAULT 1,
    PRIMARY KEY (recipe_namespace, recipe_name, item_namespace, item_name),
    FOREIGN KEY (recipe_namespace, recipe_name) REFERENCES Recipe(namespace, name),
    FOREIGN KEY (item_namespace,   item_name)   REFERENCES Item(namespace, name)
) ENGINE=InnoDB;

CREATE TABLE RecipeStation (
    recipe_namespace  VARCHAR(100),
    recipe_name       VARCHAR(200),
    station_namespace VARCHAR(100),
    station_name      VARCHAR(100),
    PRIMARY KEY (recipe_namespace, recipe_name, station_namespace, station_name),
    FOREIGN KEY (recipe_namespace,  recipe_name)  REFERENCES Recipe(namespace, name),
    FOREIGN KEY (station_namespace, station_name) REFERENCES Station(item_namespace, item_name)
) ENGINE=InnoDB;