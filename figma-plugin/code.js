// ─────────────────────────────────────────────────────────────
//  Omni Shader — plugin sandbox side
//
//  Communicates with Claude's MCP bridge through
//  figma.root.setSharedPluginData('omni','cmd'|'result', ...).
//  Runs the shader in the UI iframe; receives rendered PNG bytes
//  via postMessage; commits to Figma as an image, then either
//    (a) updates the fill of all rectangles currently using the
//        previous Omni orb hash, or
//    (b) writes the new hash into a selected target rectangle.
//  Writes a result record so the bridge can read the new hash.
// ─────────────────────────────────────────────────────────────

const NS = 'omni';
const POLL_MS = 250;

figma.showUI(__html__, { width: 520, height: 620 });

let lastSeenCmdId = null;
let currentCmd = null;
let pagesLoaded = false;

async function ensurePagesLoaded() {
  if (pagesLoaded) return;
  if (typeof figma.loadAllPagesAsync === 'function') {
    try { await figma.loadAllPagesAsync(); } catch (e) { /* older API */ }
  }
  pagesLoaded = true;
}

async function propagateHashReplace(oldHash, newHash) {
  // Walk every page, replace image fills with oldHash → newHash.
  await ensurePagesLoaded();
  let count = 0;
  for (const page of figma.root.children) {
    const nodes = page.findAll(n => {
      if (!('fills' in n)) return false;
      const fills = n.fills;
      if (!Array.isArray(fills)) return false;
      return fills.some(f => f && f.type === 'IMAGE' && f.imageHash === oldHash);
    });
    for (const n of nodes) {
      const next = n.fills.map(function (f) {
        if (f && f.type === 'IMAGE' && f.imageHash === oldHash) {
          return Object.assign({}, f, { imageHash: newHash });
        }
        return f;
      });
      n.fills = next;
      count++;
    }
  }
  return count;
}

async function applyToSelection(newHash) {
  const sel = figma.currentPage.selection;
  let count = 0;
  for (const n of sel) {
    if ('fills' in n) {
      n.fills = [{ type: 'IMAGE', scaleMode: 'FIT', imageHash: newHash }];
      count++;
    }
  }
  return count;
}

async function applyToAllOrbs(newHash) {
  // Heuristic: any rectangle already holding an IMAGE fill and named like an orb.
  await ensurePagesLoaded();
  let count = 0;
  for (const page of figma.root.children) {
    const nodes = page.findAll(n => {
      if (n.type !== 'RECTANGLE') return false;
      const name = (n.name || '').toLowerCase();
      return name.includes('orb') || name.includes('personality');
    });
    for (const n of nodes) {
      n.fills = [{ type: 'IMAGE', scaleMode: 'FIT', imageHash: newHash }];
      count++;
    }
  }
  return count;
}

function writeResult(payload) {
  const record = Object.assign({ ts: Date.now() }, payload);
  figma.root.setSharedPluginData(NS, 'result', JSON.stringify(record));
}

// ── message from UI (rendered PNG bytes, or log, or ready) ──
figma.ui.onmessage = async (msg) => {
  if (!msg || !msg.type) return;

  if (msg.type === 'log') {
    console.log('[ui]', msg.text);
    return;
  }

  if (msg.type === 'ready') {
    writeResult({ kind: 'ready', ok: true });
    return;
  }

  if (msg.type === 'rendered') {
    try {
      const bytes = new Uint8Array(msg.bytes);
      const image = figma.createImage(bytes);
      const hash = image.hash;
      const cmd = currentCmd || {};
      let affected = 0;
      if (cmd.apply === 'selection') affected = await applyToSelection(hash);
      else if (cmd.apply === 'replace' && cmd.oldHash) affected = await propagateHashReplace(cmd.oldHash, hash);
      else if (cmd.apply === 'all-orbs') affected = await applyToAllOrbs(hash);
      else if (cmd.apply === 'nodes' && cmd.nodeIds) {
        for (var i = 0; i < cmd.nodeIds.length; i++) {
          try {
            var target = await figma.getNodeByIdAsync(cmd.nodeIds[i]);
            if (target && 'fills' in target) {
              target.fills = [{ type: 'IMAGE', scaleMode: 'FILL', imageHash: hash }];
              affected++;
            }
          } catch (e) { /* skip */ }
        }
      }
      writeResult({ kind: 'baked', ok: true, id: cmd.id, hash, state: cmd.state, size: cmd.size, affected });
      currentCmd = null;
    } catch (e) {
      writeResult({ kind: 'error', ok: false, message: String(e) });
    }
  }
};

// ── poll for commands from Claude's bridge ──
setInterval(() => {
  try {
    const raw = figma.root.getSharedPluginData(NS, 'cmd');
    if (!raw) return;
    let cmd = null;
    try { cmd = JSON.parse(raw); } catch (e) { return; }
    if (!cmd || !cmd.id || cmd.id === lastSeenCmdId) return;
    lastSeenCmdId = cmd.id;
    currentCmd = cmd;

    var def = function (v, d) { return (v === undefined || v === null) ? d : v; };

    if (cmd.type === 'bake') {
      figma.ui.postMessage({
        type: 'render',
        id: cmd.id,
        state: def(cmd.state, 0),
        size: def(cmd.size, 512),
        amp: def(cmd.amp, 1.0),
        seed: def(cmd.seed, 0),
        time: def(cmd.time, 0)
      });
    } else if (cmd.type === 'bake-all') {
      figma.ui.postMessage({ type: 'render-all', id: cmd.id, size: def(cmd.size, 512) });
    } else if (cmd.type === 'ping') {
      writeResult({ kind: 'pong', id: cmd.id, ok: true });
    }
  } catch (e) {
    console.error('poll err', e);
  }
}, POLL_MS);

writeResult({ kind: 'boot', ok: true, ts: Date.now() });
