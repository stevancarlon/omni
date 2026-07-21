# Omni Shader (Figma plugin)

A tiny local Figma plugin that runs the Omni orb WebGL shader inside Figma. You install it once; after that it's driven programmatically via shared plugin data so the Claude MCP bridge can trigger bakes without you touching anything.

## One-time install (Figma desktop)

1. Open Figma desktop (the plugin needs the desktop app, not the web).
2. `Menu → Plugins → Development → Import plugin from manifest…`
3. Select `figma-plugin/manifest.json` from this repo.
4. Run `Plugins → Development → Omni Shader`.

The plugin window shows the live orb shader with controls for state (Idle / Listening / … / Muted), amplitude, and size. Leave it open while working — it will accept remote commands from Claude.

## Manual use

- **State buttons** — flip moods in real time
- **amp** slider — global amplitude (affects star brightness and outer halo)
- **size** — target resolution when baking (256 / 512 / 768 / 1024)
- **Bake current** — render the current state at the target size and commit a new Figma image (nothing is applied to canvas unless you select a target frame first)
- **Bake all 8** — loop through every state and commit one image per state
- **Propagate to all orbs** — after a bake, swap every rectangle named "orb…" or "personality…" to use the new image hash

## Remote use (from Claude)

Claude talks to the plugin via `figma.root.setSharedPluginData('omni', 'cmd', …)` / `getSharedPluginData('omni', 'result', …)`.

Command shape (written by Claude's bridge):

```json
{
  "id": 1712345678901,
  "type": "bake",
  "state": 0,
  "size": 512,
  "amp": 1.0,
  "apply": "selection" | "all-orbs" | "replace",
  "oldHash": "…only if apply=replace…"
}
```

Result shape (written by the plugin):

```json
{
  "ts": 1712345679012,
  "kind": "baked",
  "ok": true,
  "id": 1712345678901,
  "hash": "abc123…",
  "state": 0,
  "size": 512,
  "affected": 7
}
```

Keep the plugin window open during a Claude session.
