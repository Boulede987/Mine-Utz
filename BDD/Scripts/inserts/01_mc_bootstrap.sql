-- ==================================================
-- 01 - Minecraft bootstrap (Rarity, Licence, Author, Mod)
-- ==================================================

USE MineutzDB;

-- Rarity tiers
INSERT INTO Rarity (id, tier) VALUES
  (1, 'COMMON'),
  (2, 'UNCOMMON'),
  (3, 'RARE'),
  (4, 'EPIC');

-- Licence
INSERT INTO Licence (id, name) VALUES
  (1, 'Mojang EULA');

-- Author
INSERT INTO Author (id, name) VALUES
  (1, 'Mojang Studios');

-- Mod — vanilla Minecraft 1.21.1
INSERT INTO `Mod` (namespace, display_name, version, licence_id) VALUES
  ('minecraft', 'Minecraft', '1.21.1', 1);

-- Source
INSERT INTO Source (namespace, site, url) VALUES
  ('minecraft', 'Official', 'https://minecraft.net');

-- DeveloppedBy
INSERT INTO DeveloppedBy (namespace, author_id) VALUES
  ('minecraft', 1);
