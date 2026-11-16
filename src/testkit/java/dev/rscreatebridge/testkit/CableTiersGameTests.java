package dev.rscreatebridge.testkit;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;

import dev.rscreatebridge.Registration;
import dev.rscreatebridge.assembly.AssemblyControllerBlockEntity;
import dev.rscreatebridge.crafter.CrafterInterfaceBlockEntity;

import java.lang.reflect.Field;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static dev.rscreatebridge.testkit.CrafterGameTests.amount;
import static dev.rscreatebridge.testkit.CrafterGameTests.assertEquals;
import static dev.rscreatebridge.testkit.CrafterGameTests.assertNoItemEntities;
import static dev.rscreatebridge.testkit.CrafterGameTests.block;
import static dev.rscreatebridge.testkit.CrafterGameTests.item;
import static dev.rscreatebridge.testkit.CrafterGameTests.placeCrafterArray;
import static dev.rscreatebridge.testkit.CrafterGameTests.placeNetwork;
import static dev.rscreatebridge.testkit.CrafterGameTests.res;
import static dev.rscreatebridge.testkit.CrafterGameTests.speedUpMotor;
import static dev.rscreatebridge.testkit.EndToEndGameTests.fill;
import static dev.rscreatebridge.testkit.EndToEndGameTests.processingPattern;
import static dev.rscreatebridge.testkit.EndToEndGameTests.request;

/**
 * Cable Tiers compatibility (the mod that adds Elite/Ultra/Mega/Creative autocrafters): the Elite Autocrafter
 * pointing at the Assembly Controller and at the Crafter Interface. Without Cable Tiers they pass right away.
 */
@GameTestHolder(RSCBTestKit.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CableTiersGameTests {
    private static final BlockPos O = new BlockPos(1, 1, 1);

    private CableTiersGameTests() {
    }

    /** Places a Cable Tiers autocrafter facing {@code facing} and loads the patterns into it. */
    static void tieredAutocrafter(final ServerLevel level, final BlockPos pos, final Direction facing,
                                  final List<ItemStack> patterns) {
        StationBuilder.set(level, pos, StationBuilder.rsDirectional("cabletiers:elite_autocrafter", facing));
        final BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            throw new GameTestAssertException("The Elite Autocrafter was not placed (is Cable Tiers missing?)");
        }
        try {
            Class<?> type = be.getClass();
            Field field = null;
            while (type != null && field == null) {
                try {
                    field = type.getDeclaredField("patternContainer");
                } catch (final NoSuchFieldException e) {
                    type = type.getSuperclass();
                }
            }
            if (field == null) {
                throw new GameTestAssertException("The Elite Autocrafter has no patternContainer");
            }
            field.setAccessible(true);
            final Container container = (Container) field.get(be);
            for (int i = 0; i < patterns.size(); i++) {
                container.setItem(i, patterns.get(i));
            }
        } catch (final ReflectiveOperationException e) {
            throw new GameTestAssertException("Could not load patterns into the Elite Autocrafter: " + e);
        }
    }

    /** Cable Tiers is optional: without it these tests have nothing to check. */
    private static boolean skipped(final GameTestHelper h) {
        if (net.neoforged.fml.ModList.get().isLoaded("cabletiers")) {
            return false;
        }
        h.succeed();
        return true;
    }

    private static AssemblyControllerBlockEntity controller(final GameTestHelper h) {
        return h.getBlockEntity(O);
    }

    private static void viaElite(final GameTestHelper h, final List<String> kinds, final List<ResourceAmount> pattern,
                                 final Item output, final List<ResourceAmount> kit, final int kits, final long requested) {
        StationBuilder.build(h.getLevel(), h.absolutePos(O), kinds, true);
        // Elite Autocrafter north of the controller, facing it, connected to the network above with a cable.
        h.setBlock(O.north().above(), block("refinedstorage:cable"));
        tieredAutocrafter(h.getLevel(), h.absolutePos(O.north()), Direction.SOUTH,
            List.of(processingPattern(pattern, List.of(res(output, 1)))));
        h.startSequence()
            .thenWaitUntil(() -> h.assertTrue(controller(h).isNetworkActive(), "Waiting for the network"))
            .thenIdle(25)
            .thenExecute(() -> {
                fill(controller(h), kit, kits);
                request(controller(h), output, requested);
            })
            .thenWaitUntil(() -> {
                final AssemblyControllerBlockEntity be = controller(h);
                if (amount(be, output) < requested || !be.isEmptyForRs()) {
                    throw new GameTestAssertException(output + ": " + amount(be, output) + "/" + requested + " - "
                        + be.getStatus().getString());
                }
            })
            .thenIdle(40)
            .thenExecute(() -> {
                assertEquals(requested, amount(controller(h), output), "Product (no duplicates)");
                for (final ResourceAmount r : kit) {
                    assertEquals(0, controller(h).getNetworkAmount(r.resource()), "Leftover " + r.resource());
                }
                assertNoItemEntities(h);
            })
            .thenSucceed();
    }

    /** Elite Autocrafter -> Controller: copper sheets with the press (single-step recipe). */
    @GameTest(template = "empty", timeoutTicks = 3000)
    public static void eliteAutocrafterCopperSheets(final GameTestHelper h) {
        if (skipped(h)) {
            return;
        }
        viaElite(h, List.of("press"), List.of(res(Items.COPPER_INGOT, 1)), item("create:copper_sheet"),
            List.of(res(Items.COPPER_INGOT, 1)), 4, 4);
    }

    /** Elite Autocrafter -> Controller: the test recipe using spout, press and deployer (full pattern). */
    @GameTest(template = "empty", timeoutTicks = 4000)
    public static void eliteAutocrafterAllMachines(final GameTestHelper h) {
        if (skipped(h)) {
            return;
        }
        final List<ResourceAmount> kit = List.of(res(Items.FLINT, 1), res(Items.STICK, 2),
            new ResourceAmount(new FluidResource(Fluids.LAVA), 200));
        viaElite(h, AssemblyGameTests.ALL, kit, Items.EMERALD, kit, 2, 2);
    }

    /** Elite Autocrafter -> Controller: brass in a mixer with a blaze burner. */
    @GameTest(template = "empty", timeoutTicks = 3000)
    public static void eliteAutocrafterBrass(final GameTestHelper h) {
        if (skipped(h)) {
            return;
        }
        final List<ResourceAmount> kit = List.of(res(Items.COPPER_INGOT, 1), res(item("create:zinc_ingot"), 1));
        viaElite(h, List.of("mixer_heated"), kit, item("create:brass_ingot"), kit, 2, 4);
    }

    /** Elite Autocrafter -> Crafter Interface: 4 crushing wheels (2 crafts on the 5x5 array). */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void eliteAutocrafterCrushingWheels(final GameTestHelper h) {
        if (skipped(h)) {
            return;
        }
        final BlockPos iface = new BlockPos(2, 2, 3);
        placeNetwork(h, new BlockPos(2, 2, 2));
        h.setBlock(iface, Registration.CRAFTER_INTERFACE.get());
        final BlockPos motor = placeCrafterArray(h, new BlockPos(3, 2, 3), Direction.EAST, Direction.SOUTH, 5, 5, Direction.WEST);
        h.setBlock(new BlockPos(1, 2, 2), block("refinedstorage:cable"));
        final Item alloy = item("create:andesite_alloy");
        final List<ResourceAmount> inputs = List.of(res(alloy, 16), res(Items.OAK_PLANKS, 4), res(Items.STONE, 1));
        final Item wheel = item("create:crushing_wheel");
        tieredAutocrafter(h.getLevel(), h.absolutePos(new BlockPos(1, 2, 3)), Direction.EAST,
            List.of(processingPattern(inputs, List.of(res(wheel, 2)))));
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
                h.assertTrue(be.isEmptyForRs(), "The crafters should end up empty");
                assertNoItemEntities(h);
            })
            .thenSucceed();
    }
}
