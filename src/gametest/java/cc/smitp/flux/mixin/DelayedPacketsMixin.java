package cc.smitp.flux.mixin;

import cc.smitp.flux.PacketDelay;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class DelayedPacketsMixin {
    @Inject(method = "handleContainerSetSlot", at = @At("HEAD"), cancellable = true)
    private void delay(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) { if (PacketDelay.intercept(packet)) ci.cancel(); }
}
