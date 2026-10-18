package cc.smitp.client;

import cc.smitp.client.ui.FluxScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class FluxClient implements ClientModInitializer {
    public static KeyMapping openKey, stopKey;
    @Override public void onInitializeClient() {
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("flux", "controls"));
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.flux.editor", GLFW.GLFW_KEY_F8, category));
        stopKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.flux.stop", GLFW.GLFW_KEY_F9, category));
        FluxController.INSTANCE = new FluxController(Minecraft.getInstance());
        ClientTickEvents.END_CLIENT_TICK.register(client -> FluxController.INSTANCE.tick());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(literal("flux")
            .executes(context -> { openEditor(); return 1; })
            .then(literal("stop").executes(context -> { FluxController.INSTANCE.stop(); return 1; }))
            .then(literal("status").executes(context -> { context.getSource().sendFeedback(Component.literal(FluxController.INSTANCE.diagnostics())); return 1; }))));
    }
    public static void openEditor() {
        var minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() instanceof FluxScreen) return;
        // Close the real menu before entering an unrelated screen. The server must not retain it.
        FluxController.INSTANCE.runtime.cancel("Editor opened", FluxController.INSTANCE.ticks);
        if (minecraft.player != null && minecraft.player.containerMenu != minecraft.player.inventoryMenu) minecraft.player.closeContainer();
        minecraft.gui.setScreen(new FluxScreen(FluxController.INSTANCE));
    }
}
