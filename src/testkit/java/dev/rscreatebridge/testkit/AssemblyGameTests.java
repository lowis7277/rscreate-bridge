package dev.rscreatebridge.testkit;

import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSink;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.autocrafting.PlatformPatternProviderExternalPatternSink;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dev.rscreatebridge.assembly.AssemblyControllerBlockEntity;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

import static dev.rscreatebridge.testkit.CrafterGameTests.amount;
import static dev.rscreatebridge.testkit.CrafterGameTests.assertEquals;
import static dev.rscreatebridge.testkit.CrafterGameTests.assertNoItemEntities;
import static dev.rscreatebridge.testkit.CrafterGameTests.block;
import static dev.rscreatebridge.testkit.CrafterGameTests.item;
import static dev.rscreatebridge.testkit.CrafterGameTests.res;
import static dev.rscreatebridge.testkit.EndToEndGameTests.fill;
import static dev.rscreatebridge.testkit.EndToEndGameTests.processingPattern;
import static dev.rscreatebridge.testkit.EndToEndGameTests.request;

/**
 * Assembly Controller + Assembly Ports. The test network sits on top of the controller.
 * The test recipe {@code rscbtestkit:test_all_machines} (flint -> spout 100 mB of lava, press,
 * deployer with a stick; 2 loops; no scrap) uses the three machines and is deterministic.
 */
@GameTestHolder(RSCBTestKit.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AssemblyGameTests {
    private static final BlockPos O = new BlockPos(1, 1, 1);
    static final List<String> ALL = List.of("spout", "press", "deployer");
    private static final FluidResource LAVA = new FluidResource(Fluids.LAVA);

    private AssemblyGameTests() {
    }

    private static StationBuilder.Stations stations(final GameTestHelper h, final List<String> kinds) {
        return StationBuilder.build(h.getLevel(), h.absolutePos(O), kinds, true);
    }

    private static AssemblyControllerBlockEntity controller(final GameTestHelper h) {
        return h.getBlockEntity(O);
    }

    private static PlatformPatternProviderExternalPatternSink sink(final GameTestHelper h) {
        return RefinedStorageApi.INSTANCE.getPatternProviderExternalPatternSinkFactory()
            .create(h.getLevel(), h.absolutePos(O), Direction.UP);
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

    private static void accept(final GameTestHelper h, final List<ResourceAmount> resources) {
        final ExternalPatternSink.Result sim = sink(h).accept(resources, Action.SIMULATE);
        if (sim != ExternalPatternSink.Result.ACCEPTED) {
            throw new GameTestAssertException("SIMULATE=" + sim + " " + controller(h).getStatus().getString());
        }
        final ExternalPatternSink.Result exec = sink(h).accept(resources, Action.EXECUTE);
        if (exec != ExternalPatternSink.Result.ACCEPTED) {
            throw new GameTestAssertException("EXECUTE=" + exec);
        }
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

    private static long lava(final AssemblyControllerBlockEntity be) {
        return be.getNetworkAmount(LAVA);
    }

    private static void assertDepotsEmpty(final GameTestHelper h, final StationBuilder.Stations s) {
        for (final BlockPos depot : s.depots()) {
            final IItemHandler handler = h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, depot, Direction.SOUTH);
            if (handler != null) {
                for (int i = 0; i < handler.getSlots(); i++) {
                    h.assertTrue(handler.getStackInSlot(i).isEmpty(), "Something was left on the depot " + depot + ": "
                        + handler.getStackInSlot(i));
                }
            }
        }
    }

    // ------------------------------------------------------------------ cases

    /**
     * The pattern brings everything (flint, 2 sticks, 200 mB of lava) and the network has nothing else: the
     * controller loads the three machines only with what the pattern brought. Nothing is missing or left over.
     */
    @GameTest(template = "empty", timeoutTicks = 3000)
    public static void fullPatternUsesOnlyPatternMaterials(final GameTestHelper h) {
        final StationBuilder.Stations s = stations(h, ALL);
        ready(h)
            .thenExecute(() -> accept(h, List.of(res(Items.FLINT, 1), res(Items.STICK, 2), new ResourceAmount(LAVA, 200))))
            .thenWaitUntil(waitFor(h, Items.EMERALD, 1))
            .thenIdle(140) // ports unload their machines after 5 s without work
            .thenExecute(() -> {
                final AssemblyControllerBlockEntity be = controller(h);
                assertEquals(1, amount(be, Items.EMERALD), "Producto");
                assertEquals(0, amount(be, Items.STICK), "Sticks (both were used)");
                assertEquals(0, lava(be), "Lava (all 200 mB were used)");
                assertEquals(0, amount(be, Items.FLINT), "Pedernal");
                assertDepotsEmpty(h, s);
                assertNoItemEntities(h);
            })
            .thenSucceed();
    }

    /** One-loop pattern (JEI style): the rest of the sticks and lava comes from the network. */
    @GameTest(template = "empty", timeoutTicks = 3000)
    public static void jeiPatternTakesRestFromNetwork(final GameTestHelper h) {
        stations(h, ALL);
        ready(h)
            .thenExecute(() -> {
                fill(controller(h), List.of(res(Items.STICK, 5), new ResourceAmount(LAVA, 500)), 1);
                accept(h, List.of(res(Items.FLINT, 1), res(Items.STICK, 1), new ResourceAmount(LAVA, 100)));
            })
            .thenWaitUntil(waitFor(h, Items.EMERALD, 1))
            .thenIdle(140)
            .thenExecute(() -> {
                final AssemblyControllerBlockEntity be = controller(h);
                assertEquals(4, amount(be, Items.STICK), "Sticks in the network (5 - 1)");
                assertEquals(400, lava(be), "Lava in the network (500 - 100)");
                assertNoItemEntities(h);
            })
            .thenSucceed();
    }

    /** A recipe that only uses the deployer (precision mechanism): one deployer port is enough. */
    @GameTest(template = "empty", timeoutTicks = 12000)
    public static void precisionMechanismWithOneDeployerPort(final GameTestHelper h) {
        final StationBuilder.Stations s = stations(h, List.of("deployer"));
        final Item golden = item("create:golden_sheet");
        final Item cog = item("create:cogwheel");
        final Item large = item("create:large_cogwheel");
        ready(h)
            .thenExecute(() -> {
                // For the retries if it produces scrap.
                fill(controller(h), List.of(res(golden, 4), res(cog, 20), res(large, 20), res(Items.IRON_NUGGET, 20)), 1);
                accept(h, List.of(res(golden, 1), res(cog, 5), res(large, 5), res(Items.IRON_NUGGET, 5)));
            })
            .thenWaitUntil(waitFor(h, item("create:precision_mechanism"), 1))
            .thenIdle(140)
            .thenExecute(() -> {
                assertDepotsEmpty(h, s);
                assertNoItemEntities(h);
            })
            .thenSucceed();
    }

    /** Two deployer ports + one press: two tracks are built at the same time. */
    @GameTest(template = "empty", timeoutTicks = 4000)
    public static void parallelPorts(final GameTestHelper h) {
        stations(h, List.of("deployer", "deployer", "press"));
        final boolean[] sawParallel = new boolean[1];
        ready(h)
            .thenExecute(() -> accept(h, List.of(res(Items.STONE_SLAB, 3), res(Items.IRON_NUGGET, 6))))
            .thenWaitUntil(() -> {
                if (controller(h).piecesAtPorts() >= 2) {
                    sawParallel[0] = true;
                }
                waitFor(h, item("create:track"), 3).run();
            })
            .thenExecute(() -> {
                h.assertTrue(sawParallel[0], "There were never two pieces in machines at the same time");
                assertEquals(0, amount(controller(h), Items.IRON_NUGGET), "Pepitas");
            })
            .thenSucceed();
    }

    /** Single-step recipe: copper ingot -> sheet with the press on a depot (no sequenced assembly). */
    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void singleStepPressMakesCopperSheets(final GameTestHelper h) {
        final StationBuilder.Stations s = stations(h, List.of("press"));
        ready(h)
            .thenExecute(() -> accept(h, List.of(res(Items.COPPER_INGOT, 2))))
            .thenWaitUntil(waitFor(h, item("create:copper_sheet"), 2))
            .thenIdle(10)
            .thenExecute(() -> {
                assertEquals(2, amount(controller(h), item("create:copper_sheet")), "Sheets (no duplicates)");
                assertEquals(0, amount(controller(h), Items.COPPER_INGOT), "Leftover copper");
                assertDepotsEmpty(h, s);
                assertNoItemEntities(h);
            })
            .thenSucceed();
    }

    /**
     * The station ends up in an unloaded chunk with a piece on it: the job pauses (it is not treated as lost
     * nor redone with new materials). Once it loads again, it finishes with the same piece.
     */
    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void unloadedStationPausesInsteadOfRetrying(final GameTestHelper h) {
        final StationBuilder.Stations s = stations(h, List.of("press"));
        final java.util.Set<BlockPos> unloaded = java.util.Set.copyOf(s.ports());
        ready(h)
            .thenExecute(() -> {
                controller(h).insertIntoNetwork(new ItemResource(Items.COPPER_INGOT), 2, Action.EXECUTE);
                accept(h, List.of(res(Items.COPPER_INGOT, 1)));
            })
            .thenWaitUntil(() -> h.assertTrue(controller(h).piecesAtPorts() == 1, "Waiting for the piece at the station"))
            .thenExecute(() -> AssemblyControllerBlockEntity.simulateUnloadedStations(unloaded::contains))
            .thenIdle(200)
            .thenExecute(() -> {
                h.assertTrue(!controller(h).isEmptyForRs(), "With the station unloaded the job has to wait");
                assertEquals(2, amount(controller(h), Items.COPPER_INGOT), "It should not take copper from the network to redo it");
                AssemblyControllerBlockEntity.simulateUnloadedStations(null);
            })
            .thenWaitUntil(waitFor(h, item("create:copper_sheet"), 1))
            .thenExecute(() -> {
                assertEquals(1, amount(controller(h), item("create:copper_sheet")), "A single sheet");
                assertEquals(2, amount(controller(h), Items.COPPER_INGOT), "The network copper is untouched");
                assertDepotsEmpty(h, s);
            })
            .thenSucceed();
    }

    /** Breaking the Port with a piece on its depot: the piece goes back to the Controller (not left behind or redone). */
    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void brokenPortHandsThePieceBack(final GameTestHelper h) {
        final StationBuilder.Stations s = stations(h, List.of("press"));
        final BlockPos port = s.ports().get(0);
        final var portState = h.getLevel().getBlockState(port);
        ready(h)
            .thenExecute(() -> {
                controller(h).insertIntoNetwork(new ItemResource(Items.COPPER_INGOT), 2, Action.EXECUTE);
                accept(h, List.of(res(Items.COPPER_INGOT, 1)));
            })
            .thenWaitUntil(() -> h.assertTrue(controller(h).piecesAtPorts() == 1, "Waiting for the piece at the station"))
            .thenExecute(() -> {
                h.getLevel().destroyBlock(port, false);
                assertDepotsEmpty(h, s);
                assertNoItemEntities(h);
                h.assertTrue(controller(h).piecesAtPorts() == 0, "The piece should go back to the Controller");
            })
            .thenIdle(20)
            .thenExecute(() -> h.getLevel().setBlock(port, portState, 3))
            .thenWaitUntil(waitFor(h, item("create:copper_sheet"), 1))
            .thenExecute(() -> {
                assertEquals(1, amount(controller(h), item("create:copper_sheet")), "A single sheet");
                assertEquals(2, amount(controller(h), Items.COPPER_INGOT), "It was not redone with copper from the network");
                assertNoItemEntities(h);
            })
            .thenSucceed();
    }

    /** The recipe lookup is cached: the second time, with the same ingredients, it isn't searched again. */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void recipeLookupIsCached(final GameTestHelper h) {
        final List<ResourceAmount> copper = List.of(res(Items.COPPER_INGOT, 1));
        final var first = dev.rscreatebridge.assembly.Recipes.matches(h.getLevel(), copper);
        final var second = dev.rscreatebridge.assembly.Recipes.matches(h.getLevel(), List.of(res(Items.COPPER_INGOT, 1)));
        h.assertTrue(!first.isEmpty(), "Copper should match the sheet recipe");
        h.assertTrue(first == second, "The second lookup should come from the cache");
        h.assertTrue(dev.rscreatebridge.assembly.Recipes.matches(h.getLevel(), List.of(res(Items.BEDROCK, 1))).isEmpty(),
            "Bedrock matches no recipe");
        h.succeed();
    }

    /** Always scrap: 1 attempt + 3 retries (new base items from the network), then it gives up. */
    @GameTest(template = "empty", timeoutTicks = 3000)
    public static void alwaysScrapGivesUp(final GameTestHelper h) {
        stations(h, List.of("press"));
        ready(h)
            .thenExecute(() -> {
                controller(h).insertIntoNetwork(new ItemResource(Items.CLAY_BALL), 5, Action.EXECUTE);
                accept(h, List.of(res(Items.CLAY_BALL, 1)));
            })
            .thenWaitUntil(waitFor(h, Items.DIRT, 4))
            .thenIdle(20)
            .thenExecute(() -> {
                assertEquals(4, amount(controller(h), Items.DIRT), "Scrap devuelto (1 + 3 reintentos)");
                assertEquals(2, amount(controller(h), Items.CLAY_BALL), "Arcilla restante");
                h.assertTrue(controller(h).isStatusProblem(), "It should report that it gave up");
                assertNoItemEntities(h);
            })
            .thenSucceed();
    }

    /** A machine the recipe needs is missing: rejected (RS waits) instead of taking materials. */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void missingMachineRejects(final GameTestHelper h) {
        stations(h, List.of("deployer"));
        ready(h)
            .thenExecute(() -> {
                final ExternalPatternSink.Result r = sink(h).accept(List.of(res(item("create:powdered_obsidian"), 1),
                    new ResourceAmount(LAVA, 500)), Action.SIMULATE);
                h.assertTrue(r == ExternalPatternSink.Result.REJECTED, "It should reject without a spout and a press: " + r);
                h.assertTrue(controller(h).getStatus().getString().toLowerCase().contains("spout"),
                    "The status should name the missing machine: " + controller(h).getStatus().getString());
            })
            .thenSucceed();
    }

    /** The press is not turning: the piece waits; once the motor is placed, it finishes. */
    @GameTest(template = "empty", timeoutTicks = 3000)
    public static void stoppedMachineWaitsThenRecovers(final GameTestHelper h) {
        final StationBuilder.Stations s = stations(h, ALL);
        final BlockPos pressMotor = s.motors().get(0); // absolute
        h.getLevel().setBlock(pressMotor, Blocks.AIR.defaultBlockState(), 3);
        ready(h)
            .thenExecute(() -> {
                final var press = h.getLevel().getBlockEntity(s.machines().get(1));
                h.assertTrue(press instanceof KineticBlockEntity k && k.getSpeed() == 0, "The press is still turning");
                accept(h, List.of(res(Items.FLINT, 1), res(Items.STICK, 2), new ResourceAmount(LAVA, 200)));
            })
            .thenIdle(200)
            .thenExecute(() -> {
                assertEquals(0, amount(controller(h), Items.EMERALD), "It can't finish without the press");
                h.assertTrue(!controller(h).isEmptyForRs(), "It keeps the job");
                StationBuilder.motor(h.getLevel(), pressMotor, Direction.SOUTH);
            })
            .thenWaitUntil(waitFor(h, Items.EMERALD, 1))
            .thenSucceed();
    }

    /** Aborting with pieces in the machines: pieces and materials go back to the network. Nothing is lost. */
    @GameTest(template = "empty", timeoutTicks = 2000)
    public static void abortReturnsEverything(final GameTestHelper h) {
        final StationBuilder.Stations s = stations(h, ALL);
        ready(h)
            .thenExecute(() -> accept(h, List.of(res(Items.FLINT, 2), res(Items.STICK, 4), new ResourceAmount(LAVA, 400))))
            .thenWaitUntil(() -> h.assertTrue(controller(h).piecesAtPorts() > 0, "Waiting for pieces in machines"))
            .thenIdle(10)
            .thenExecute(() -> controller(h).abortAll(false))
            .thenIdle(140)
            .thenExecute(() -> {
                final AssemblyControllerBlockEntity be = controller(h);
                final long pieces = be.getNetworkItemCountAnyComponents(Items.FLINT)
                    + be.getNetworkItemCountAnyComponents(Items.NETHER_BRICK)
                    + be.getNetworkItemCountAnyComponents(Items.EMERALD);
                assertEquals(2, pieces, "Pieces back in the network");
                h.assertTrue(be.isEmptyForRs(), "Empty queue");
                assertDepotsEmpty(h, s);
                assertNoItemEntities(h);
            })
            .thenSucceed();
    }

    /** Saving and reloading the controller mid-job (like closing the world): it still finishes. */
    @GameTest(template = "empty", timeoutTicks = 3000)
    public static void reloadMidWork(final GameTestHelper h) {
        stations(h, ALL);
        ready(h)
            .thenExecute(() -> accept(h, List.of(res(Items.FLINT, 2), res(Items.STICK, 4), new ResourceAmount(LAVA, 400))))
            .thenIdle(30)
            .thenExecute(() -> {
                final AssemblyControllerBlockEntity be = controller(h);
                final var registries = h.getLevel().registryAccess();
                final var saved = be.saveWithoutMetadata(registries);
                final AssemblyControllerBlockEntity fresh = new AssemblyControllerBlockEntity(be.getBlockPos(), be.getBlockState());
                fresh.loadWithComponents(saved, registries);
                h.assertTrue(saved.equals(fresh.saveWithoutMetadata(registries)), "NBT round trip: " + saved);
                be.loadWithComponents(saved, registries);
            })
            .thenWaitUntil(waitFor(h, Items.EMERALD, 2))
            .thenSucceed();
    }

    /** Cutting the network with work in progress: the warning shows; once it is back, it finishes and clears it. */
    @GameTest(template = "empty", timeoutTicks = 3000)
    public static void networkLossAndRecovery(final GameTestHelper h) {
        stations(h, ALL);
        ready(h)
            .thenExecute(() -> {
                accept(h, List.of(res(Items.FLINT, 1), res(Items.STICK, 2), new ResourceAmount(LAVA, 200)));
                h.setBlock(O.above(), Blocks.AIR);
            })
            .thenWaitUntil(() -> h.assertTrue(controller(h).isStatusProblem() && !controller(h).isNetworkActive(),
                "Waiting for the warning: " + controller(h).getStatus().getString()))
            .thenExecute(() -> h.setBlock(O.above(), block("refinedstorage:creative_controller")))
            .thenWaitUntil(() -> {
                waitFor(h, Items.EMERALD, 1).run();
                h.assertTrue(!controller(h).isStatusProblem(), "The warning should be cleared");
            })
            .thenSucceed();
    }

    /**
     * Like an RS importer or exporter, a port only connects to the network through its back: a cable on a side
     * does nothing, and turning the port with a wrench moves the connection.
     */
    @GameTest(template = "empty", timeoutTicks = 300)
    public static void portConnectsOnlyThroughItsBack(final GameTestHelper h) {
        final BlockPos network = new BlockPos(2, 1, 2);
        final BlockPos port = new BlockPos(3, 1, 2);
        h.setBlock(network, block("refinedstorage:creative_controller"));
        // The port faces north (the station side); its back is south. The controller is to its west: a side.
        h.setBlock(port, dev.rscreatebridge.Registration.ASSEMBLY_PORT.get().defaultBlockState()
            .setValue(dev.rscreatebridge.assembly.AssemblyPortBlock.FACING, Direction.NORTH));
        final java.util.function.Supplier<dev.rscreatebridge.assembly.AssemblyPortBlockEntity> be = () -> h.getBlockEntity(port);
        h.startSequence()
            .thenIdle(30)
            .thenExecute(() -> h.assertTrue(!be.get().isConnected(), "A network on the side should not connect"))
            .thenExecute(() -> h.setBlock(port, h.getBlockState(port)
                .setValue(dev.rscreatebridge.assembly.AssemblyPortBlock.FACING, Direction.EAST)))
            .thenWaitUntil(() -> h.assertTrue(be.get().isConnected() && be.get().isNetworkActive(),
                "Turned to face east, its back (west) touches the network and should connect"))
            .thenExecute(() -> h.setBlock(port, h.getBlockState(port)
                .setValue(dev.rscreatebridge.assembly.AssemblyPortBlock.FACING, Direction.SOUTH)))
            .thenWaitUntil(() -> h.assertTrue(!be.get().isConnected(), "Turned again, it should disconnect"))
            .thenSucceed();
    }

    /** Ports expose no inventory: a funnel or hopper next to them can't insert or extract anything. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void blocksExposeNoInventory(final GameTestHelper h) {
        final StationBuilder.Stations s = stations(h, List.of("deployer"));
        ready(h)
            .thenExecute(() -> {
                for (final Direction side : Direction.values()) {
                    h.assertTrue(h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, s.ports().get(0), side) == null,
                        "The port exposes an inventory on " + side);
                    h.assertTrue(h.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, s.controller(), side) == null,
                        "The controller exposes an inventory on " + side);
                }
            })
            .thenSucceed();
    }

    /** What the Goggles show travels to the client in the update tag (controller and port). */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void goggleLinesSync(final GameTestHelper h) {
        final StationBuilder.Stations s = stations(h, List.of("deployer"));
        ready(h)
            .thenExecute(() -> {
                final var registries = h.getLevel().registryAccess();
                final AssemblyControllerBlockEntity be = controller(h);
                final AssemblyControllerBlockEntity client = new AssemblyControllerBlockEntity(be.getBlockPos(), be.getBlockState());
                client.loadWithComponents(be.getUpdateTag(registries), registries);
                final List<net.minecraft.network.chat.Component> tip = new java.util.ArrayList<>();
                h.assertTrue(client.addToGoggleTooltip(tip, false), "The controller showed nothing with Goggles");
                final String text = tip.stream().map(net.minecraft.network.chat.Component::getString)
                    .reduce("", (a, b) -> a + "\n" + b);
                h.assertTrue(text.contains("1 deployer"), "The Goggles should list the port: " + text);

                final dev.rscreatebridge.assembly.AssemblyPortBlockEntity port =
                    (dev.rscreatebridge.assembly.AssemblyPortBlockEntity) h.getLevel().getBlockEntity(s.ports().get(0));
                final dev.rscreatebridge.assembly.AssemblyPortBlockEntity portClient =
                    new dev.rscreatebridge.assembly.AssemblyPortBlockEntity(port.getBlockPos(), port.getBlockState());
                portClient.loadWithComponents(port.getUpdateTag(registries), registries);
                final List<net.minecraft.network.chat.Component> portTip = new java.util.ArrayList<>();
                h.assertTrue(portClient.addToGoggleTooltip(portTip, false), "The port showed nothing with Goggles");
                h.assertTrue(portTip.stream().anyMatch(c -> c.getString().contains("Deployer")),
                    "The port should name its machine: " + portTip);
            })
            .thenSucceed();
    }

    // ------------------------------------------------------------------ with the real Autocrafter

    private static void withAutocrafter(final GameTestHelper h, final List<String> kinds, final List<ResourceAmount> pattern,
                                        final Item output, final Consumer<AssemblyControllerBlockEntity> fillNetwork,
                                        final long requested, final Consumer<AssemblyControllerBlockEntity> check) {
        final StationBuilder.Stations s = stations(h, kinds);
        // Autocrafter north of the controller, facing it, connected to the network above with a cable.
        h.setBlock(O.north().above(), block("refinedstorage:cable"));
        DemoBuilder.autocrafter(h.getLevel(), h.absolutePos(O.north()), Direction.SOUTH,
            List.of(processingPattern(pattern, List.of(res(output, 1)))));
        ready(h)
            .thenExecute(() -> {
                fillNetwork.accept(controller(h));
                request(controller(h), output, requested);
            })
            .thenWaitUntil(waitFor(h, output, requested))
            .thenIdle(140)
            .thenExecute(() -> {
                assertEquals(requested, amount(controller(h), output), "Product (no duplicates)");
                check.accept(controller(h));
                assertDepotsEmpty(h, s);
                assertNoItemEntities(h);
            })
            .thenSucceed();
    }

    /** The pattern the JEI "+" builds (first alternative of each entry). */
    private static List<ResourceAmount> jeiPattern(final GameTestHelper h, final String recipe) {
        final dev.rscreatebridge.assembly.AssemblyRecipeInfo info = dev.rscreatebridge.assembly.AssemblyRecipeInfo.byId(
            h.getLevel(), net.minecraft.resources.ResourceLocation.parse(recipe));
        if (info == null) {
            throw new GameTestAssertException("Recipe not found: " + recipe);
        }
        return info.fullKitForPattern().stream().map(List::getFirst).toList();
    }

    private static long kitAmount(final List<ResourceAmount> kit, final Item item) {
        return kit.stream().filter(r -> r.resource() instanceof ItemResource ir && ir.item() == item)
            .mapToLong(ResourceAmount::amount).sum();
    }

    /** The JEI "+" builds the pattern with what all the loops need (not just one, as JEI shows). */
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void jeiButtonPatternHasFullAmounts(final GameTestHelper h) {
        final List<ResourceAmount> precision = jeiPattern(h, "create:sequenced_assembly/precision_mechanism");
        assertEquals(4, precision.size(), "Precision mechanism pattern entries: " + precision);
        assertEquals(1, kitAmount(precision, item("create:golden_sheet")), "Golden sheet");
        assertEquals(5, kitAmount(precision, item("create:cogwheel")), "Engranajes (5 vueltas)");
        assertEquals(5, kitAmount(precision, item("create:large_cogwheel")), "Engranajes grandes (5 vueltas)");
        assertEquals(5, kitAmount(precision, Items.IRON_NUGGET), "Pepitas (5 vueltas)");
        final List<ResourceAmount> track = jeiPattern(h, "create:sequenced_assembly/track");
        assertEquals(2, track.size(), "Track pattern entries (both nuggets together): " + track);
        assertEquals(2, kitAmount(track, Items.IRON_NUGGET), "Track nuggets");
        final List<ResourceAmount> sturdy = jeiPattern(h, "create:sequenced_assembly/sturdy_sheet");
        assertEquals(500, sturdy.stream().filter(r -> r.resource() instanceof FluidResource)
            .mapToLong(ResourceAmount::amount).sum(), "Sturdy sheet lava");
        h.succeed();
    }

    /** Real Autocrafter with the pattern the JEI "+" builds: RS reserves everything and nothing is left in the network. */
    @GameTest(template = "empty", timeoutTicks = 4000)
    public static void autocrafterFullPattern(final GameTestHelper h) {
        final List<ResourceAmount> pattern = jeiPattern(h, "rscbtestkit:test_all_machines");
        assertEquals(2, kitAmount(pattern, Items.STICK), "Pattern sticks (2 loops)");
        withAutocrafter(h, ALL, pattern,
            Items.EMERALD,
            be -> fill(be, List.of(res(Items.FLINT, 1), res(Items.STICK, 2), new ResourceAmount(LAVA, 200)), 2), 2,
            be -> {
                assertEquals(0, amount(be, Items.STICK), "Palos sobrantes");
                assertEquals(0, lava(be), "Leftover lava");
                assertEquals(0, amount(be, Items.FLINT), "Leftover flint");
            });
    }

    /** Real Autocrafter with the copper sheet pattern (what the Potato Cannon needs): 3 sheets with one press. */
    @GameTest(template = "empty", timeoutTicks = 3000)
    public static void autocrafterCopperSheet(final GameTestHelper h) {
        withAutocrafter(h, List.of("press"), List.of(res(Items.COPPER_INGOT, 1)), item("create:copper_sheet"),
            be -> fill(be, List.of(res(Items.COPPER_INGOT, 3)), 1), 3,
            be -> assertEquals(0, amount(be, Items.COPPER_INGOT), "Leftover copper"));
    }

    /** Real Autocrafter, JEI precision mechanism pattern, with a single deployer. */
    @GameTest(template = "empty", timeoutTicks = 12000)
    public static void autocrafterPrecisionJeiPattern(final GameTestHelper h) {
        final Item golden = item("create:golden_sheet");
        final Item cog = item("create:cogwheel");
        final Item large = item("create:large_cogwheel");
        withAutocrafter(h, List.of("deployer"),
            List.of(res(golden, 1), res(cog, 1), res(large, 1), res(Items.IRON_NUGGET, 1)),
            item("create:precision_mechanism"),
            be -> fill(be, List.of(res(golden, 5), res(cog, 25), res(large, 25), res(Items.IRON_NUGGET, 25)), 1), 1,
            be -> h.assertTrue(amount(be, golden) <= 4, "It should have used at least one sheet"));
    }
}
