# Playstyle builder

`index.html` is a standalone browser tool that generates a PylaAndroid `.pyla` playstyle from a few
settings. It is one self-contained file: no server, network, account or installation is required.
All processing stays in the page.

## Use it

1. Open `index.html` in a JavaScript-enabled browser. If a messenger or file manager only shows a
   text/preview screen, download the file and open it with a browser; a viewer without JavaScript
   cannot run the builder.
2. Pick a preset (Balanced, Kiter, Rusher, Team follower, Guard), adjust distance, tolerance,
   strafing, idle behaviour and optional abilities, then **Download .pyla**.
3. Stop the bot in PylaAndroid, open **Playstyles → Import custom .pyla**, select the file, select
   its name and check the active playstyle in the logs after starting.

A visible selection alone is not proof that the script actually runs. Import success does not
guarantee gameplay behaviour.

## Compatibility boundary

- Targets the Android `.pyla` scripting surface of this repository, not PC-only helpers or external
  mods.
- The body uses normal auto-aim attack requests and catalog-driven hold/release. It does not use
  aiming drags, A* pathfinding, world-space navigation, HP checks or teammate AFK detection.
- Native engine authority stays in place: detections, brawler catalog, combat freshness/geometry
  and Stop/Pause gates are unaffected by any exported script.
- Distances are percentages of the brawler's attack range and are clamped by the engine's own
  limits. Strategy compatibility is not a win-rate or live-game guarantee.

## Checks

- `node tools/playstyle-builder/verify.js` — offline regression gate for the builder itself: single
  self-contained file, no external references, inline scripts compile, unique ids, accepted
  generator output for all preset/idle combinations, `.pyla` header/body format, reproducible
  `example-export.pyla`, and rejection of invalid settings.
- `./gradlew :app:testDebugUnitTest --tests com.pyla.ai.pyla.PlaystyleBuilderExportTest` — Android
  compatibility gate: `example-export.pyla` keeps a single-line metadata header, compiles with the
  production `PyLexer`/`PyParser`, and imports through the real `PlaystyleImporter` without byte
  changes.

Neither check proves gameplay behaviour or on-device import; no live-game test is claimed.

## Files

- `index.html` — the deliverable; the only file a user needs.
- `example-export.pyla` — actual browser download of the default settings, used by both checks.
- `verify.js` — offline builder/export regression gate.
