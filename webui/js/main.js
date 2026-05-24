'use strict';

// ── Neo4j Query Runner ────────────────────────────────────────────────

async function runQuery(cypher, params) {
  const session = driver.session();
  try {
    const result = await session.run(cypher, params);
    return result.records;
  } finally {
    await session.close();
  }
}

// ── Connection ────────────────────────────────────────────────────────

document.getElementById('btn-connect').addEventListener('click', async () => {
  if (driver) { try { await driver.close(); } catch (_) {} }
  const uri  = document.getElementById('neo-uri').value.trim();
  const user = document.getElementById('neo-user').value.trim();
  const pass = document.getElementById('neo-pass').value;
  setStatus('Connecting…');
  try {
    driver = neo4j.driver(uri, neo4j.auth.basic(user, pass));
    await driver.verifyConnectivity();
    setStatus('Connected.', 'ok');
    ['search-input', 'query-type', 'btn-search', 'btn-clear']
      .forEach(id => document.getElementById(id).disabled = false);
  } catch (err) {
    setStatus('Connection failed: ' + err.message, 'error');
    driver = null;
  }
});

// ── Search ────────────────────────────────────────────────────────────

document.getElementById('btn-search').addEventListener('click', runSearch);
searchInputEl.addEventListener('keydown', evt => {
  if (evt.key === 'Enter' && autocompleteActiveIdx < 0) runSearch();
});

async function runSearch() {
  const searchTerm = searchInputEl.value.trim();
  if (!searchTerm || !driver) return;
  closeAutocomplete();

  let queryType = document.getElementById('query-type').value;
  if (queryType === 'auto') {
    queryType = searchInputEl.dataset.detectedType || 'explore';
  }

  setStatus('Searching…');
  try {
    const records    = await runQuery(QUERIES[queryType], { searchTerm });
    const rootId     = extractRootId(records);
    const elements   = processQueryResults(records);
    const layoutMode = DAGRE_QUERIES.has(queryType) ? 'dagre' : 'smart';
    const added      = addToGraph(elements, rootId, layoutMode);

    if (rootId) lastRootId = rootId;
    saveBreadcrumb(`${searchTerm} (${queryType})`, rootId);
    setStatus(`${added} new elements added (${cy.elements().length} total).`, 'ok');
  } catch (err) {
    setStatus('Query error: ' + err.message, 'error');
  }
}

document.getElementById('btn-clear').addEventListener('click', () => {
  cy.elements().remove();
  neoToCytoMap.clear();
  pinnedNodes.clear();
  lastRootId = null;
  hideInfoPanel();
  setStatus('Graph cleared.', 'ok');
});

// ── Expand Neighbours ─────────────────────────────────────────────────

async function expandNeighbours(elementId) {
  if (!driver) return;
  setStatus('Expanding…');
  try {
    const records  = await runQuery(
      `MATCH (node) WHERE elementId(node) = $elementId MATCH (node)-[rel]-(neighbour) RETURN node, rel, neighbour LIMIT ${EXPAND_LIMIT}`,
      { elementId }
    );
    const elements = processQueryResults(records);
    const added    = addToGraph(elements, null);
    setStatus(`${added} new elements added.`, 'ok');
  } catch (err) {
    setStatus('Expand error: ' + err.message, 'error');
  }
}

// ── Context Menu Search Helper ────────────────────────────────────────
// Called from openContextMenu (ui.js) — defined here because it calls runSearch

function searchNodeAs(node, queryType) {
  const name = node.data('name') || node.data('minecraftId');
  searchInputEl.value                = name;
  searchInputEl.dataset.detectedType = queryType;
  document.getElementById('query-type').value = 'auto';
  runSearch();
}

// ── Cytoscape Events ──────────────────────────────────────────────────

cy.on('tap', 'node', evt => showInfoPanel(evt.target));
cy.on('tap', evt   => { if (evt.target === cy) hideInfoPanel(); });
cy.on('dblclick', 'node', evt => expandNeighbours(evt.target.id()));
cy.on('dragfree', 'node', evt => {
  const node = evt.target;
  pinnedNodes.add(node.id());
  node.data('pinned', true);
});
cy.on('cxttap', 'node', evt => {
  const { clientX, clientY } = evt.originalEvent;
  openContextMenu(clientX, clientY, evt.target);
});

document.getElementById('btn-expand').addEventListener('click', () => {
  if (selectedNode) expandNeighbours(selectedNode.id());
});

// ── Autocomplete Events ───────────────────────────────────────────────

searchInputEl.addEventListener('input', () => {
  clearTimeout(autocompleteDebounce);
  const query = searchInputEl.value.trim();
  if (query.length < 2 || !driver) { closeAutocomplete(); return; }
  autocompleteDebounce = setTimeout(async () => {
    try {
      const records     = await runQuery(AUTOCOMPLETE_CYPHER, { query });
      const suggestions = records.map(record => ({
        id:     record.get('id'),
        name:   record.get('name'),
        labels: record.get('labels'),
      }));
      renderAutocompleteSuggestions(suggestions);
    } catch (_) { /* autocomplete errors are non-fatal */ }
  }, AUTOCOMPLETE_DELAY);
});

searchInputEl.addEventListener('keydown', evt => {
  if (autocompleteEl.style.display === 'none') return;
  const count = autocompleteSuggestions.length;
  if (evt.key === 'ArrowDown') {
    evt.preventDefault();
    autocompleteActiveIdx = (autocompleteActiveIdx + 1) % count;
    highlightAutocompleteItem(autocompleteActiveIdx);
  } else if (evt.key === 'ArrowUp') {
    evt.preventDefault();
    autocompleteActiveIdx = (autocompleteActiveIdx - 1 + count) % count;
    highlightAutocompleteItem(autocompleteActiveIdx);
  } else if (evt.key === 'Enter' && autocompleteActiveIdx >= 0) {
    evt.preventDefault();
    evt.stopPropagation();
    applyAutocompleteSuggestion(autocompleteActiveIdx);
  }
});

document.addEventListener('click', evt => {
  if (!autocompleteEl.contains(evt.target) && evt.target !== searchInputEl) {
    closeAutocomplete();
  }
});

// ── Context Menu Events ───────────────────────────────────────────────

document.addEventListener('click', closeContextMenu);
document.addEventListener('contextmenu', evt => {
  if (evt.target?.closest?.('#graph-container')) evt.preventDefault();
});

// ── Keyboard Shortcuts ────────────────────────────────────────────────

document.addEventListener('keydown', evt => {
  const tag     = document.activeElement?.tagName?.toLowerCase();
  const inInput = (tag === 'input' || tag === 'select' || tag === 'textarea');

  switch (evt.key) {
    case '/':
      if (!inInput) { evt.preventDefault(); searchInputEl.focus(); searchInputEl.select(); }
      break;

    case 'Escape':
      if (autocompleteEl.style.display !== 'none') { closeAutocomplete(); break; }
      if (contextMenuEl.style.display  !== 'none') { closeContextMenu();  break; }
      if (inInput) { document.activeElement.blur(); break; }
      cy.elements().unselect();
      hideInfoPanel();
      break;

    case 'f': case 'F':
      if (!inInput) cy.fit(cy.elements(), 50);
      break;

    case 'r': case 'R':
      if (!inInput) {
        if (lastRootId) runSmartLayout(lastRootId);
        else runDagreLayout();
      }
      break;

    case 'u': case 'U':
      if (!inInput) unpinAllNodes();
      break;

    case 'Delete': case 'Backspace':
      if (!inInput && selectedNode) {
        const nodeId = selectedNode.data('id');
        pinnedNodes.delete(nodeId);
        selectedNode.remove();
        hideInfoPanel();
        setStatus('Node removed.', 'ok');
      }
      break;
  }
});

// ── Fast Zoom  (Shift + scroll) ───────────────────────────────────────

document.getElementById('cy').addEventListener('wheel', evt => {
  if (!evt.shiftKey) return;
  evt.preventDefault();
  evt.stopPropagation();

  const zoomFactor = evt.deltaY < 0 ? 1.5 : 1 / 1.5;
  const newZoom    = Math.max(cy.minZoom(), Math.min(cy.maxZoom(), cy.zoom() * zoomFactor));

  cy.zoom({
    level:            newZoom,
    renderedPosition: { x: evt.offsetX, y: evt.offsetY },
  });
}, { passive: false });

// ── Shortcuts Panel Toggle ────────────────────────────────────────────

document.getElementById('shortcuts-toggle').addEventListener('click', () => {
  const body  = document.getElementById('shortcuts-body');
  const arrow = document.getElementById('shortcuts-arrow');
  const open  = body.classList.toggle('open');
  arrow.textContent = open ? '▾' : '▸';
});
