package cc.smitp.flux;

import cc.smitp.client.*;
import cc.smitp.client.ui.FluxScreen;
import cc.smitp.flux.graph.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.*;
import java.nio.file.*;
import java.util.*;

/** Runs in its own disposable world and config directory; never included in the release jar. */
public final class FluxGameTest implements FabricClientGameTest {
    private static final BlockPos CHEST = new BlockPos(0, 100, 0);
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    @Override public void runTest(ClientGameTestContext context) {
        context.runOnClient(mc -> PacketDelay.install());
        try (var world = context.worldBuilder().create()) {
            world.getConnection().waitForChunksDownload();
            var server = world.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("tp @a 0.5 100 2.5 180 0");
            server.runOnServer(s -> {
                var level = s.overworld();
                for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) level.setBlockAndUpdate(new BlockPos(x,99,z), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(CHEST, Blocks.CHEST.defaultBlockState());
                var player = s.getPlayerList().getPlayers().getFirst(); player.getInventory().clearContent();
                player.getInventory().setItem(9, new ItemStack(Items.CARROT, 64));
                player.getInventory().setItem(0, new ItemStack(Items.CARROT, 12));
                player.getInventory().setItem(10, new ItemStack(Items.OAK_LOG, 32));
                player.getInventory().setItem(11, new ItemStack(Items.DIAMOND, 7));
                player.inventoryMenu.broadcastChanges();
            });
            world.getConnection().waitForClientboundPackets(); context.waitTicks(5);
            context.runOnClient(mc -> {
                var flux = FluxController.INSTANCE;
                String text = """
                    version 1;
                    workflow "Integration" {
                      on container.opened;
                      if container.matches(dimension: "minecraft:overworld", position: [0,100,0]) {
                        deposit all item("minecraft:carrot");
                        deposit all tag("minecraft:logs");
                        notify "Integration complete";
                      }
                    }
                    """;
                check(flux.apply(text, null) == null, "Source must apply: " + flux.status);
                check(flux.toggle("Integration").startsWith("Enabled"), "Workflow must enable");
                open(mc);
            });
            context.waitFor(mc -> !FluxController.INSTANCE.trace.runs().isEmpty() && !FluxController.INSTANCE.runtime.active(), 160);
            context.runOnClient(mc -> {
                var flux = FluxController.INSTANCE;
                check(flux.trace.runs().getFirst().status().equals("Completed"), "Run failed: " + flux.trace.runs().getFirst().entries());
                check(flux.tracker.last() != null, "Container capture missing");
            });
            server.runOnServer(s -> {
                var chest = (ChestBlockEntity) s.overworld().getBlockEntity(CHEST); int carrots = 0, logs = 0;
                for (int i = 0; i < chest.getContainerSize(); i++) { var stack = chest.getItem(i); if (stack.is(Items.CARROT)) carrots += stack.getCount(); if (stack.is(Items.OAK_LOG)) logs += stack.getCount(); }
                check(carrots == 76 && logs == 32, "Server inventory expected 76 carrots + 32 logs, got " + carrots + "/" + logs);
                check(s.getPlayerList().getPlayers().getFirst().getInventory().getItem(11).getCount() == 7, "Unmatched diamonds changed");
            });
            // Test the other vanilla adapters, including delayed authoritative slot delivery.
            for (Block block : List.of(Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.SHULKER_BOX, Blocks.ENDER_CHEST)) {
                context.runOnClient(mc -> mc.player.closeContainer());
                world.getConnection().waitForServerboundPackets();
                server.runOnServer(srv -> {
                    var level = srv.overworld();
                    if (level.getBlockEntity(CHEST) instanceof net.minecraft.world.Container old) old.clearContent();
                    level.setBlockAndUpdate(CHEST, Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(CHEST, block.defaultBlockState());
                    var player = srv.getPlayerList().getPlayers().getFirst();
                    player.getInventory().clearContent();
                    player.getInventory().setItem(9, new ItemStack(Items.CARROT, 16));
                    player.inventoryMenu.broadcastChanges();
                });
                world.getConnection().waitForClientboundPackets();
                int countBefore = context.computeOnClient(mc -> FluxController.INSTANCE.trace.runs().size());
                context.runOnClient(mc -> { PacketDelay.delay = 8; open(mc); });
                context.waitFor(mc -> FluxController.INSTANCE.trace.runs().size() > countBefore && !FluxController.INSTANCE.runtime.active(), 200);
                context.runOnClient(mc -> { check(FluxController.INSTANCE.trace.runs().getFirst().status().equals("Completed"), "Adapter failed for " + block + ": " + FluxController.INSTANCE.trace.runs().getFirst().entries()); PacketDelay.delay = 0; });
                server.runOnServer(srv -> {
                    var player = srv.getPlayerList().getPlayers().getFirst();
                    net.minecraft.world.Container destination = block == Blocks.ENDER_CHEST ? player.getEnderChestInventory() : (net.minecraft.world.Container) srv.overworld().getBlockEntity(CHEST);
                    int count = 0; for (int i = 0; i < destination.getContainerSize(); i++) if (destination.getItem(i).is(Items.CARROT)) count += destination.getItem(i).getCount();
                    check(count == 16, "Adapter did not transfer carrots for " + block + ": " + count);
                });
            }
            // Verify a double chest can be matched through its opposite half.
            context.runOnClient(mc -> mc.player.closeContainer()); world.getConnection().waitForServerboundPackets();
            server.runOnServer(srv -> {
                var level = srv.overworld();
                    if (level.getBlockEntity(CHEST) instanceof net.minecraft.world.Container old) old.clearContent();
                    level.setBlockAndUpdate(CHEST, Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(CHEST.east(), Blocks.CHEST.defaultBlockState());
                level.setBlockAndUpdate(CHEST, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.LEFT));
                level.setBlockAndUpdate(CHEST.east(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.RIGHT));
                var player = srv.getPlayerList().getPlayers().getFirst(); player.getInventory().clearContent(); player.getInventory().setItem(9, new ItemStack(Items.CARROT, 8)); player.inventoryMenu.broadcastChanges();
            });
            world.getConnection().waitForClientboundPackets();
            int doubleBefore = context.computeOnClient(mc -> FluxController.INSTANCE.trace.runs().size());
            context.runOnClient(mc -> {
                check(FluxController.INSTANCE.apply("version 1; workflow \"Integration\" { on container.opened; if container.matches(dimension: \"minecraft:overworld\", position: [1,100,0]) { deposit all item(\"minecraft:carrot\"); } }", null) == null, "Double chest flow failed to apply");
                open(mc);
            });
            context.waitFor(mc -> FluxController.INSTANCE.trace.runs().size() > doubleBefore && !FluxController.INSTANCE.runtime.active(), 160);
            context.runOnClient(mc -> {
                check(FluxController.INSTANCE.tracker.last().positions().size() == 2, "Double chest did not capture both positions");
                check(FluxController.INSTANCE.trace.runs().getFirst().entries().stream().anyMatch(e -> e.message().contains("8/8")), "Opposite half did not match");
            });
            // A genuine manual menu click cancels a run waiting on delayed confirmations.
            context.runOnClient(mc -> mc.player.closeContainer()); world.getConnection().waitForServerboundPackets();
            int manualBefore = context.computeOnClient(mc -> FluxController.INSTANCE.trace.runs().size());
            context.runOnClient(mc -> {
                check(FluxController.INSTANCE.apply("version 1; workflow \"Integration\" { on container.opened; withdraw all item(\"minecraft:carrot\"); notify \"Must not execute after cancellation\"; }", null) == null, "Manual cancellation flow failed to apply");
                PacketDelay.delay = 8; open(mc);
            });
            context.waitFor(mc -> FluxController.INSTANCE.trace.runs().size() > manualBefore && FluxController.INSTANCE.runtime.active(), 100);
            context.runOnClient(mc -> {
                mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, -999, 0, ContainerInput.PICKUP, mc.player);
                check(!FluxController.INSTANCE.runtime.active(), "Manual click must cancel immediately");
                check(FluxController.INSTANCE.trace.runs().getFirst().status().contains("Manual"), "Manual stop reason missing");
                PacketDelay.delay = 0;
            });
            context.waitTicks(12);
            // Reopening fires exactly once; a wrong location workflow must not move items.
            int prior = context.computeOnClient(mc -> FluxController.INSTANCE.trace.runs().size());
            context.runOnClient(mc -> {
                mc.player.closeContainer(); var flux = FluxController.INSTANCE;
                check(flux.apply("version 1; workflow \"Integration\" { on container.opened; if container.matches(dimension: \"minecraft:overworld\", position: [2,100,0]) { withdraw all item(\"minecraft:carrot\"); } }", null) == null, "Wrong-location flow failed to apply");
                open(mc);
            });
            context.waitFor(mc -> FluxController.INSTANCE.trace.runs().size() > prior && !FluxController.INSTANCE.runtime.active(), 100);
            context.runOnClient(mc -> {
                check(FluxController.INSTANCE.trace.runs().getFirst().entries().stream().noneMatch(e -> e.message().contains("captured")), "Wrong location ran a transfer");
                mc.gui.toastManager().clear();
                check(FluxController.INSTANCE.apply(cc.smitp.flux.storage.ProgramRepository.EXAMPLE.replace("[120, 64, -30]", "[0, 100, 0]"), null) == null, "Example must apply");
                FluxController.INSTANCE.editorDocument = null; FluxClient.openEditor();
            });
            context.waitForScreen(FluxScreen.class); context.waitTicks(5);
            context.takeScreenshot("flux-circuit-small");
            context.getInput().resizeWindow(1280, 800);
            context.runOnClient(mc -> { mc.options.guiScale().set(1); mc.resizeGui(); });
            context.waitTicks(3); context.clickScreenButton("Fit canvas");
            context.takeScreenshot("flux-circuit");
            context.clickScreenButton("Condition"); context.waitTicks(3); context.takeScreenshot("flux-inspector");
            context.clickScreenButton("Undo");
            // Exercise text round-trip and rendering; profile enablement survives Apply.
            context.clickScreenButton("Text"); context.waitTicks(3);
            context.runOnClient(mc -> check(mc.gui.screen().children().stream().anyMatch(w -> w instanceof net.minecraft.client.gui.components.MultiLineEditBox edit && edit.visible && edit.getValue().contains("Farm storage")), "Text editor must be visible and contain the program"));
            context.takeScreenshot("flux-text");
            context.clickScreenButton("Circuit"); context.clickScreenButton("Trace"); context.waitTicks(3); context.takeScreenshot("flux-trace");
            context.runOnClient(mc -> {
                var flux = FluxController.INSTANCE;
                try { Files.writeString(Path.of("flux-performance.txt"), flux.diagnostics() + "\n" + flux.trace.runs().stream().map(r -> r.workflow + " " + r.status() + " " + r.entries()).toList()); }
                catch (java.io.IOException e) { throw new RuntimeException(e); }
                mc.gui.setScreen(null);
            });
        }
    }
    private static void open(net.minecraft.client.Minecraft mc) {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(CHEST), Direction.SOUTH, CHEST, false));
    }
}
