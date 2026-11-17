package dev.rscreatebridge.testkit;

import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlock;
import com.simibubi.create.content.processing.basin.BasinBlock;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;

import dev.rscreatebridge.Registration;
import dev.rscreatebridge.assembly.AssemblyControllerBlock;
import dev.rscreatebridge.assembly.AssemblyPortBlock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import net.createmod.catnip.math.Pointing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static dev.rscreatebridge.testkit.StationBuilder.block;
import static dev.rscreatebridge.testkit.StationBuilder.rsDirectional;

/**
 * Builds the layouts of the Ponder scenes and saves them as structures in
 * {@code src/main/resources/assets/rscreatebridge/ponder/}. Only runs with {@code -Drscb.exportPonder=true}
 * (run {@code ./gradlew runGameTestServer -PexportPonder=true}); otherwise it passes right away.
 * <p>
 * Every layout has a 5x5 base plate (layer 0, white concrete and snow like Create's own scenes). The viewer looks
 * at the scene from the south, so the blocks' fronts face south.
 */
@GameTestHolder(RSCBTestKit.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PonderStructureExport {
    private static final int SIZE = 5;
    private static final BlockPos ORIGIN = new BlockPos(1, 1, 1);

    private PonderStructureExport() {
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void exportPonderStructures(final GameTestHelper h) {
        if (!Boolean.getBoolean("rscb.exportPonder")) {
            h.succeed();
            return;
        }
        final ServerLevel level = h.getLevel();
        final BlockPos o = h.absolutePos(ORIGIN);
        export(level, o, "crafter_interface", 5, PonderStructureExport::crafterInterface);
        export(level, o, "assembly_depot", 5, PonderStructureExport::depotStation);
        export(level, o, "assembly_basin", 6, PonderStructureExport::basinStation);
        h.succeed();
    }

    private interface Layout {
        void build(ServerLevel level, BlockPos o);
    }

    private static void export(final ServerLevel level, final BlockPos o, final String name, final int height, final Layout layout) {
        for (int x = 0; x < SIZE; x++) {
            for (int z = 0; z < SIZE; z++) {
                for (int y = 0; y < height; y++) {
                    level.setBlock(o.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2 | 16);
                }
                level.setBlock(o.offset(x, 0, z), ((x + z) % 2 == 0 ? Blocks.WHITE_CONCRETE : Blocks.SNOW_BLOCK)
                    .defaultBlockState(), 2 | 16);
            }
        }
        layout.build(level, o);
        final StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, o, new Vec3i(SIZE, height, SIZE), false, null);
        final CompoundTag tag = template.save(new CompoundTag());
        final Path file = projectDir().resolve("src/main/resources/assets/rscreatebridge/ponder/" + name + ".nbt");
        try {
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(tag, file);
        } catch (final IOException e) {
            throw new IllegalStateException("Could not write " + file, e);
        }
        dev.rscreatebridge.RSCreateBridge.LOGGER.info("Ponder structure written: {}", file);
    }

    private static Path projectDir() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("build.gradle"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("build.gradle not found above " + Paths.get("").toAbsolutePath());
        }
        return dir;
    }

    private static void set(final ServerLevel level, final BlockPos pos, final BlockState state) {
        level.setBlock(pos, state, 2 | 16);
    }

    /** RS side shared by the assembly scenes: RS Controller, Autocrafter facing the Assembly Controller. */
    private static void assemblyNetwork(final ServerLevel level, final BlockPos o) {
        set(level, o.offset(0, 1, 4), withProperty(block("refinedstorage:controller").defaultBlockState(), "energy_type", "on"));
        set(level, o.offset(1, 1, 4), withProperty(rsDirectional("refinedstorage:autocrafter", Direction.EAST), "active", "true"));
        set(level, o.offset(2, 1, 4), Registration.ASSEMBLY_CONTROLLER.get().defaultBlockState()
            .setValue(AssemblyControllerBlock.FACING, Direction.SOUTH));
    }

    /**
     * Crafter Interface: a 2x2 Mechanical Crafter array whose head points at the interface; an Autocrafter faces
     * the interface and an RS Controller powers it.
     */
    private static void crafterInterface(final ServerLevel level, final BlockPos o) {
        for (int gx = 0; gx < 2; gx++) {
            for (int gy = 0; gy < 2; gy++) {
                final Direction target = gx > 0 ? Direction.WEST : gy > 0 ? Direction.DOWN : Direction.WEST;
                set(level, o.offset(2 + gx, 1 + gy, 2), crafter(Direction.SOUTH, target));
            }
        }
        // Rotation comes in through a cogwheel next to the array and a motor behind it.
        set(level, o.offset(4, 1, 2), block("create:cogwheel").defaultBlockState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        set(level, o.offset(4, 1, 1), block("create:creative_motor").defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.SOUTH));
        set(level, o.offset(1, 1, 2), Registration.CRAFTER_INTERFACE.get().defaultBlockState());
        set(level, o.offset(0, 1, 2), withProperty(rsDirectional("refinedstorage:autocrafter", Direction.EAST), "active", "true"));
        set(level, o.offset(0, 1, 3), block("refinedstorage:cable").defaultBlockState());
        set(level, o.offset(0, 1, 4), withProperty(block("refinedstorage:controller").defaultBlockState(), "energy_type", "on"));
    }

    /** Depot station: a press over a depot, the Port's head against the depot and the cable on its back. */
    private static void depotStation(final ServerLevel level, final BlockPos o) {
        assemblyNetwork(level, o);
        set(level, o.offset(2, 1, 3), block("refinedstorage:cable").defaultBlockState());
        set(level, o.offset(2, 1, 2), Registration.ASSEMBLY_PORT.get().defaultBlockState()
            .setValue(AssemblyPortBlock.FACING, Direction.NORTH));
        set(level, o.offset(2, 1, 1), block("create:depot").defaultBlockState());
        set(level, o.offset(2, 3, 1), block("create:mechanical_press").defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        set(level, o.offset(2, 3, 0), block("create:creative_motor").defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.SOUTH));
    }

    /** Basin station: a heated basin with a mixer above, the Port at the basin's height. */
    private static void basinStation(final ServerLevel level, final BlockPos o) {
        assemblyNetwork(level, o);
        set(level, o.offset(2, 1, 3), block("refinedstorage:cable").defaultBlockState());
        set(level, o.offset(2, 2, 3), block("refinedstorage:cable").defaultBlockState());
        set(level, o.offset(2, 2, 2), Registration.ASSEMBLY_PORT.get().defaultBlockState()
            .setValue(AssemblyPortBlock.FACING, Direction.NORTH));
        set(level, o.offset(2, 1, 1), block("create:blaze_burner").defaultBlockState()
            .setValue(BlazeBurnerBlock.HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.KINDLED));
        set(level, o.offset(2, 2, 1), block("create:basin").defaultBlockState()
            .setValue(BasinBlock.FACING, Direction.DOWN));
        set(level, o.offset(2, 4, 1), block("create:mechanical_mixer").defaultBlockState());
        set(level, o.offset(2, 4, 0), block("create:cogwheel").defaultBlockState()
            .setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
        set(level, o.offset(2, 5, 0), block("create:creative_motor").defaultBlockState()
            .setValue(BlockStateProperties.FACING, Direction.DOWN));
    }

    /** Lit RS blocks look better in the scene than the "no energy" look they are saved with. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState withProperty(final BlockState state, final String name, final String value) {
        final Property property = state.getBlock().getStateDefinition().getProperty(name);
        return property == null ? state : state.setValue(property, (Comparable) property.getValue(value).orElseThrow());
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
        throw new IllegalStateException("No POINTING for " + target);
    }
}
