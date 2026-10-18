package cc.smitp.client;

import cc.smitp.flux.lang.Program.Direction;
import cc.smitp.flux.runtime.InventoryAdapter;
import cc.smitp.flux.runtime.RevisionClock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Packet-owned snapshots isolate authoritative contents from vanilla's local predictions. */
public final class MinecraftInventoryAdapter implements InventoryAdapter {
    private final Minecraft minecraft;
    private final ContainerSessionTracker tracker;
    private ItemStack[] stacks = new ItemStack[0];
    private RevisionClock clock = new RevisionClock(0);
    private boolean ready;
    public boolean automatedClick;
    public MinecraftInventoryAdapter(Minecraft minecraft, ContainerSessionTracker tracker) { this.minecraft = minecraft; this.tracker = tracker; }
    public void reset() { ready = false; stacks = new ItemStack[0]; clock = new RevisionClock(0); }
    public boolean ready() { return ready; }
    public boolean contents(int containerId, int stateId, List<ItemStack> items) {
        var menu = tracker.menu(); if (menu == null || menu.containerId != containerId || items.size() != menu.slots.size()) return false;
        boolean initial = !ready;
        if (initial) { stacks = new ItemStack[items.size()]; clock = new RevisionClock(items.size()); }
        for (int i = 0; i < items.size(); i++) update(i, stateId, items.get(i));
        ready = true; if (initial) tracker.synchronizedContents(); return initial;
    }
    public void slot(int containerId, int stateId, int slot, ItemStack stack) {
        var menu = tracker.menu(); if (!ready || menu == null || menu.containerId != containerId || slot < 0 || slot >= stacks.length) return;
        update(slot, stateId, stack);
    }
    private void update(int slot, int stateId, ItemStack stack) {
        if (clock.accept(slot, stateId)) stacks[slot] = stack.copy();
    }
    @Override public Container container() { return tracker.current(); }
    @Override public boolean valid(long session) {
        return ready && tracker.current() != null && tracker.current().session() == session && minecraft.player != null && !minecraft.player.isSpectator() && !minecraft.player.isDeadOrDying()
            && minecraft.player.containerMenu == tracker.menu() && minecraft.level != null && tracker.current().dimension().equals(minecraft.level.dimension().identifier().toString())
            && minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen && screen.getMenu() == tracker.menu();
    }
    @Override public boolean cursorEmpty() { return tracker.menu() != null && tracker.menu().getCarried().isEmpty(); }
    @Override public List<Stack> source(Direction direction) {
        List<Stack> result = new ArrayList<>(); int boundary = stacks.length - 36;
        int start = direction == Direction.DEPOSIT ? boundary : 0, end = direction == Direction.DEPOSIT ? stacks.length : boundary;
        for (int i = start; i < end; i++) result.add(stack(i)); return result;
    }
    @Override public Stack stack(int slot) { ItemStack s = stacks[slot]; return new Stack(slot, BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), s.getComponents(), clock.revision(slot)); }
    @Override public boolean mayPickup(int slot) {
        var menu = tracker.menu(); ItemStack source = stacks[slot];
        if (!menu.getSlot(slot).mayPickup(minecraft.player) || !ItemStack.matches(source, menu.getSlot(slot).getItem())) return false;
        int boundary = stacks.length - 36;
        int from = slot >= boundary ? 0 : boundary, to = slot >= boundary ? boundary : stacks.length;
        for (int i = from; i < to; i++) {
            var destination = menu.getSlot(i); ItemStack existing = stacks[i];
            if (destination.mayPlace(source) && (existing.isEmpty() || ItemStack.isSameItemSameComponents(source, existing)) && existing.getCount() < Math.min(source.getMaxStackSize(), destination.getMaxStackSize(source))) return true;
        }
        return false;
    }
    @Override public long quickMove(int slot) {
        long before = clock.sequence(); automatedClick = true;
        try { minecraft.gameMode.handleContainerInput(tracker.menu().containerId, slot, 0, ContainerInput.QUICK_MOVE, minecraft.player); }
        finally { automatedClick = false; }
        return before;
    }
    @Override public void notifyLocal(String message) { if (minecraft.player != null) minecraft.player.sendSystemMessage(Component.literal("[Flux] " + message)); }
}
