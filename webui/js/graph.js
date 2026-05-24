'use strict';

// ── Shared Application State ──────────────────────────────────────────

let driver       = null;
let selectedNode = null;
let lastRootId   = null;

const pinnedNodes  = new Set();   // cytoscape node IDs the user has drag-pinned
const breadcrumbs  = [];          // { label, rootId, elements[], neoMapping[] }
const neoToCytoMap = new Map();   // neo4j elementId → cytoscape node ID

// ── Neo4j Value Helpers ───────────────────────────────────────────────

function getNeoId(neoNode) {
  return neoNode.elementId ?? neoNode.identity?.toString() ?? String(neoNode.identity);
}

function isNeo4jNode(value) {
  return value != null && typeof value === 'object' && Array.isArray(value.labels) &&
         (value.elementId != null || value.identity != null);
}

function isNeo4jRel(value) {
  return value != null && typeof value === 'object' && typeof value.type === 'string' &&
         (value.startNodeElementId != null || value.start != null);
}

function getRelSourceId(rel) { return rel.startNodeElementId ?? rel.start?.toString(); }
function getRelTargetId(rel) { return rel.endNodeElementId   ?? rel.end?.toString(); }

// ── Cytoscape Initialization ──────────────────────────────────────────

const cy = cytoscape({
  container: document.getElementById('cy'),
  elements: [],
  style: [
    {
      selector: 'node',
      style: {
        label: 'data(label)',
        'font-size': 11,
        color: '#e4e4f0',
        'text-valign': 'bottom',
        'text-margin-y': 4,
        'text-outline-width': 2,
        'text-outline-color': '#0f0f13',
        'background-color': 'data(color)',
        'border-width': 2,
        'border-color': 'rgba(255,255,255,0.15)',
        width: 36,
        height: 36,
      }
    },
    {
      selector: 'node[image]',
      style: {
        'background-image': 'data(image)',
        'background-fit': 'cover',
        'background-clip': 'none',
        'border-width': 3,
        'border-color': '#6fb0d2',
        width: 40,
        height: 40,
      }
    },
    {
      selector: 'node[?pinned]',
      style: {
        'border-width': 3,
        'border-style': 'dashed',
        'border-color': '#ffffff',
      }
    },
    {
      selector: 'node:selected',
      style: { 'border-color': '#ffffff', 'border-width': 3 }
    },
    {
      selector: 'edge',
      style: {
        width: 2,
        'line-color': 'data(color)',
        'target-arrow-color': 'data(color)',
        'target-arrow-shape': 'triangle',
        'curve-style': 'bezier',
        label: 'data(label)',
        'font-size': 9,
        color: '#888',
        'text-outline-width': 1,
        'text-outline-color': '#0f0f13',
        'text-rotation': 'autorotate',
      }
    },
  ],
  layout: { name: 'preset' },
  wheelSensitivity: 0.3,
});

// ── Data Processing ───────────────────────────────────────────────────

function processQueryResults(records) {
  const nodeMap = new Map(); // cytoId → element object
  const edgeMap = new Map(); // edgeId → element object

  function addNode(neoNode) {
    const internalId = getNeoId(neoNode);
    const label      = primaryLabel(neoNode.labels);
    const { image: imageFile, id: minecraftId, name, ...extraProps } = neoNode.properties;

    // Use neo4j elementId as cytoscape node ID — DB-assigned, globally unique.
    // Prevents collision regardless of mod naming (same minecraft id across registries,
    // duplicate recipe names, etc.).
    const cytoId = internalId;
    neoToCytoMap.set(internalId, cytoId);
    if (nodeMap.has(cytoId)) return;

    nodeMap.set(cytoId, {
      data: {
        id:          cytoId,
        minecraftId: minecraftId,
        name:        name || minecraftId || cytoId,
        label:       name || minecraftId || cytoId,
        color:       LABEL_COLOR[label] || '#888',
        neoLabels:   neoNode.labels,
        image:       imageFile ? `textures/${imageFile}` : undefined,
        ...extraProps,
      }
    });
  }

  function addEdge(rel) {
    const edgeId   = 'edge-' + (rel.elementId ?? rel.identity?.toString());
    if (edgeMap.has(edgeId)) return;
    const sourceId = neoToCytoMap.get(getRelSourceId(rel)) ?? getRelSourceId(rel);
    const targetId = neoToCytoMap.get(getRelTargetId(rel)) ?? getRelTargetId(rel);
    if (!sourceId || !targetId) return;

    edgeMap.set(edgeId, {
      data: {
        id:     edgeId,
        source: sourceId,
        target: targetId,
        label:  rel.type.toLowerCase().replace(/_/g, ' '),
        color:  REL_COLOR[rel.type] || '#666',
        type:   rel.type,
        ...rel.properties,
      }
    });
  }

  // Two-pass: build neoToCytoMap from nodes before resolving edge endpoints
  for (const record of records) {
    record.keys.forEach(key => {
      const value = record.get(key);
      if (isNeo4jNode(value)) addNode(value);
    });
  }
  for (const record of records) {
    record.keys.forEach(key => {
      const value = record.get(key);
      if (isNeo4jRel(value)) addEdge(value);
    });
  }

  return [...nodeMap.values(), ...edgeMap.values()];
}

function extractRootId(records) {
  if (!records.length) return null;
  for (const key of records[0].keys) {
    const value = records[0].get(key);
    if (isNeo4jNode(value)) return getNeoId(value); // elementId = cytoId
  }
  return null;
}
