'use strict';

// ── Status Bar ────────────────────────────────────────────────────────

function setStatus(message, type = '') {
  const el = document.getElementById('status');
  el.textContent = message;
  el.className   = type;
}

// ── Info Panel ────────────────────────────────────────────────────────

const SKIP_INFO_KEYS = new Set(['id', 'minecraftId', 'label', 'color', 'neoLabels', 'image', 'namespace', 'name']);

function showInfoPanel(node) {
  selectedNode = node;
  const data   = node.data();
  document.getElementById('info-title').textContent = data.minecraftId || data.name || data.id;

  const bodyEl = document.getElementById('info-body');
  bodyEl.innerHTML = '';
  appendInfoRow(bodyEl, 'type', (data.neoLabels || []).join(', '));

  for (const [key, value] of Object.entries(data)) {
    if (SKIP_INFO_KEYS.has(key) || value == null || value === '') continue;
    const display = typeof value === 'number'
      ? value.toFixed(2).replace(/\.00$/, '')
      : String(value);
    appendInfoRow(bodyEl, key, display);
  }

  document.getElementById('info-panel').classList.add('visible');
}

function appendInfoRow(parent, key, value) {
  const row = document.createElement('div');
  row.className = 'info-row';
  row.innerHTML = `<span class="info-key">${key}</span><span class="info-val">${value}</span>`;
  parent.appendChild(row);
}

function hideInfoPanel() {
  document.getElementById('info-panel').classList.remove('visible');
  selectedNode = null;
}

// ── Breadcrumb History ────────────────────────────────────────────────

function saveBreadcrumb(label, rootId) {
  const snapshot = {
    label,
    rootId,
    elements:   [...cy.nodes().map(node => node.json()), ...cy.edges().map(edge => edge.json())],
    neoMapping: [...neoToCytoMap.entries()],
  };
  breadcrumbs.push(snapshot);
  if (breadcrumbs.length > MAX_BREADCRUMBS) breadcrumbs.shift();
  renderBreadcrumbs(breadcrumbs.length - 1);
}

function restoreBreadcrumb(snapshotIdx) {
  const snapshot = breadcrumbs[snapshotIdx];
  if (!snapshot) return;

  cy.elements().remove();
  neoToCytoMap.clear();
  pinnedNodes.clear();
  snapshot.neoMapping.forEach(([neoId, cytoId]) => neoToCytoMap.set(neoId, cytoId));

  cy.add(snapshot.elements.filter(el => el.group === 'nodes'));
  cy.add(snapshot.elements.filter(el => el.group === 'edges'));

  lastRootId = snapshot.rootId;
  hideInfoPanel();
  setStatus(`Restored: ${snapshot.label}`, 'ok');
  renderBreadcrumbs(snapshotIdx);
}

function renderBreadcrumbs(currentIdx = breadcrumbs.length - 1) {
  const sectionEl = document.getElementById('breadcrumb-section');
  const listEl    = document.getElementById('breadcrumb-list');

  if (!breadcrumbs.length) { sectionEl.style.display = 'none'; return; }
  sectionEl.style.display = 'block';
  listEl.innerHTML = '';

  breadcrumbs.forEach((crumb, idx) => {
    const chip = document.createElement('div');
    chip.className = 'breadcrumb-chip' + (idx === currentIdx ? ' current' : '');
    chip.textContent = crumb.label;
    chip.addEventListener('click', () => restoreBreadcrumb(idx));
    listEl.appendChild(chip);
  });
}

// ── Autocomplete ──────────────────────────────────────────────────────

const searchInputEl  = document.getElementById('search-input');
const autocompleteEl = document.getElementById('autocomplete-dropdown');

let autocompleteSuggestions = [];
let autocompleteActiveIdx   = -1;
let autocompleteDebounce    = null;

function positionAutocompleteDropdown() {
  const rect = searchInputEl.getBoundingClientRect();
  autocompleteEl.style.top   = (rect.bottom + 2) + 'px';
  autocompleteEl.style.left  = rect.left + 'px';
  autocompleteEl.style.width = rect.width + 'px';
}

function renderAutocompleteSuggestions(suggestions) {
  autocompleteSuggestions = suggestions;
  autocompleteActiveIdx   = -1;
  autocompleteEl.innerHTML = '';

  if (!suggestions.length) { closeAutocomplete(); return; }

  suggestions.forEach((suggestion, idx) => {
    const label = primaryLabel(suggestion.labels);
    const color = LABEL_COLOR[label] || '#888';
    const item  = document.createElement('div');
    item.className = 'autocomplete-item';
    item.innerHTML =
      `<div class="autocomplete-dot" style="background:${color}"></div>` +
      `<span class="autocomplete-name">${suggestion.name || suggestion.id}</span>` +
      `<span class="autocomplete-id">${suggestion.id}</span>`;
    item.addEventListener('mousedown', evt => {
      evt.preventDefault(); // prevent input blur before selection registers
      applyAutocompleteSuggestion(idx);
    });
    autocompleteEl.appendChild(item);
  });

  positionAutocompleteDropdown();
  autocompleteEl.style.display = 'block';
}

function closeAutocomplete() {
  autocompleteEl.style.display = 'none';
  autocompleteSuggestions = [];
  autocompleteActiveIdx   = -1;
}

function highlightAutocompleteItem(idx) {
  autocompleteEl.querySelectorAll('.autocomplete-item')
    .forEach((item, itemIdx) => item.classList.toggle('active', itemIdx === idx));
}

function applyAutocompleteSuggestion(idx) {
  const suggestion = autocompleteSuggestions[idx];
  if (!suggestion) return;
  searchInputEl.value                = suggestion.name || suggestion.id;
  searchInputEl.dataset.detectedType = detectQueryType(suggestion.labels);
  closeAutocomplete();
}

// ── Pin System ────────────────────────────────────────────────────────

function pinNode(node) {
  pinnedNodes.add(node.id());
  node.data('pinned', true);
}

function unpinNode(nodeId) {
  pinnedNodes.delete(nodeId);
  cy.getElementById(nodeId).removeData('pinned');
}

function unpinAllNodes() {
  pinnedNodes.forEach(nodeId => cy.getElementById(nodeId).removeData('pinned'));
  pinnedNodes.clear();
  setStatus('All nodes unpinned.', 'ok');
}

// ── Context Menu ──────────────────────────────────────────────────────

const contextMenuEl = document.getElementById('context-menu');

function openContextMenu(clientX, clientY, node) {
  const nodeId   = node.data('id');
  const labels   = node.data('neoLabels') || [];
  const isPinned = pinnedNodes.has(nodeId);

  const menuItems = [];

  if (labels.includes('Entity')) {
    menuItems.push({ label: 'Mob info',               action: () => searchNodeAs(node, 'mob')         });
  }
  if (labels.some(l => ['Item', 'Armor', 'Tool', 'Food'].includes(l))) {
    menuItems.push({ label: 'Recipe chain',            action: () => searchNodeAs(node, 'recipe')      });
    menuItems.push({ label: 'Recipe tree (recursive)', action: () => searchNodeAs(node, 'recipe_tree') });
    menuItems.push({ label: 'Item sources',            action: () => searchNodeAs(node, 'sources')     });
    menuItems.push({ label: 'Sources (recursive)',     action: () => searchNodeAs(node, 'source_tree') });
  }
  if (labels.includes('Biome'))     menuItems.push({ label: 'Biome info',     action: () => searchNodeAs(node, 'biome')     });
  if (labels.includes('Structure')) menuItems.push({ label: 'Structure info', action: () => searchNodeAs(node, 'structure') });
  if (labels.includes('Dimension')) menuItems.push({ label: 'Dimension info', action: () => searchNodeAs(node, 'dimension') });
  if (labels.includes('Block'))     menuItems.push({ label: 'Block drops',    action: () => searchNodeAs(node, 'block')     });

  menuItems.push({ separator: true });
  menuItems.push({
    label:  isPinned ? 'Unpin node' : 'Pin node',
    action: () => { isPinned ? unpinNode(nodeId) : pinNode(node); },
  });
  menuItems.push({ label: 'Expand neighbors', action: () => expandNeighbours(node.id()) });
  menuItems.push({ separator: true });
  menuItems.push({
    label:  'Remove from graph',
    danger: true,
    action: () => {
      if (selectedNode?.id() === node.id()) hideInfoPanel();
      pinnedNodes.delete(nodeId);
      cy.getElementById(nodeId).remove();
      setStatus('Node removed.', 'ok');
    },
  });

  contextMenuEl.innerHTML = '';
  menuItems.forEach(item => {
    if (item.separator) {
      const sep = document.createElement('div');
      sep.className = 'context-sep';
      contextMenuEl.appendChild(sep);
      return;
    }
    const el = document.createElement('div');
    el.className = 'context-item' + (item.danger ? ' danger' : '');
    el.textContent = item.label;
    el.addEventListener('click', () => { closeContextMenu(); item.action(); });
    contextMenuEl.appendChild(el);
  });

  const menuW = 180;
  const menuH = menuItems.length * 32;
  contextMenuEl.style.left    = Math.min(clientX, window.innerWidth  - menuW - 8) + 'px';
  contextMenuEl.style.top     = Math.min(clientY, window.innerHeight - menuH - 8) + 'px';
  contextMenuEl.style.display = 'block';
}

function closeContextMenu() {
  contextMenuEl.style.display = 'none';
}
