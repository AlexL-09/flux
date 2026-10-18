version 1;
// Replace the location with your chest, or use Capture container in the editor.
workflow "Farm storage" {
  on container.opened;
  if container.matches(dimension: "minecraft:overworld", position: [120, 64, -30]) {
    deposit all item("minecraft:carrot");
    deposit all tag("minecraft:logs");
    notify "Farm storage finished";
  }
}
