package dev.rscreatebridge.testkit;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;

import dev.rscreatebridge.Registration;
import dev.rscreatebridge.assembly.AssemblyControllerBlockEntity;
import dev.rscreatebridge.common.BridgeNodeBlockEntity;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static dev.rscreatebridge.testkit.CrafterGameTests.amount;
import static dev.rscreatebridge.testkit.CrafterGameTests.assertNoItemEntities;
import static dev.rscreatebridge.testkit.CrafterGameTests.item;

/**
 * The demo as a player uses it: {@code /function rscbtest:demo} (which calls {@code /rscbtest demo}), then
 * real RS requests for everything that has a pattern.
 */
@GameTestHolder(RSCBTestKit.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DemoGameTests {
    private static final String[][] REQUESTS = {
        {"create:crushing_wheel", "4"}, {"create:sturdy_sheet", "2"}, {"create:track", "2"},
        {"create:extendo_grip", "1"}, {"create:precision_mechanism", "1"}, {"create:brass_ingot", "4"}};

    private DemoGameTests() {
    }

    @GameTest(template = "empty_big", timeoutTicks = 30000)
    public static void demoFunctionBuildsAndWorks(final GameTestHelper h) {
        h.startSequence()
            .thenExecute(() -> {
                final var server = h.getLevel().getServer();
                final BlockPos at = h.absolutePos(new BlockPos(-2, 1, -2));
                final var source = server.createCommandSourceStack().withPermission(4).withLevel(h.getLevel())
                    .withPosition(Vec3.atBottomCenterOf(at)).withSuppressedOutput();
                server.getCommands().performPrefixedCommand(source, "function rscbtest:demo");
                h.assertTrue(h.getLevel().getBlockState(h.absolutePos(DemoBuilder.CRAFTER_INTERFACE))
                    .is(Registration.CRAFTER_INTERFACE.get()), "The function did not build the demo");
            })
            .thenWaitUntil(() -> h.assertTrue(((BridgeNodeBlockEntity) h.getBlockEntity(DemoBuilder.ASSEMBLY_CONTROLLER)).isNetworkActive()
                && ((BridgeNodeBlockEntity) h.getBlockEntity(DemoBuilder.CRAFTER_INTERFACE)).isNetworkActive(), "red"))
            .thenIdle(40)
            .thenExecute(() -> requestAll(h))
            .thenWaitUntil(() -> waitForResults(h))
            .thenExecute(() -> assertNoItemEntities(h))
            .thenSucceed();
    }

    private static void requestAll(final GameTestHelper h) {
        final BridgeNodeBlockEntity node = h.getBlockEntity(DemoBuilder.ASSEMBLY_CONTROLLER);
        final Network network = node.getNetwork();
        if (network == null) {
            throw new GameTestAssertException("No network");
        }
        final AutocraftingNetworkComponent autocrafting = network.getComponent(AutocraftingNetworkComponent.class);
        final List<String> failed = new ArrayList<>();
        for (final String[] r : REQUESTS) {
            final var result = autocrafting.ensureTask(new ItemResource(item(r[0])), Long.parseLong(r[1]),
                BridgeNodeBlockEntity.ACTOR, CancellationToken.NONE);
            if (result != AutocraftingNetworkComponent.EnsureResult.TASK_CREATED) {
                failed.add(r[0] + "=" + result);
            }
        }
        if (!failed.isEmpty()) {
            throw new GameTestAssertException("Requests rejected by RS: " + failed);
        }
    }

    private static void waitForResults(final GameTestHelper h) {
        final BridgeNodeBlockEntity node = h.getBlockEntity(DemoBuilder.ASSEMBLY_CONTROLLER);
        final StringBuilder missing = new StringBuilder();
        for (final String[] e : REQUESTS) {
            final long have = amount(node, item(e[0]));
            if (have < Long.parseLong(e[1])) {
                missing.append(e[0]).append(' ').append(have).append('/').append(e[1]).append("; ");
            }
        }
        if (!missing.isEmpty()) {
            final AssemblyControllerBlockEntity c = h.getBlockEntity(DemoBuilder.ASSEMBLY_CONTROLLER);
            throw new GameTestAssertException("Missing: " + missing + " controller: " + c.getStatus().getString());
        }
    }
}
