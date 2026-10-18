# Flux

A client-only Fabric mod for Minecraft **26.2 / Java 25**. Build inventory workflows in an in-game circuit editor or a readable `.flux` language. **Both editors share the same validated program model.**

## Install and use

Build with `./gradlew build`, then put `build/libs/flux-1.0.0.jar` and Fabric API **0.160.0+26.2** in your Minecraft 26.2 instance's `mods` directory. Use Fabric Loader **0.19.5 or newer** and Java 25. **The server does not need Flux.**

1. Open the chest you want to automate, then press **F8** to open Flux. Opening the editor closes the inventory menu.
2. Select a Condition node and click **Capture container**. This fills in the last verified container's dimension and coordinates.
3. Add Deposit/Withdraw nodes from the searchable palette. Enter a namespaced item ID such as `minecraft:carrot`, or select Tag and enter `minecraft:logs`. Click **Set property** to commit an inspector edit.
4. Drag an output port to another node's input. A Condition runs its **yes** or **no** subchain, then its **next** continuation. Arbitrary merges and loops are rejected.
5. Click **Apply**, select the Container opened event, and **Enable in this profile**. Reopen the container to run the workflow.

**F9** stops automation, including while an inventory or other screen is open. Rebind both keys in Minecraft's Controls settings. `/flux` opens the editor, `/flux stop` stops execution, and `/flux status` displays state and measured active tick timing. These are local client commands.

### Editor controls

- Drag a palette entry onto the canvas or click it to create a node; drag a node to reposition it.
- Drag from output to input, or click an output and then an input. The inspector also offers Connect/Unlink controls.
- Middle- or right-drag to pan; scroll over the canvas to zoom. **Home** fits the graph.
- Scroll the Nodes or Inspector panel to access controls at small GUI scales. Lower Minecraft's GUI scale for a larger workspace.
- **Ctrl/Cmd+Z**, **Ctrl/Cmd+Shift+Z**, or **Ctrl/Cmd+Y** undo/redo. **Ctrl/Cmd+S** applies.
- Left/right arrows cycle nodes when a text field is not focused. **Enter** completes a pending connection to the selected node. Delete removes a selected node.
- Text edits remain drafts until Apply. Invalid text and disconnected graphs cannot replace the running program.
- Trace shows condition results, moved counts, partial transfers, and stop reasons. Scroll to inspect older entries.

## Language

```flux
version 1;

workflow "Farm storage" {
  on container.opened;
  if container.matches(dimension: "minecraft:overworld", position: [120, 64, -30]) {
    deposit all item("minecraft:carrot");
    deposit all tag("minecraft:logs");
    notify "Farm storage finished";
  }
}
```

Workflows run in saved order. Use the event inspector's Run earlier/later buttons to reorder, then Apply.

| Feature | Syntax |
| --- | --- |
| Exact location | `container.matches(dimension: "minecraft:overworld", position: [x, y, z])` |
| Container type | `container.type("minecraft:barrel")` |
| Player has items | `player.contains(item("minecraft:carrot"))` |
| Container has tag | `container.contains(tag("minecraft:logs"))` |
| Boolean conditions | `!condition`, `a && b`, `a || b`, parentheses, `true`, `false` |
| Branch | `if condition { ... } else { ... }` |
| Deposit/withdraw | `deposit all item("minecraft:carrot");`, `withdraw all tag("minecraft:logs");` |
| Local message | `notify "Finished";` |
| Comment | `// comment` |

`!` binds before `&&`, which binds before `||`. Strings support `\"`, `\\`, `\n`, `\r`, and `\t`. Comments are retained as annotations at the top during formatting. See [examples](examples/) and [implementation architecture](docs/architecture.md).

## Transfer behavior

Supported containers: vanilla chests, trapped chests, barrels, shulker boxes, and ender chests. Either half of a verified double chest matches. Colored shulker boxes use the type `minecraft:shulker_box`. Unknown or custom menus are rejected.

Deposits include the player's main inventory and hotbar. Armor, offhand, crafting slots, and carried cursor items are excluded. Item selectors match the item ID; normal Minecraft component rules determine stack merging. Flux transfers as much as destination space allows. It does not guarantee exact quantities or reserve counts.

Only one normal quick-move click is outstanding at a time. Flux waits for authoritative source-slot updates and never blindly retries a missing response. A manual inventory action, occupied cursor, changed menu, disconnect, or unexpected inventory change stops the run. Already completed transfers remain completed.

Location detection is best-effort client correlation between a block interaction and a menu response. The client cannot prove which server inventory backs an arbitrary menu. Server rules may restrict automation.

## Files and limits

Files live under `config/flux/`:

- `workflows.flux`: applied source, with a `.bak` previous version.
- `draft.flux`: editable text draft, including invalid text.
- `editor.json`: versioned profile enablement, canvas positions, workflow order, and runtime limits, with a `.bak` previous version.

Single-player saves and multiplayer server addresses have separate profiles. Every workflow starts disabled in a new profile. A renamed workflow must be enabled under its new name. Valid graph drafts are saved as text when closing the editor; disconnected graph drafts remain in memory across editor opens until Minecraft exits.

Runtime limits can be edited in `editor.json` while Minecraft is closed:

```json
"limits": {
  "clickInterval": 2,
  "confirmationTimeout": 100,
  "transitions": 200,
  "deadline": 2000
}
```

All durations are client ticks. Bounds are 2–40, 20–1200, 1–1000, and 100–12000 respectively. The language caps source size at 256 KiB, workflows at 128, total compiled instructions at 4096, and nesting at 64. Trace memory holds at most 100 runs with 512 entries each. There are no inventory scans while the runtime is idle.

## Develop and test

Use a Java 25 JDK (`JAVA_HOME`), then:

```sh
./gradlew build                 # Compile, unit tests, release jar
./gradlew runClient             # Development Minecraft instance
./gradlew runClientGameTest     # Disposable world, real inventory tests, screenshots
./gradlew genSources            # Generate Minecraft sources; run separately from build
```

Game-test code is in a separate source set and is excluded from the release jar. Unit-test results are in `build/reports/tests/test/`. Game-test screenshots and timing evidence are in `build/run/clientGameTest/`; the test run directory is reset each run. See [verification notes](docs/verification.md) for measured results and remaining coverage.

## License

CC0-1.0, matching the original project license.
