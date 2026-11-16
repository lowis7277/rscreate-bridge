package dev.rscreatebridge.testkit;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.autocrafting.PatternState;
import com.refinedmods.refinedstorage.common.autocrafting.ProcessingPatternState;
import com.refinedmods.refinedstorage.common.autocrafting.autocrafter.AutocrafterBlockEntity;
import com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternType;
import com.refinedmods.refinedstorage.common.content.DataComponents;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;

import dev.rscreatebridge.RSCreateBridge;
import dev.rscreatebridge.Registration;
import dev.rscreatebridge.assembly.AssemblyControllerBlockEntity;
import dev.rscreatebridge.common.BridgeNodeBlockEntity;
import dev.rscreatebridge.crafter.CrafterInterfaceBlockEntity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static dev.rscreatebridge.testkit.CrafterGameTests.*;

/**
 * End to end: the real RS2 Autocrafter with an encoded processing pattern and an autocrafting request
 * (ensureTask), just like requesting from the grid. With double insertion there would be extra items (in the
 * crafters, in the world or in the network).
 */
@GameTestHolder(RSCBTestKit.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EndToEndGameTests {
    private EndToEndGameTests() {
    }

    static ItemStack processingPattern(final List<ResourceAmount> inputs, final List<ResourceAmount> outputs) {
        final ItemStack stack = new ItemStack(com.refinedmods.refinedstorage.common.content.Items.INSTANCE.getPattern());
        stack.set(DataComponents.INSTANCE.getPatternState(), new PatternState(UUID.randomUUID(), PatternType.PROCESSING));
        final List<Optional<ProcessingPatternState.ProcessingIngredient>> ingredients = new ArrayList<>();
        for (final ResourceAmount input : inputs) {
            ingredients.add(Optional.of(new ProcessingPatternState.ProcessingIngredient(input, List.of())));
        }
        final List<Optional<ResourceAmount>> outs = new ArrayList<>();
        for (final ResourceAmount output : outputs) {
            outs.add(Optional.of(output));
        }
        stack.set(DataComponents.INSTANCE.getProcessingPatternState(), new ProcessingPatternState(ingredients, outs));
        return stack;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState withDirection(final BlockState state, final Direction direction) {
        final Property property = state.getBlock().getStateDefinition().getProperty("direction");
        if (property == null) {
            throw new GameTestAssertException("The Autocrafter has no 'direction' property");
        }
        return state.setValue(property, (Comparable) property.getValue(direction.getSerializedName()).orElseThrow());
    }

    static void placeAutocrafter(final GameTestHelper h, final BlockPos pos, final Direction facing,
                                         final ItemStack pattern) {
        h.setBlock(pos, withDirection(block("refinedstorage:autocrafter").defaultBlockState(), facing));
        final AutocrafterBlockEntity be = h.getBlockEntity(pos);
        try {
            final Field field = AutocrafterBlockEntity.class.getDeclaredField("patternContainer");
            field.setAccessible(true);
            ((Container) field.get(be)).setItem(0, pattern);
        } catch (final ReflectiveOperationException e) {
            throw new GameTestAssertException("Could not load the pattern into the Autocrafter: " + e);
        }
    }

    static void request(final BridgeNodeBlockEntity anyNode, final Item output, final long amount) {
        final Network network = anyNode.getNetwork();
        if (network == null) {
            throw new GameTestAssertException("No network");
        }
        final AutocraftingNetworkComponent autocrafting = network.getComponent(AutocraftingNetworkComponent.class);
        final AutocraftingNetworkComponent.EnsureResult result = autocrafting.ensureTask(new ItemResource(output), amount,
            BridgeNodeBlockEntity.ACTOR, CancellationToken.NONE);
        if (result != AutocraftingNetworkComponent.EnsureResult.TASK_CREATED) {
            throw new GameTestAssertException("RS did not create the task: " + result + " (patterns: "
                + autocrafting.getOutputs() + ")");
        }
    }

    static void fill(final BridgeNodeBlockEntity node, final List<ResourceAmount> kit, final int times) {
        for (int i = 0; i < times; i++) {
            for (final ResourceAmount r : kit) {
                final long inserted = node.insertIntoNetwork(r.resource(), r.amount(), Action.EXECUTE);
                if (inserted != r.amount()) {
                    throw new GameTestAssertException("The network did not accept " + r);
                }
            }
        }
    }

    // ------------------------------------------------------------------ Mechanical Crafters

    /** Requesting 4 crushing wheels = 2 pattern iterations (16 alloy, 4 planks, 1 stone -> 2). */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void autocrafterCrushingWheels(final GameTestHelper h) {
        final BlockPos iface = new BlockPos(2, 2, 3);
        placeNetwork(h, new BlockPos(2, 2, 2));
        h.setBlock(iface, Registration.CRAFTER_INTERFACE.get());
        final BlockPos motor = placeCrafterArray(h, new BlockPos(3, 2, 3), Direction.EAST, Direction.SOUTH, 5, 5, Direction.WEST);
        h.setBlock(new BlockPos(1, 2, 2), block("refinedstorage:cable"));
        final Item alloy = item("create:andesite_alloy");
        final List<ResourceAmount> inputs = List.of(res(alloy, 16), res(Items.OAK_PLANKS, 4), res(Items.STONE, 1));
        final Item wheel = item("create:crushing_wheel");
        placeAutocrafter(h, new BlockPos(1, 2, 3), Direction.EAST, processingPattern(inputs, List.of(res(wheel, 2))));
        h.startSequence()
            .thenExecuteAfter(5, () -> speedUpMotor(h, motor))
            .thenWaitUntil(() -> h.assertTrue(((CrafterInterfaceBlockEntity) h.getBlockEntity(iface)).isNetworkActive(), "red"))
            .thenIdle(20)
            .thenExecute(() -> {
                final CrafterInterfaceBlockEntity be = h.getBlockEntity(iface);
                fill(be, inputs, 2);
                request(be, wheel, 4);
            })
            .thenWaitUntil(() -> {
                final CrafterInterfaceBlockEntity be = h.getBlockEntity(iface);
                if (amount(be, wheel) < 4) {
                    throw new GameTestAssertException("Wheels: " + amount(be, wheel) + " - " + be.getStatus().getString());
                }
            })
            .thenIdle(40)
            .thenExecute(() -> {
                final CrafterInterfaceBlockEntity be = h.getBlockEntity(iface);
                assertEquals(4, amount(be, wheel), "Crushing wheels (no duplicates)");
                assertEquals(0, amount(be, alloy), "Leftover alloy");
                assertEquals(0, amount(be, Items.OAK_PLANKS), "Tablas sobrantes");
                assertEquals(0, amount(be, Items.STONE), "Leftover stone");
                h.assertTrue(be.isEmptyForRs(), "The crafters should end up empty");
                assertNoItemEntities(h);
            })
            .thenSucceed();
    }


    /** Removing a crafter mid-craft: detected, reported and redone with materials from the network. */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void crafterRemovedMidCraftIsRetried(final GameTestHelper h) {
        final BlockPos iface = new BlockPos(2, 2, 3);
        placeNetwork(h, new BlockPos(2, 2, 2));
        h.setBlock(iface, Registration.CRAFTER_INTERFACE.get());
        final BlockPos motor = placeCrafterArray(h, new BlockPos(3, 2, 3), Direction.EAST, Direction.SOUTH, 5, 5, Direction.WEST);
        h.setBlock(new BlockPos(1, 2, 2), block("refinedstorage:cable"));
        final Item alloy = item("create:andesite_alloy");
        final List<ResourceAmount> inputs = List.of(res(alloy, 16), res(Items.OAK_PLANKS, 4), res(Items.STONE, 1));
        final Item wheel = item("create:crushing_wheel");
        placeAutocrafter(h, new BlockPos(1, 2, 3), Direction.EAST, processingPattern(inputs, List.of(res(wheel, 2))));
        final BlockPos victim = new BlockPos(7, 4, 3); // a crafter in the middle
        final BlockState[] victimState = new BlockState[1];
        h.startSequence()
            .thenExecuteAfter(5, () -> speedUpMotor(h, motor))
            .thenWaitUntil(() -> h.assertTrue(((CrafterInterfaceBlockEntity) h.getBlockEntity(iface)).isNetworkActive(), "red"))
            .thenIdle(20)
            .thenExecute(() -> {
                final CrafterInterfaceBlockEntity be = h.getBlockEntity(iface);
                fill(be, inputs, 2); // one kit for the request and another one for the retry
                request(be, wheel, 2);
            })
            .thenWaitUntil(() -> h.assertTrue(!((CrafterInterfaceBlockEntity) h.getBlockEntity(iface)).isEmptyForRs(),
                "waiting for the craft to start"))
            .thenExecute(() -> {
                victimState[0] = h.getLevel().getBlockState(h.absolutePos(victim));
                h.getLevel().destroyBlock(h.absolutePos(victim), false);
            })
            .thenWaitUntil(() -> {
                final CrafterInterfaceBlockEntity be = h.getBlockEntity(iface);
                if (!be.getStatus().getString().contains("lost")) {
                    throw new GameTestAssertException("Waiting for the lost craft warning: " + be.getStatus().getString());
                }
            })
            .thenExecute(() -> h.setBlock(victim, victimState[0]))
            .thenWaitUntil(() -> {
                final CrafterInterfaceBlockEntity be = h.getBlockEntity(iface);
                if (amount(be, wheel) < 2 || !be.isEmptyForRs()) {
                    throw new GameTestAssertException("Wheels: " + amount(be, wheel) + " - " + be.getStatus().getString());
                }
            })
            .thenIdle(20)
            .thenExecute(() -> {
                final CrafterInterfaceBlockEntity be = h.getBlockEntity(iface);
                assertEquals(2, amount(be, wheel), "Wheels (no duplicates)");
                assertEquals(0, amount(be, alloy), "Alloy: the second kit was used for the retry");
                // The items Create dropped when the crafter broke are still on the floor (21 in total).
                long dropped = 0;
                for (final var e : h.getEntities(net.minecraft.world.entity.EntityType.ITEM)) {
                    dropped += e.getItem().getCount();
                }
                h.assertTrue(dropped <= 21, "More items on the floor than the lost craft had: " + dropped);
            })
            .thenSucceed();
    }






}
