version 1;
// A bounded branch; withdraws all matching stacks, not an exact quantity.
workflow "Restock carrots" {
  on container.opened;
  if container.matches(dimension: "minecraft:overworld", position: [120, 64, -30]) && !player.contains(item("minecraft:carrot")) {
    if container.contains(item("minecraft:carrot")) {
      withdraw all item("minecraft:carrot");
    } else {
      notify "No carrots left in this container";
    }
  }
}
