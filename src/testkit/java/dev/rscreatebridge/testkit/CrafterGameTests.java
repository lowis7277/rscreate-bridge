package dev.rscreatebridge.testkit;

import com.refinedmods.refinedstorage.api.autocrafting.task.ExternalPatternSink;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.autocrafting.PlatformPatternProviderExternalPatternSink;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;

import dev.rscreatebridge.RSCreateBridge;
import dev.rscreatebridge.Registration;
import dev.rscreatebridge.assembly.AssemblyControllerBlockEntity;
import dev.rscreatebridge.common.BridgeNodeBlockEntity;
import dev.rscreatebridge.crafter.CrafterInterfaceBlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.createmod.catnip.math.Pointing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests with real RS2 and Create. Run them with {@code ./gradlew runGameTestServer}.
 * All ingredients are delivered through RS's composite factory (the same one the Autocrafter uses), so if
 * RS's generic item sink inserted on its own, it would show.
 */
@GameTestHolder(RSCBTestKit.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CrafterGameTests {
    private static final String TEMPLATE = "empty";

    private CrafterGameTests() {
    }

    // ================================================================== helpers

    static Block block(final String id) {
        final Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        if (block == Blocks.AIR) {
            throw new GameTestAssertException("Missing block " + id);
        }
        return block;
    }

    static Item item(final String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    }

    static ResourceAmount res(final Item item, final long amount) {
        return new ResourceAmount(new ItemResource(item), amount);
    }

    /** Minimal RS network: creative controller + storage block. */
    static void placeNetwork(final GameTestHelper h, final BlockPos controller) {
        h.setBlock(controller, block("refinedstorage:creative_controller"));
        h.setBlock(controller.below(), block("refinedstorage:64k_storage_block"));
    }

    static void placeMotor(final GameTestHelper h, final BlockPos pos, final Direction shaftTowards) {
        h.setBlock(pos, block("create:creative_motor").defaultBlockState().setValue(BlockStateProperties.FACING, shaftTowards));
    }

    static void speedUpMotor(final GameTestHelper h, final BlockPos pos) {
        if (h.getLevel().getBlockEntity(h.absolutePos(pos)) instanceof CreativeMotorBlockEntity motor) {
            motor.generatedSpeed.setValue(256);
        }
    }

    private static BlockState crafter(final Direction facing, final Direction target) {
        final BlockState base = block("create:mechanical_crafter").defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
        for (final Pointing pointing : Pointing.values()) {
            final BlockState state = base.setValue(MechanicalCrafterBlock.POINTING, pointing);
            if (MechanicalCrafterBlock.getTargetDirection(state) == target) {
                return state;
            }
        }
        throw new GameTestAssertException("No POINTING points " + target + " with facing " + facing);
    }

    /**
     * Crafter array on a vertical plane. {@code right} is the horizontal direction the columns grow in; the
     * head is at the bottom corner, at {@code origin}, and points at {@code headTarget} (the interface).
     * Columns > 0 point towards column 0; column 0 points down.
     */
    static BlockPos placeCrafterArray(final GameTestHelper h, final BlockPos origin, final Direction right,
                                                    final Direction facing, final int width, final int height,
                                                    final Direction headTarget) {
        for (int gx = 0; gx < width; gx++) {
            for (int gy = 0; gy < height; gy++) {
                final BlockPos pos = origin.relative(right, gx).above(gy);
                final Direction target = gx > 0 ? right.getOpposite() : gy > 0 ? Direction.DOWN : headTarget;
                h.setBlock(pos, crafter(facing, target));
            }
        }
        // Crafters have no shaft: they turn like cogwheels. A cogwheel below the head, on the same plane,
        // with a creative motor behind it.
        final BlockPos cog = origin.below();
        h.setBlock(cog, block("create:cogwheel").defaultBlockState().setValue(BlockStateProperties.AXIS, facing.getAxis()));
        final BlockPos motor = cog.relative(facing.getOpposite());
        placeMotor(h, motor, facing);
        return motor;
    }

    private static PlatformPatternProviderExternalPatternSink rsSink(final GameTestHelper h, final BlockPos target,
                                                                     final Direction autocrafterSide) {
        return RefinedStorageApi.INSTANCE.getPatternProviderExternalPatternSinkFactory()
            .create(h.getLevel(), h.absolutePos(target), autocrafterSide);
    }

    static void assertNoItemEntities(final GameTestHelper h) {
        final var entities = h.getEntities(EntityType.ITEM);
        if (!entities.isEmpty()) {
            final StringBuilder sb = new StringBuilder();
            entities.forEach(e -> sb.append(e.getItem()).append(' '));
            throw new GameTestAssertException("There are items lying in the world: " + sb);
        }
    }

    static void assertEquals(final long expected, final long actual, final String what) {
        if (expected != actual) {
            throw new GameTestAssertException(what + ": expected " + expected + ", got " + actual);
        }
    }

    static long amount(final BridgeNodeBlockEntity be, final Item item) {
        return be.getNetworkAmount(new ItemResource(item));
    }

    // ================================================================== Mechanical Crafters


    /** 5x5 crushing wheel with empty corners (started "like redstone"), no duplicates. */
    @GameTest(template = TEMPLATE, timeoutTicks = 1200)
    public static void crushingWheel5x5(final GameTestHelper h) {
        final BlockPos iface = new BlockPos(2, 2, 3);
        placeNetwork(h, new BlockPos(2, 2, 2));
        h.setBlock(iface, Registration.CRAFTER_INTERFACE.get());
        final BlockPos motor = placeCrafterArray(h, new BlockPos(3, 2, 3), Direction.EAST, Direction.SOUTH, 5, 5, Direction.WEST);
        runCrafterCase(h, iface, motor, List.of(
                res(item("create:andesite_alloy"), 16), res(Items.OAK_PLANKS, 4), res(Items.STONE, 1)),
            item("create:crushing_wheel"), 2);
    }

    /** Extendo grip: vertically asymmetric, not mirrored. If Y were inverted, Create wouldn't recognize it. */
    @GameTest(template = TEMPLATE, timeoutTicks = 1200)
    public static void extendoGripVertical(final GameTestHelper h) {
        final BlockPos iface = new BlockPos(2, 2, 3);
        placeNetwork(h, new BlockPos(2, 2, 2));
        h.setBlock(iface, Registration.CRAFTER_INTERFACE.get());
        final BlockPos motor = placeCrafterArray(h, new BlockPos(3, 2, 3), Direction.EAST, Direction.SOUTH, 5, 5, Direction.WEST);
        runCrafterCase(h, iface, motor, List.of(
                res(item("create:brass_ingot"), 1), res(item("create:precision_mechanism"), 1),
                res(Items.STICK, 6), res(item("create:brass_hand"), 1)),
            item("create:extendo_grip"), 1);
    }

    /** Test recipe asymmetric in X and Y, not mirrored, with the array facing SOUTH. */
    @GameTest(template = TEMPLATE, timeoutTicks = 1200)
    public static void asymmetricFacingSouth(final GameTestHelper h) {
        asymmetricCase(h, Direction.SOUTH, Direction.EAST);
    }

    /** The same with the array facing NORTH (left and right end up swapped in the world). */
    @GameTest(template = TEMPLATE, timeoutTicks = 1200)
    public static void asymmetricFacingNorth(final GameTestHelper h) {
        asymmetricCase(h, Direction.NORTH, Direction.EAST);
    }

    /** Array on the Z plane (facing EAST), columns growing north. */
    @GameTest(template = TEMPLATE, timeoutTicks = 1200)
    public static void asymmetricFacingEast(final GameTestHelper h) {
        asymmetricCase(h, Direction.EAST, Direction.NORTH);
    }

    private static void asymmetricCase(final GameTestHelper h, final Direction facing, final Direction right) {
        final BlockPos origin = new BlockPos(7, 2, 7);
        final BlockPos iface = origin.relative(right.getOpposite());
        final BlockPos controller = iface.relative(facing.getOpposite());
        placeNetwork(h, controller);
        h.setBlock(iface, Registration.CRAFTER_INTERFACE.get());
        final BlockPos motor = placeCrafterArray(h, origin, right, facing, 3, 3, right.getOpposite());
        runCrafterCase(h, iface, motor, List.of(
                res(Items.IRON_INGOT, 1), res(Items.GOLD_INGOT, 1), res(Items.DIAMOND, 1), res(Items.COAL, 1)),
            Items.BEACON, 1, facing.getOpposite());
    }

    private static void runCrafterCase(final GameTestHelper h, final BlockPos iface, final BlockPos motor,
                                       final List<ResourceAmount> inputs, final Item output, final int outputCount) {
        runCrafterCase(h, iface, motor, inputs, output, outputCount, Direction.NORTH);
    }

    private static void runCrafterCase(final GameTestHelper h, final BlockPos iface, final BlockPos motor,
                                       final List<ResourceAmount> inputs, final Item output, final int outputCount,
                                       final Direction autocrafterSide) {
        h.startSequence()
            .thenExecuteAfter(5, () -> speedUpMotor(h, motor))
            .thenWaitUntil(() -> {
                final CrafterInterfaceBlockEntity be = h.getBlockEntity(iface);
                if (!be.isNetworkActive()) {
                    throw new GameTestAssertException("The interface is not on an active network yet");
                }
            })
            .thenWaitUntil(() -> {
                final ExternalPatternSink.Result r = rsSink(h, iface, autocrafterSide).accept(inputs, Action.SIMULATE);
                if (r != ExternalPatternSink.Result.ACCEPTED) {
                    final CrafterInterfaceBlockEntity be = h.getBlockEntity(iface);
                    throw new GameTestAssertException("SIMULATE=" + r + " estado=" + be.getStatus().getString()
                        + " motor=" + speedAt(h, motor));
                }
            })
            .thenExecute(() -> {
                final ServerLevel level = h.getLevel();
                // No capability towards the Autocrafter (no double insertion); towards the crafter there is one.
                h.assertTrue(level.getCapability(Capabilities.ItemHandler.BLOCK, h.absolutePos(iface), autocrafterSide) == null,
                    "The interface should not expose items towards the Autocrafter");
                final PlatformPatternProviderExternalPatternSink sink = rsSink(h, iface, autocrafterSide);
                assertResult(ExternalPatternSink.Result.ACCEPTED, sink.accept(inputs, Action.SIMULATE), "SIMULATE");
                assertResult(ExternalPatternSink.Result.ACCEPTED, sink.accept(inputs, Action.EXECUTE), "EXECUTE");
                // While it is crafting, a second request has to wait.
                assertResult(ExternalPatternSink.Result.REJECTED, sink.accept(inputs, Action.SIMULATE), "SIMULATE ocupado");
            })
            .thenWaitUntil(() -> {
                final CrafterInterfaceBlockEntity be = h.getBlockEntity(iface);
                assertEquals(outputCount, amount(be, output), "Result in the network");
            })
            .thenIdle(20)
            .thenExecute(() -> {
                final CrafterInterfaceBlockEntity be = h.getBlockEntity(iface);
                assertEquals(outputCount, amount(be, output), "Result in the network (no duplicates)");
                for (final ResourceAmount in : inputs) {
                    assertEquals(0, be.getNetworkAmount(in.resource()), "Leftover ingredient " + in.resource());
                }
                assertNoItemEntities(h);
            })
            .thenSucceed();
    }

    static String speedAt(final GameTestHelper h, final BlockPos pos) {
        return h.getLevel().getBlockEntity(h.absolutePos(pos)) instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity k
            ? k.getSpeed() + "(teo " + k.getTheoreticalSpeed() + ", over " + k.isOverStressed() + ", src " + k.hasSource()
                + ", net " + k.hasNetwork() + ")" : "no BE";
    }

    private static void assertResult(final ExternalPatternSink.Result expected, final ExternalPatternSink.Result actual,
                                     final String what) {
        if (expected != actual) {
            throw new GameTestAssertException(what + ": esperado " + expected + ", dio " + actual);
        }
    }

    /** Ingredients that are no recipe: rejected without touching any crafter. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void crafterRejectsUnknownRecipe(final GameTestHelper h) {
        final BlockPos iface = new BlockPos(2, 2, 3);
        placeNetwork(h, new BlockPos(2, 2, 2));
        h.setBlock(iface, Registration.CRAFTER_INTERFACE.get());
        final BlockPos motor = placeCrafterArray(h, new BlockPos(3, 2, 3), Direction.EAST, Direction.SOUTH, 3, 3, Direction.WEST);
        h.startSequence()
            .thenExecuteAfter(5, () -> speedUpMotor(h, motor))
            .thenWaitUntil(() -> h.assertTrue(((CrafterInterfaceBlockEntity) h.getBlockEntity(iface)).isNetworkActive(), "red"))
            .thenIdle(10)
            .thenExecute(() -> {
                final List<ResourceAmount> junk = List.of(res(Items.DIRT, 3), res(Items.SAND, 2));
                final PlatformPatternProviderExternalPatternSink sink = rsSink(h, iface, Direction.NORTH);
                assertResult(ExternalPatternSink.Result.REJECTED, sink.accept(junk, Action.SIMULATE), "SIMULATE");
                assertResult(ExternalPatternSink.Result.REJECTED, sink.accept(junk, Action.EXECUTE), "EXECUTE");
                final CrafterInterfaceBlockEntity be = h.getBlockEntity(iface);
                h.assertTrue(be.isEmptyForRs(), "The crafters should stay empty");
            })
            .thenSucceed();
    }

    /** No rotation: rejected (RS waits) instead of leaving items stuck. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void crafterRejectsWhenNotRotating(final GameTestHelper h) {
        final BlockPos iface = new BlockPos(2, 2, 3);
        placeNetwork(h, new BlockPos(2, 2, 2));
        h.setBlock(iface, Registration.CRAFTER_INTERFACE.get());
        final BlockPos motor = placeCrafterArray(h, new BlockPos(3, 2, 3), Direction.EAST, Direction.SOUTH, 3, 3, Direction.WEST);
        h.setBlock(motor, Blocks.AIR); // no motor
        h.startSequence()
            .thenWaitUntil(() -> h.assertTrue(((CrafterInterfaceBlockEntity) h.getBlockEntity(iface)).isNetworkActive(), "red"))
            .thenIdle(10)
            .thenExecute(() -> {
                final List<ResourceAmount> in = List.of(
                    res(Items.IRON_INGOT, 1), res(Items.GOLD_INGOT, 1), res(Items.DIAMOND, 1), res(Items.COAL, 1));
                assertResult(ExternalPatternSink.Result.REJECTED, rsSink(h, iface, Direction.NORTH).accept(in, Action.SIMULATE),
                    "SIMULATE without rotation");
            })
            .thenSucceed();
    }

    /** Regression: RS's factory still works the same with blocks that aren't ours (a chest). */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void plainChestStillWorks(final GameTestHelper h) {
        final BlockPos chest = new BlockPos(3, 2, 3);
        h.setBlock(chest, Blocks.CHEST);
        h.startSequence()
            .thenIdle(2)
            .thenExecute(() -> {
                final List<ResourceAmount> in = List.of(res(Items.IRON_INGOT, 5), res(Items.GOLD_INGOT, 2));
                final PlatformPatternProviderExternalPatternSink sink = rsSink(h, chest, Direction.NORTH);
                assertResult(ExternalPatternSink.Result.ACCEPTED, sink.accept(in, Action.SIMULATE), "SIMULATE");
                assertResult(ExternalPatternSink.Result.ACCEPTED, sink.accept(in, Action.EXECUTE), "EXECUTE");
                final ChestBlockEntity be = h.getBlockEntity(chest);
                int iron = 0;
                int gold = 0;
                for (int i = 0; i < be.getContainerSize(); i++) {
                    final ItemStack s = be.getItem(i);
                    if (s.is(Items.IRON_INGOT)) {
                        iron += s.getCount();
                    }
                    if (s.is(Items.GOLD_INGOT)) {
                        gold += s.getCount();
                    }
                }
                assertEquals(5, iron, "Iron in the chest");
                assertEquals(2, gold, "Gold in the chest");
            })
            .thenSucceed();
    }




    /** Materials for one full run, computed straight from the Create recipe. */
    static List<ResourceAmount> kitFor(final GameTestHelper h, final String recipeId) {
        final var holder = h.getLevel().getRecipeManager().byKey(ResourceLocation.parse(recipeId)).orElseThrow();
        final SequencedAssemblyRecipe recipe = (SequencedAssemblyRecipe) holder.value();
        final List<ResourceAmount> kit = new ArrayList<>();
        kit.add(res(recipe.getIngredient().getItems()[0].getItem(), 1));
        final Map<Item, Long> items = new java.util.LinkedHashMap<>();
        long lava = 0;
        for (final var seq : recipe.getSequence()) {
            final var step = seq.getRecipe();
            if (step.getIngredients().size() > 1) {
                items.merge(step.getIngredients().get(1).getItems()[0].getItem(), (long) recipe.getLoops(), Long::sum);
            }
            if (!step.getFluidIngredients().isEmpty()) {
                lava += (long) step.getFluidIngredients().get(0).amount() * recipe.getLoops();
            }
        }
        items.forEach((i, n) -> kit.add(res(i, n)));
        if (lava > 0) {
            kit.add(new ResourceAmount(new FluidResource(Fluids.LAVA), lava));
        }
        return kit;
    }








}
