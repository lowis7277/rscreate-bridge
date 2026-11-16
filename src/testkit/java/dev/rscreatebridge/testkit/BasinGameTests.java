package dev.rscreatebridge.testkit;

import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSink;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.simibubi.create.content.processing.basin.BasinBlock;

import dev.rscreatebridge.Registration;
import dev.rscreatebridge.assembly.MachineType;
import dev.rscreatebridge.assembly.AssemblyControllerBlockEntity;
import dev.rscreatebridge.assembly.AssemblyPortBlock;
import dev.rscreatebridge.assembly.AssemblyPortBlockEntity;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static dev.rscreatebridge.testkit.CrafterGameTests.amount;
import static dev.rscreatebridge.testkit.CrafterGameTests.assertEquals;
import static dev.rscreatebridge.testkit.CrafterGameTests.assertNoItemEntities;
import static dev.rscreatebridge.testkit.CrafterGameTests.item;
import static dev.rscreatebridge.testkit.CrafterGameTests.res;

/** Basin stations (mixer and press) and the Create wrench on the mod's blocks. */
@GameTestHolder(RSCBTestKit.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BasinGameTests {
    private static final BlockPos O = new BlockPos(1, 1, 1);

    private BasinGameTests() {
    }

    private static StationBuilder.Stations stations(final GameTestHelper h, final List<String> kinds) {
        return StationBuilder.build(h.getLevel(), h.absolutePos(O), kinds, true);
    }

    private static AssemblyControllerBlockEntity controller(final GameTestHelper h) {
        return h.getBlockEntity(O);
    }

    private static ExternalPatternSink.Result offer(final GameTestHelper h, final List<ResourceAmount> resources,
                                                    final Action action) {
        return RefinedStorageApi.INSTANCE.getPatternProviderExternalPatternSinkFactory()
            .create(h.getLevel(), h.absolutePos(O), Direction.UP).accept(resources, action);
    }

    private static void accept(final GameTestHelper h, final List<ResourceAmount> resources) {
        final ExternalPatternSink.Result sim = offer(h, resources, Action.SIMULATE);
        if (sim != ExternalPatternSink.Result.ACCEPTED) {
            throw new GameTestAssertException("SIMULATE=" + sim + " " + controller(h).getStatus().getString());
        }
        if (offer(h, resources, Action.EXECUTE) != ExternalPatternSink.Result.ACCEPTED) {
            throw new GameTestAssertException("EXECUTE rechazado");
        }
    }

    private static GameTestSequence ready(final GameTestHelper h) {
        return h.startSequence()
            .thenWaitUntil(() -> {
                if (!controller(h).isNetworkActive()) {
                    throw new GameTestAssertException("Waiting for the network");
                }
            })
            .thenIdle(25);
    }

    private static Runnable waitFor(final GameTestHelper h, final Item output, final long count) {
        return () -> {
            final AssemblyControllerBlockEntity be = controller(h);
            if (amount(be, output) < count || !be.isEmptyForRs()) {
                throw new GameTestAssertException(output + ": " + amount(be, output) + "/" + count + " - "
                    + be.getStatus().getString());
            }
        };
    }

    private static AssemblyPortBlockEntity port(final GameTestHelper h, final StationBuilder.Stations s, final int i) {
        return (AssemblyPortBlockEntity) h.getLevel().getBlockEntity(s.ports().get(i));
    }

    // ------------------------------------------------------------------ mixer

    /** Andesite alloy x2 (andesite + nugget, no heat) in a mixer over a basin. */
    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void mixerMakesAndesiteAlloy(final GameTestHelper h) {
        final StationBuilder.Stations s = stations(h, List.of("mixer"));
        ready(h)
            .thenExecute(() -> {
                assertEquals(1, port(h, s, 0).machineType() == dev.rscreatebridge.assembly.MachineType.MIXING ? 1 : 0,
                    "The port should recognize the mixer: " + port(h, s, 0).describe());
                final Component problem = port(h, s, 0).stationProblem();
                h.assertTrue(problem == null, "Station problem: " + (problem == null ? "" : problem.getString())
                    + " / mezcladora: " + h.getLevel().getBlockEntity(s.machines().get(0)));
                accept(h, List.of(res(Items.ANDESITE, 2), res(Items.IRON_NUGGET, 2)));
            })
            .thenWaitUntil(waitFor(h, item("create:andesite_alloy"), 2))
            .thenIdle(10)
            .thenExecute(() -> {
                final AssemblyControllerBlockEntity be = controller(h);
                assertEquals(2, amount(be, item("create:andesite_alloy")), "Alloy (no duplicates)");
                assertEquals(0, amount(be, Items.ANDESITE), "Leftover andesite");
                h.assertTrue(port(h, s, 0).basinEmpty(), "The basin should end up empty");
                assertNoItemEntities(h);
            })
            .thenSucceed();
    }

    /** Brass needs heat. Without a blaze burner it is rejected and says what is missing; with a lit one, it works. */
    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void brassNeedsHeat(final GameTestHelper h) {
        stations(h, List.of("mixer"));
        final List<ResourceAmount> kit = List.of(res(item("minecraft:copper_ingot"), 1), res(item("create:zinc_ingot"), 1));
        ready(h)
            .thenExecute(() -> {
                final ExternalPatternSink.Result r = offer(h, kit, Action.SIMULATE);
                h.assertTrue(r == ExternalPatternSink.Result.REJECTED, "Without heat it should reject: " + r);
                final String status = controller(h).getStatus().getString().toLowerCase();
                h.assertTrue(status.contains("blaze"), "The status should ask for a blaze burner: " + status);
            })
            .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void brassWithBlazeBurner(final GameTestHelper h) {
        stations(h, List.of("mixer_heated"));
        ready(h)
            .thenExecute(() -> accept(h, List.of(res(item("minecraft:copper_ingot"), 1), res(item("create:zinc_ingot"), 1))))
            .thenWaitUntil(waitFor(h, item("create:brass_ingot"), 2))
            .thenSucceed();
    }

    /** With the real RS Autocrafter: asking for 4 brass = 2 mixes; exactly what is in the network is used. */
    @GameTest(template = "empty", timeoutTicks = 3000)
    public static void autocrafterBrass(final GameTestHelper h) {
        stations(h, List.of("mixer_heated"));
        h.setBlock(O.north().above(), CrafterGameTests.block("refinedstorage:cable"));
        DemoBuilder.autocrafter(h.getLevel(), h.absolutePos(O.north()), Direction.SOUTH, List.of(
            EndToEndGameTests.processingPattern(List.of(res(Items.COPPER_INGOT, 1), res(item("create:zinc_ingot"), 1)),
                List.of(res(item("create:brass_ingot"), 2)))));
        ready(h)
            .thenExecute(() -> {
                EndToEndGameTests.fill(controller(h), List.of(res(Items.COPPER_INGOT, 2), res(item("create:zinc_ingot"), 2)), 1);
                EndToEndGameTests.request(controller(h), item("create:brass_ingot"), 4);
            })
            .thenWaitUntil(waitFor(h, item("create:brass_ingot"), 4))
            .thenIdle(20)
            .thenExecute(() -> {
                assertEquals(4, amount(controller(h), item("create:brass_ingot")), "Brass (no duplicates)");
                assertEquals(0, amount(controller(h), Items.COPPER_INGOT), "Leftover copper");
                assertEquals(0, amount(controller(h), item("create:zinc_ingot")), "Leftover zinc");
                assertNoItemEntities(h);
            })
            .thenSucceed();
    }

    /** A recipe that outputs a fluid (test recipe: kelp -> 250 mB of water): the fluid goes from the basin to the network. */
    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void mixerFluidOutputGoesToNetwork(final GameTestHelper h) {
        final StationBuilder.Stations s = stations(h, List.of("mixer"));
        final FluidResource water = new FluidResource(Fluids.WATER);
        ready(h)
            .thenExecute(() -> accept(h, List.of(res(Items.KELP, 2))))
            .thenWaitUntil(() -> {
                final AssemblyControllerBlockEntity be = controller(h);
                if (be.getNetworkAmount(water) < 500 || !be.isEmptyForRs()) {
                    throw new GameTestAssertException("Water in the network: " + be.getNetworkAmount(water) + "/500 - "
                        + be.getStatus().getString());
                }
            })
            .thenIdle(10)
            .thenExecute(() -> {
                assertEquals(500, controller(h).getNetworkAmount(water), "Water (no duplicates)");
                assertEquals(0, amount(controller(h), Items.KELP), "Algas sobrantes");
                h.assertTrue(port(h, s, 0).basinEmpty(), "The basin should end up empty (output tank included)");
            })
            .thenSucceed();
    }

    /** Abort asks for confirmation: the first shift + click only warns; the second one (within 3 s) aborts. */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void abortNeedsConfirmation(final GameTestHelper h) {
        final StationBuilder.Stations s = stations(h, List.of("press"));
        h.getLevel().setBlock(s.motors().get(0), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        ready(h)
            .thenExecute(() -> accept(h, List.of(res(Items.COPPER_INGOT, 1))))
            .thenExecute(() -> {
                final ServerPlayer player = FakePlayerFactory.getMinecraft(h.getLevel());
                controller(h).setOwner(player.getUUID());
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                player.setShiftKeyDown(true);
                final BlockPos abs = h.absolutePos(O);
                final BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(abs).relative(Direction.SOUTH, 0.5),
                    Direction.SOUTH, abs, false);
                player.gameMode.useItemOn(player, h.getLevel(), ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
                h.assertTrue(!controller(h).isEmptyForRs(), "The first shift + click should not abort");
                player.gameMode.useItemOn(player, h.getLevel(), ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
                h.assertTrue(controller(h).isEmptyForRs(), "The second shift + click should abort");
                player.setShiftKeyDown(false);
            })
            .thenIdle(5)
            .thenExecute(() -> assertEquals(1, amount(controller(h), Items.COPPER_INGOT), "The copper went back to the network"))
            .thenSucceed();
    }

    /** Press over a basin (compacting): 2 flint + gravel + 100 mB of lava -> andesite. */
    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void basinPressCompacts(final GameTestHelper h) {
        stations(h, List.of("basin_press"));
        ready(h)
            .thenExecute(() -> accept(h, List.of(res(Items.FLINT, 2), res(Items.GRAVEL, 1),
                new ResourceAmount(new FluidResource(Fluids.LAVA), 100))))
            .thenWaitUntil(waitFor(h, Items.ANDESITE, 1))
            .thenExecute(() -> assertEquals(0, controller(h).getNetworkAmount(new FluidResource(Fluids.LAVA)), "Lava"))
            .thenSucceed();
    }

    /** Mixer over a depot: the port says so ("needs a basin") instead of "no machine". */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void mixerOverDepotIsExplained(final GameTestHelper h) {
        final StationBuilder.Stations s = stations(h, List.of("press"));
        h.getLevel().setBlock(s.machines().get(0), StationBuilder.block("create:mechanical_mixer").defaultBlockState(), 3);
        ready(h)
            .thenExecute(() -> {
                final Component problem = port(h, s, 0).stationProblem();
                h.assertTrue(problem != null && problem.getString().toLowerCase().contains("basin"),
                    "It should say the mixer needs a basin: " + problem);
            })
            .thenSucceed();
    }

    /** A basin outputting to the side: the port warns and isn't used (the results would never reach the network). */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void basinOutputSidewaysIsAProblem(final GameTestHelper h) {
        final StationBuilder.Stations s = stations(h, List.of("mixer"));
        final BlockPos basin = s.depots().get(0);
        // Create only lets the basin output to the side if something below can receive it (a depot, for example).
        h.getLevel().setBlock(basin.below().west(), StationBuilder.block("create:depot").defaultBlockState(), 3);
        h.getLevel().setBlock(basin, h.getLevel().getBlockState(basin).setValue(BasinBlock.FACING, Direction.WEST), 3);
        ready(h)
            .thenExecute(() -> h.assertTrue(port(h, s, 0).stationProblem() != null,
                "A basin outputting to the side should be a station problem: "
                    + h.getLevel().getBlockState(basin)))
            .thenSucceed();
    }

    // ------------------------------------------------------------------ Create wrench

    private static void wrenchClick(final GameTestHelper h, final ServerPlayer player, final BlockPos abs, final Direction face) {
        final ItemStack wrench = new ItemStack(item("create:wrench"));
        player.setItemInHand(InteractionHand.MAIN_HAND, wrench);
        final BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(abs).relative(face, 0.5), face, abs, false);
        player.gameMode.useItemOn(player, h.getLevel(), wrench, InteractionHand.MAIN_HAND, hit);
    }

    /** Clicking with the wrench (on top or on a side) turns the Port and the Controller 90 degrees, like in game. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void wrenchRotatesPortAndController(final GameTestHelper h) {
        final BlockPos portRel = new BlockPos(2, 1, 2);
        final BlockPos ctrlRel = new BlockPos(4, 1, 2);
        h.setBlock(portRel, Registration.ASSEMBLY_PORT.get().defaultBlockState().setValue(AssemblyPortBlock.FACING, Direction.NORTH));
        h.setBlock(ctrlRel, Registration.ASSEMBLY_CONTROLLER.get().defaultBlockState());
        h.startSequence()
            .thenExecute(() -> {
                final ServerPlayer player = FakePlayerFactory.getMinecraft(h.getLevel());
                player.setGameMode(GameType.SURVIVAL);
                player.setPos(Vec3.atCenterOf(h.absolutePos(portRel)).add(0, 1.5, 0));
                wrenchClick(h, player, h.absolutePos(portRel), Direction.UP);
                final BlockState afterTop = h.getBlockState(portRel);
                h.assertTrue(afterTop.is(Registration.ASSEMBLY_PORT.get()) && afterTop.getValue(AssemblyPortBlock.FACING) != Direction.NORTH,
                    "The wrench on top did not turn the port: " + afterTop);
                final Direction before = afterTop.getValue(AssemblyPortBlock.FACING);
                wrenchClick(h, player, h.absolutePos(portRel), Direction.EAST);
                h.assertTrue(h.getBlockState(portRel).getValue(AssemblyPortBlock.FACING) != before,
                    "The wrench on a side did not turn the port");
                final Direction ctrlBefore = h.getBlockState(ctrlRel).getValue(AssemblyPortBlock.FACING);
                wrenchClick(h, player, h.absolutePos(ctrlRel), Direction.UP);
                h.assertTrue(h.getBlockState(ctrlRel).getValue(AssemblyPortBlock.FACING) != ctrlBefore,
                    "The wrench did not turn the controller");
            })
            .thenSucceed();
    }
}
