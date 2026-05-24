'use strict';

function runDagreLayout() {
  const savedPositions = new Map();
  cy.nodes().forEach(node => {
    if (pinnedNodes.has(node.id())) savedPositions.set(node.id(), { ...node.position() });
  });

  const layout = cy.layout({
    name: 'dagre', rankDir: 'LR',
    rankSep: 120, nodeSep: 50, edgeSep: 10,
    animate: true, animationDuration: 400, fit: true, padding: 50,
  });

  layout.one('layoutstop', () => {
    savedPositions.forEach((pos, nodeId) => cy.getElementById(nodeId).position(pos));
  });

  layout.run();
}

/**
 * Radial zone layout:
 * - Root at center
 * - Each relationship type → own zone on a circle around root
 * - Nodes within a zone in a compact square grid
 * - 2nd-hop nodes extend radially outward from their parent
 */
function runSmartLayout(rootId) {
  const rootNode = cy.getElementById(rootId);
  if (!rootNode.length) { runDagreLayout(); return; }

  if (!pinnedNodes.has(rootId)) rootNode.position({ x: 0, y: 0 });
  const positioned = new Set([rootId]);

  // Group direct neighbours by relationship type
  const zonesByType = new Map(); // relType → cyNode[]
  rootNode.connectedEdges().forEach(edge => {
    const relType     = edge.data('type') || '_';
    const neighbour   = edge.source().id() === rootId ? edge.target() : edge.source();
    const neighbourId = neighbour.id();
    if (positioned.has(neighbourId)) return;
    if (!zonesByType.has(relType)) zonesByType.set(relType, []);
    if (!zonesByType.get(relType).some(node => node.id() === neighbourId)) {
      zonesByType.get(relType).push(neighbour);
    }
  });

  const zoneList  = [...zonesByType.entries()];
  const zoneCount = zoneList.length;
  const zoneAngle = new Map(); // relType → radians

  zoneList.forEach(([relType, zoneNodes], zoneIdx) => {
    const angle = (2 * Math.PI * zoneIdx) / Math.max(zoneCount, 1);
    zoneAngle.set(relType, angle);

    const cols  = Math.ceil(Math.sqrt(zoneNodes.length));
    const rows  = Math.ceil(zoneNodes.length / cols);
    const zoneW = (cols - 1) * ZONE_NODE_CELL;
    const zoneH = (rows - 1) * ZONE_NODE_CELL;
    const dist  = ZONE_BASE_DIST + Math.max(zoneW, zoneH) * 0.3;
    const zoneX = dist * Math.cos(angle);
    const zoneY = dist * Math.sin(angle);

    zoneNodes.forEach((node, nodeIdx) => {
      positioned.add(node.id());
      if (pinnedNodes.has(node.id())) return;
      const colIdx = nodeIdx % cols;
      const rowIdx = Math.floor(nodeIdx / cols);
      node.position({
        x: zoneX - zoneW / 2 + colIdx * ZONE_NODE_CELL,
        y: zoneY - zoneH / 2 + rowIdx * ZONE_NODE_CELL,
      });
    });
  });

  // 2nd-hop: push further out from parent along the same zone angle
  for (const [relType, zoneNodes] of zonesByType) {
    const angle = zoneAngle.get(relType);
    const dirX  = Math.cos(angle);
    const dirY  = Math.sin(angle);
    const hop2  = new Map(); // parentId → cyNode[]

    for (const parentNode of zoneNodes) {
      parentNode.connectedEdges().forEach(edge => {
        const neighbour   = edge.source().id() === parentNode.id() ? edge.target() : edge.source();
        const neighbourId = neighbour.id();
        if (positioned.has(neighbourId)) return;
        const parentId = parentNode.id();
        if (!hop2.has(parentId)) hop2.set(parentId, []);
        if (!hop2.get(parentId).some(node => node.id() === neighbourId)) {
          hop2.get(parentId).push(neighbour);
        }
      });
    }

    for (const [parentId, hop2Nodes] of hop2) {
      const parentPos = cy.getElementById(parentId).position();
      const clusterX  = parentPos.x + dirX * ZONE_HOP2_EXTRA_DIST;
      const clusterY  = parentPos.y + dirY * ZONE_HOP2_EXTRA_DIST;
      const cols      = Math.ceil(Math.sqrt(hop2Nodes.length));

      hop2Nodes.forEach((node, nodeIdx) => {
        positioned.add(node.id());
        if (pinnedNodes.has(node.id())) return;
        const colIdx = nodeIdx % cols;
        const rowIdx = Math.floor(nodeIdx / cols);
        node.position({
          x: clusterX + (colIdx - (cols - 1) / 2) * ZONE_NODE_CELL,
          y: clusterY + (rowIdx - Math.floor(hop2Nodes.length / cols / 2)) * ZONE_NODE_CELL,
        });
      });
    }
  }

  // Overflow: anything not reachable within 2 hops of root
  let overflowIdx = 0;
  cy.nodes().forEach(node => {
    if (!positioned.has(node.id())) {
      node.position({ x: 600, y: (overflowIdx++) * ZONE_NODE_CELL });
    }
  });

  cy.fit(cy.elements(), 50);
}

// layoutMode 'smart' = radial zones (mob/biome/structure/dimension)
// layoutMode 'dagre' = hierarchical LR (recipe trees, explore)
function addToGraph(elements, rootId, layoutMode = 'smart') {
  const existingIds = new Set(cy.elements().map(el => el.id()));
  const newElements = elements.filter(el => !existingIds.has(el.data.id));
  if (!newElements.length) return 0;

  cy.add(newElements.filter(el => !el.data.source));
  cy.add(newElements.filter(el =>  el.data.source));

  if (layoutMode === 'dagre' || !rootId || !cy.getElementById(rootId).length) {
    runDagreLayout();
  } else {
    runSmartLayout(rootId);
  }

  return newElements.length;
}
