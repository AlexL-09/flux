package cc.smitp.flux;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import java.util.*;

/** Test-only inbound latency; this class is absent from production artifacts. */
public final class PacketDelay {
    public static int delay;
    public static boolean delivering;
    private static long tick;
    private record Delivery(long tick, ClientboundContainerSetSlotPacket packet) {}
    private static final Deque<Delivery> pending = new ArrayDeque<>();
    public static void install() {
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            tick++;
            while (!pending.isEmpty() && pending.getFirst().tick <= tick) {
                var next = pending.removeFirst(); delivering = true;
                try { if (mc.getConnection() != null) mc.getConnection().handleContainerSetSlot(next.packet); }
                finally { delivering = false; }
            }
        });
    }
    public static boolean intercept(ClientboundContainerSetSlotPacket packet) {
        if (delay == 0 || delivering || !Minecraft.getInstance().isSameThread()) return false;
        pending.addLast(new Delivery(tick + delay, packet)); return true;
    }
}
