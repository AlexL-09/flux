package cc.smitp.client.mixin;

import cc.smitp.client.FluxController;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TAIL runs only after vanilla has scheduled the packet onto the client thread. */
@Mixin(ClientPacketListener.class)
public abstract class ContainerPacketsMixin {
    @Inject(method = "handleOpenScreen", at = @At("TAIL"))
    private void flux$open(ClientboundOpenScreenPacket packet, CallbackInfo ci) { if (FluxController.INSTANCE != null) FluxController.INSTANCE.opened(); }
    @Inject(method = "handleContainerContent", at = @At("TAIL"))
    private void flux$content(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        var flux = FluxController.INSTANCE;
        if (flux != null && flux.inventory.contents(packet.containerId(), packet.stateId(), packet.items())) flux.contentsReady();
    }
    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void flux$slot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        var flux = FluxController.INSTANCE; if (flux != null) flux.inventory.slot(packet.getContainerId(), packet.getStateId(), packet.getSlot(), packet.getItem());
    }
    @Inject(method = "handleContainerClose", at = @At("TAIL"))
    private void flux$close(ClientboundContainerClosePacket packet, CallbackInfo ci) { if (FluxController.INSTANCE != null) FluxController.INSTANCE.closeContainer(); }
    @Inject(method = "handleSetPlayerInventory", at = @At("TAIL"))
    private void flux$externalInventory(ClientboundSetPlayerInventoryPacket packet, CallbackInfo ci) {
        if (FluxController.INSTANCE != null) FluxController.INSTANCE.runtime.cancel("Player inventory changed outside container synchronization", FluxController.INSTANCE.ticks);
    }
    @Inject(method = "handleUpdateTags", at = @At("TAIL"))
    private void flux$tags(net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket packet, CallbackInfo ci) {
        if (FluxController.INSTANCE != null && FluxController.INSTANCE.connected()) FluxController.INSTANCE.recompile();
    }
}
