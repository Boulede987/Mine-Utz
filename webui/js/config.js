'use strict';

const MAX_BREADCRUMBS      = 8;
const AUTOCOMPLETE_LIMIT   = 12;
const AUTOCOMPLETE_DELAY   = 250;
const EXPAND_LIMIT         = 50;
const ZONE_BASE_DIST       = 300;
const ZONE_NODE_CELL       = 75;
const ZONE_HOP2_EXTRA_DIST = 180;

const DAGRE_QUERIES = new Set(['recipe', 'recipe_tree', 'explore']);

const LABEL_COLOR = {
  Item:      '#f4c430',
  Armor:     '#f4c430',
  Tool:      '#f4c430',
  Food:      '#f4c430',
  Entity:    '#6fb0d2',
  Biome:     '#5cb85c',
  Structure: '#c87850',
  Dimension: '#9b59b6',
  Recipe:    '#e05c5c',
};

const REL_COLOR = {
  PRODUCES:           '#f4c430',
  REQUIRES:           '#e05c5c',
  USES_STATION:       '#aaaaaa',
  DROPS:              '#6fb0d2',
  CONTAINS:           '#c87850',
  SPAWNS_IN:          '#5cb85c',
  FOUND_IN:           '#c87850',
  FOUND_IN_STRUCTURE: '#6fb0d2',
  IN_DIMENSION:       '#9b59b6',
};
