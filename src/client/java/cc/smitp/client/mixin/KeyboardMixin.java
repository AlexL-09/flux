package cc.smitp.client.mixin;

import cc.smitp.client.*;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void flux$globalKeys(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (action != 1 || FluxController.INSTANCE == null) return;
        if (FluxClient.stopKey != null && FluxClient.stopKey.matches(event)) { FluxController.INSTANCE.stop(); ci.cancel(); }
        else if (FluxClient.openKey != null && FluxClient.openKey.matches(event)) { FluxClient.openEditor(); ci.cancel(); }
    }
}
