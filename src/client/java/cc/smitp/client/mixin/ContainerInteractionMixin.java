package cc.smitp.client.mixin;

import cc.smitp.client.FluxController;
import it.unimi.dsi.fastutil.ints.*;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.HashedStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(MultiPlayerGameMode.class)
public abstract class ContainerInteractionMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void flux$interact(LocalPlayer player, InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        var flux = FluxController.INSTANCE; if (flux != null) flux.tracker.interacted(hit.getBlockPos(), flux.ticks);
    }
    @Inject(method = "handleContainerInput", at = @At("HEAD"))
    private void flux$manual(CallbackInfo ci) { if (FluxController.INSTANCE != null) FluxController.INSTANCE.manualInput(); }
    @ModifyArg(method = "handleContainerInput", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ServerboundContainerClickPacket;<init>(IISBLnet/minecraft/world/inventory/ContainerInput;Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;Lnet/minecraft/network/HashedStack;)V"), index = 5)
    private Int2ObjectMap<HashedStack> flux$authoritativeChanges(Int2ObjectMap<HashedStack> predicted) {
        // Omitting predictions keeps vanilla's server-side remote slots at their pre-click values,
        // so successful quick-moves produce real slot updates instead of being silently suppressed.
        return FluxController.INSTANCE != null && FluxController.INSTANCE.inventory.automatedClick ? new Int2ObjectOpenHashMap<>() : predicted;
    }
}
