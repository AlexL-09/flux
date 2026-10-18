package cc.smitp.client;

import cc.smitp.flux.lang.Program.Position;
import cc.smitp.flux.runtime.InventoryAdapter.Container;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import java.util.*;

/** Correlates actual block use with one subsequent compatible menu, never its title. */
public final class ContainerSessionTracker {
    private record Pending(Object connection, String dimension, String type, List<Position> positions, int slots, long tick) {}
    private final Minecraft minecraft;
    private Pending pending;
    private Container current, last;
    private AbstractContainerMenu menu;
    private long generation;
    public ContainerSessionTracker(Minecraft minecraft) { this.minecraft = minecraft; }
    public void interacted(BlockPos pos, long tick) {
        pending = null;
        if (minecraft.level == null || minecraft.player == null) return;
        var state = minecraft.level.getBlockState(pos); Block block = state.getBlock();
        String type; int slots = 27; List<Position> positions = new ArrayList<>(); positions.add(position(pos));
        if (block instanceof ChestBlock) {
            type = BuiltInRegistries.BLOCK.getKey(block).toString();
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos neighbor = pos.relative(ChestBlock.getConnectedDirection(state));
                var other = minecraft.level.getBlockState(neighbor);
                if (other.getBlock() != block || other.getValue(ChestBlock.TYPE) == ChestType.SINGLE || other.getValue(ChestBlock.TYPE) == state.getValue(ChestBlock.TYPE)
                    || other.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING) || !neighbor.relative(ChestBlock.getConnectedDirection(other)).equals(pos)) return;
                positions.add(position(neighbor)); slots = 54;
            }
        } else if (block instanceof BarrelBlock) type = "minecraft:barrel";
        else if (block instanceof ShulkerBoxBlock) type = "minecraft:shulker_box";
        else if (block instanceof EnderChestBlock) type = "minecraft:ender_chest";
        else return;
        if (!Set.of("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel", "minecraft:shulker_box", "minecraft:ender_chest").contains(type)) return;
        pending = new Pending(minecraft.getConnection(), minecraft.level.dimension().identifier().toString(), type, List.copyOf(positions), slots, tick);
    }
    public Container opened(long tick) {
        current = null; menu = null; generation++;
        Pending p = pending; pending = null;
        if (p == null || minecraft.player == null || minecraft.level == null || p.connection != minecraft.getConnection() || tick - p.tick > 40 || !p.dimension.equals(minecraft.level.dimension().identifier().toString())) return null;
        AbstractContainerMenu candidate = minecraft.player.containerMenu;
        boolean supported = p.type.equals("minecraft:shulker_box") ? candidate.getClass() == ShulkerBoxMenu.class : candidate.getClass() == ChestMenu.class;
        if (!supported || candidate.slots.size() != p.slots + 36) return null;
        // Validate the slot contract: exactly 36 main/hotbar slots, all others owned by the container.
        Set<Integer> indices = new HashSet<>();
        for (int i = 0; i < candidate.slots.size(); i++) {
            var slot = candidate.getSlot(i);
            if (i < p.slots) { if (slot.container == minecraft.player.getInventory()) return null; }
            else if (slot.container != minecraft.player.getInventory() || slot.getContainerSlot() < 0 || slot.getContainerSlot() >= 36 || !indices.add(slot.getContainerSlot())) return null;
        }
        menu = candidate; current = new Container(generation, p.dimension, p.type, p.positions); return current;
    }
    public void synchronizedContents() { if (current != null) last = current; }
    public Container current() { return current; }
    public Container last() { return last; }
    public AbstractContainerMenu menu() { return menu; }
    public void clear() { pending = null; current = null; menu = null; generation++; }
    public void resetConnection() { clear(); last = null; }
    private static Position position(BlockPos p) { return new Position(p.getX(), p.getY(), p.getZ()); }
}
