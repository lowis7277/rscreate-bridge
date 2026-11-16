package dev.rscreatebridge.testkit;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.autocrafting.autocrafter.AutocrafterBlockEntity;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.simibubi.create.content.fluids.tank.CreativeFluidTankBlockEntity;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

import dev.rscreatebridge.Registration;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import net.createmod.catnip.math.Pointing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;

import static dev.rscreatebridge.testkit.EndToEndGameTests.processingPattern;
import static dev.rscreatebridge.testkit.StationBuilder.block;
import static dev.rscreatebridge.testkit.StationBuilder.item;
import static dev.rscreatebridge.testkit.StationBuilder.rsDirectional;
import static dev.rscreatebridge.testkit.StationBuilder.set;

/**
 * The test instance demo, built in code (so it doesn't depend on structure templates, which with RS 2.0.9
 * rotate directional blocks wrongly). Coordinates relative to {@code origin} (north-west corner, floor level):
 * <ul>
 *   <li>z=0..2, north: RS network (creative controller, storage, grids), chests and infinite lava;</li>
 *   <li>x=3..9: Autocrafter -> Crafter Interface -> 5x5 array;</li>
 *   <li>x=11..27: Autocrafter -> Assembly Controller -> 5 stations (depot or basin + machine + Assembly
 *       Port): spout, press, 2 deployers and a mixer with a blaze burner, on the same network by cable.</li>
 * </ul>
 */
public final class DemoBuilder {
    public static final int SIZE_X = 28;
    public static final int SIZE_Y = 8;
    public static final int SIZE_Z = 12;
    public static final BlockPos CRAFTER_INTERFACE = new BlockPos(4, 1, 5);
    public static final BlockPos ASSEMBLY_CONTROLLER = new BlockPos(11, 1, 6);
    /** One station per machine, plus a second deployer to work in parallel. */
    public static final List<String> STATIONS = List.of("spout", "press", "deployer", "deployer", "mixer_heated");

    public record Demo(BlockPos origin, StationBuilder.Stations stations) {
    }

    private DemoBuilder() {
    }

    /** The demo starts 2 blocks south-east of whoever runs the command, with the floor one block below. */
    public static BlockPos originFrom(final Vec3 position) {
        return BlockPos.containing(position).offset(2, -1, 2);
    }

    public static void clear(final ServerLevel level, final BlockPos origin) {
        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) {
                for (int y = SIZE_Y - 1; y >= 0; y--) {
                    level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2 | 16);
                }
                level.setBlock(origin.offset(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            }
        }
    }

    public static Demo build(final ServerLevel level, final BlockPos origin) {
        clear(level, origin);
        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) {
                set(level, origin.offset(x, 0, z), Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }
        // --- RS network
        set(level, origin.offset(1, 1, 2), block("refinedstorage:creative_controller").defaultBlockState());
        set(level, origin.offset(1, 2, 2), block("refinedstorage:creative_storage_block").defaultBlockState());
        set(level, origin.offset(1, 3, 2), block("refinedstorage:creative_fluid_storage_block").defaultBlockState());
        for (int x = 2; x <= 19; x++) {
            set(level, origin.offset(x, 1, 2), block("refinedstorage:cable").defaultBlockState());
        }
        set(level, origin.offset(2, 1, 1), rsDirectional("refinedstorage:grid", Direction.SOUTH));
        set(level, origin.offset(3, 1, 1), rsDirectional("refinedstorage:pattern_grid", Direction.SOUTH));
        set(level, origin.offset(4, 1, 1), rsDirectional("refinedstorage:autocrafter_manager", Direction.SOUTH));
        set(level, origin.offset(5, 1, 1), rsDirectional("refinedstorage:autocrafting_monitor", Direction.SOUTH));

        // --- Materials: chests read by External Storage, and infinite lava (creative tank).
        chest(level, origin.offset(7, 1, 0), item("create:andesite_alloy"), 8, Items.OAK_PLANKS, 2, Items.STONE, 2,
            item("create:brass_ingot"), 1, item("create:brass_hand"), 1, Items.STICK, 2);
        chest(level, origin.offset(8, 1, 0), item("create:golden_sheet"), 2, item("create:cogwheel"), 3,
            item("create:large_cogwheel"), 3, Items.IRON_NUGGET, 4);
        chest(level, origin.offset(9, 1, 0), item("create:powdered_obsidian"), 2, Items.STONE_SLAB, 2,
            Items.COPPER_INGOT, 2, item("create:zinc_ingot"), 2);
        for (int x = 7; x <= 9; x++) {
            set(level, origin.offset(x, 1, 1), rsDirectional("refinedstorage:external_storage", Direction.NORTH));
        }
        set(level, origin.offset(10, 1, 0), block("create:creative_fluid_tank").defaultBlockState());
        if (level.getBlockEntity(origin.offset(10, 1, 0)) instanceof CreativeFluidTankBlockEntity tank
            && tank.getTankInventory() instanceof CreativeFluidTankBlockEntity.CreativeSmartFluidTank creative) {
            creative.setContainedFluid(new FluidStack(Fluids.LAVA, 1000));
        }
        set(level, origin.offset(10, 1, 1), rsDirectional("refinedstorage:external_storage", Direction.NORTH));

        // --- A) Mechanical Crafters
        for (final BlockPos c : List.of(new BlockPos(3, 1, 3), new BlockPos(3, 1, 4), new BlockPos(4, 1, 3), new BlockPos(4, 1, 4))) {
            set(level, origin.offset(c), block("refinedstorage:cable").defaultBlockState());
        }
        set(level, origin.offset(CRAFTER_INTERFACE), Registration.CRAFTER_INTERFACE.get().defaultBlockState());
        final Item alloy = item("create:andesite_alloy");
        autocrafter(level, origin.offset(3, 1, 5), Direction.EAST, List.of(
            processingPattern(List.of(CrafterGameTests.res(alloy, 16), CrafterGameTests.res(Items.OAK_PLANKS, 4),
                CrafterGameTests.res(Items.STONE, 1)), List.of(CrafterGameTests.res(item("create:crushing_wheel"), 2))),
            processingPattern(List.of(CrafterGameTests.res(item("create:brass_ingot"), 1),
                    CrafterGameTests.res(item("create:precision_mechanism"), 1), CrafterGameTests.res(Items.STICK, 6),
                    CrafterGameTests.res(item("create:brass_hand"), 1)),
                List.of(CrafterGameTests.res(item("create:extendo_grip"), 1)))));
        crafterArray(level, origin.offset(5, 1, 5), Direction.EAST, Direction.SOUTH, 5, 5, Direction.WEST);

        // --- B) Assembly: patterns with everything the recipe needs (the amounts of the full recipe, like the
        //        JEI "+"); the Autocrafter hands it to the Controller, which loads the machines of each station.
        for (final BlockPos c : List.of(new BlockPos(11, 1, 3), new BlockPos(11, 1, 4), new BlockPos(11, 2, 4),
            new BlockPos(11, 2, 5), new BlockPos(11, 2, 6))) {
            set(level, origin.offset(c), block("refinedstorage:cable").defaultBlockState());
        }
        final StationBuilder.Stations stations = StationBuilder.build(level, origin.offset(ASSEMBLY_CONTROLLER), STATIONS, false);
        autocrafter(level, origin.offset(11, 1, 5), Direction.SOUTH, List.of(
            processingPattern(List.of(CrafterGameTests.res(item("create:golden_sheet"), 1),
                    CrafterGameTests.res(item("create:cogwheel"), 5), CrafterGameTests.res(item("create:large_cogwheel"), 5),
                    CrafterGameTests.res(Items.IRON_NUGGET, 5)),
                List.of(CrafterGameTests.res(item("create:precision_mechanism"), 1))),
            processingPattern(List.of(CrafterGameTests.res(item("create:powdered_obsidian"), 1),
                    new ResourceAmount(new FluidResource(Fluids.LAVA), 500)),
                List.of(CrafterGameTests.res(item("create:sturdy_sheet"), 1))),
            processingPattern(List.of(CrafterGameTests.res(Items.STONE_SLAB, 1), CrafterGameTests.res(Items.IRON_NUGGET, 2)),
                List.of(CrafterGameTests.res(item("create:track"), 1))),
            processingPattern(List.of(CrafterGameTests.res(Items.COPPER_INGOT, 1), CrafterGameTests.res(item("create:zinc_ingot"), 1)),
                List.of(CrafterGameTests.res(item("create:brass_ingot"), 2)))));
        return new Demo(origin, stations);
    }

    private static void chest(final ServerLevel level, final BlockPos pos, final Object... itemAndStacks) {
        set(level, pos, Blocks.CHEST.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
        final ChestBlockEntity chest = (ChestBlockEntity) level.getBlockEntity(pos);
        int slot = 0;
        for (int i = 0; i < itemAndStacks.length; i += 2) {
            final Item it = (Item) itemAndStacks[i];
            for (int s = 0; s < (Integer) itemAndStacks[i + 1]; s++) {
                chest.setItem(slot++, new ItemStack(it, it.getDefaultMaxStackSize()));
            }
        }
    }

    static void autocrafter(final ServerLevel level, final BlockPos pos, final Direction facing, final List<ItemStack> patterns) {
        set(level, pos, rsDirectional("refinedstorage:autocrafter", facing));
        try {
            final Field field = AutocrafterBlockEntity.class.getDeclaredField("patternContainer");
            field.setAccessible(true);
            final Container container = (Container) field.get(level.getBlockEntity(pos));
            for (int i = 0; i < patterns.size(); i++) {
                container.setItem(i, patterns.get(i));
            }
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("Autocrafter: " + e, e);
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
        throw new IllegalStateException("POINTING");
    }

    private static void crafterArray(final ServerLevel level, final BlockPos origin, final Direction right, final Direction facing,
                                     final int width, final int height, final Direction headTarget) {
        for (int gx = 0; gx < width; gx++) {
            for (int gy = 0; gy < height; gy++) {
                final Direction target = gx > 0 ? right.getOpposite() : gy > 0 ? Direction.DOWN : headTarget;
                set(level, origin.relative(right, gx).above(gy), crafter(facing, target));
            }
        }
        final BlockPos cog = origin.below();
        set(level, cog, block("create:cogwheel").defaultBlockState().setValue(BlockStateProperties.AXIS, facing.getAxis()));
        final BlockPos motor = cog.relative(facing.getOpposite());
        set(level, motor, block("create:creative_motor").defaultBlockState().setValue(BlockStateProperties.FACING, facing));
        if (level.getBlockEntity(motor) instanceof CreativeMotorBlockEntity m) {
            m.generatedSpeed.setValue(128);
        }
    }
}
