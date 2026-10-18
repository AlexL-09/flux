# Flux implementation

Flux targets Minecraft 26.2, Fabric API 0.160.0+26.2, Java 25. It is a client-only mod.

## Program pipeline

`Parser` produces immutable records with source spans. `Compiler` validates identifiers against the active item registry, resolves tags to item sets, and lowers structured branches to immutable jump/test/action instructions. The tick loop never parses source or resolves tags.

`Graph` and `EditorDocument` are independent of Minecraft. Event roots own sequences; branches own Yes and No subchains plus a Next continuation. The chosen subchain completes before Next. Every node has at most one incoming edge. Cycles, arbitrary merges, disconnected nodes, and invalid ports are rejected. The node registry drives the palette, ports, and inspector. Boolean expressions live in branch properties, so all accepted text expressions have a graph representation. Comments are preserved as document annotations and formatted at the top, not attached to their original line.

## Game integration

`ContainerSessionTracker` captures a block interaction and associates it with the next compatible vanilla menu within 40 client ticks. It requires the same connection, dimension, correct menu class, expected container size, and a verified 36-slot main/hotbar layout. A paired chest accepts either half only after both block states agree. This is best-effort client correlation, not proof of the server's backing inventory. Unknown menus are rejected.

The menu is activated after its initial full contents packet, once per session. Subsequent full-content packets update state without retriggering workflows. A new connection clears captured locations. Profile keys hash single-player save paths or multiplayer server addresses.

`MinecraftInventoryAdapter` keeps copies of authoritative packet contents separate from vanilla's locally predicted stacks. `RevisionClock` rejects duplicate/stale per-slot state IDs and handles the 32768-state wrap. A source-slot update newer than the issued click is required before the executor continues; unrelated slots do not acknowledge it.

Vanilla normally includes predicted slot hashes in quick-move packets and suppresses redundant server updates when those hashes match. For Flux-issued clicks only, `ContainerInteractionMixin` omits that optional prediction map. The click still runs through `MultiPlayerGameMode.handleContainerInput`, uses the actual menu state ID, and uses the vanilla quick-move operation. An unmodified server then sends the changed slots. Flux never invents inventory changes or resends an unconfirmed click. A server rejection with no update times out.

## Execution and failure behavior

A single executor runs enabled workflows in saved order. Each transfer snapshots eligible source slots and clicks each captured stack at most once. Before clicking, it verifies the captured item, components, and count, source permission, destination space, empty cursor, and current menu session. Merges retain normal Minecraft component compatibility. Concurrent newly arriving stacks are excluded; changes to captured stacks stop the run. Completed transfers are not rolled back.

Successful source updates produce moved counts. Full destinations are skipped before clicking; partial transfers and authoritative no-ops appear in the trace. A changed stack kind, increased source count, missing response, manual click, non-container player-inventory packet, closed menu, occupied cursor, world/profile change, program replacement, or emergency stop cancels execution.

Defaults: two ticks between clicks, 100 ticks for confirmation, 200 interpreter transitions per active tick, 2000 ticks per workflow. Bounds are enforced by `WorkflowRuntime.Limits`. At most 100 traces and 512 entries per trace are retained. No inventories are scanned while the executor is idle. `/flux status` reports measured active tick mean/max and sample count; these include runtime work, not rendering or all networking.

## Persistence

`config/flux/workflows.flux` is the applied source. `draft.flux` can contain invalid edits. `editor.json` stores profile enablement, structural-path canvas positions, workflow order, and runtime limits. Source and metadata are individually replaced atomically with `.bak` files. A failed validation does not replace the applied source. Graph drafts, including disconnected nodes, remain in memory when the editor is reopened during the same game session. Valid drafts are also saved as text on editor close.

Workflows begin disabled in every profile. Renaming a workflow requires applying and enabling its new name. Unknown config versions fail closed. No file watcher executes edits automatically; Apply is the activation boundary. Runtime limits are loaded on client startup.
