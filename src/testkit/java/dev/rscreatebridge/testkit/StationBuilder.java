package dev.rscreatebridge.testkit;

import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity;

import dev.rscreatebridge.Registration;
import dev.rscreatebridge.assembly.AssemblyPortBlock;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Builds the Assembly Controller and a row of stations (seen from above, the controller at {@code o};
 * each station is 3 blocks wide):
 * <pre>
 *          motor  motor  motor        (y+2, z=0)   machines are at y+2, z=1
 *  [C]     depot  depot  depot        (z=1)
 *   |      port   port   port         (z=2, facing the depot)
 *   +------cable--cable--cable        (z=3)
 * </pre>
 * No belts: the controller places the piece on each port's depot and takes it back.
 */
public final class StationBuilder {
    /** A station: "deployer", "press", "spout", "mixer" or "basin_press" ("_heated" adds a blaze burner). */
    public record Stations(BlockPos controller, List<BlockPos> ports, List<BlockPos> depots, List<BlockPos> machines,
                           List<BlockPos> motors) {
    }

    private StationBuilder() {
    }

    static Block block(final String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
    }

    static Item item(final String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    }

    static void set(final ServerLevel level, final BlockPos pos, final BlockState state) {
        level.setBlock(pos, state, 3);
    }

    static void motor(final ServerLevel level, final BlockPos pos, final Direction shaftTowards) {
        set(level, pos, block("create:creative_motor").defaultBlockState().setValue(BlockStateProperties.FACING, shaftTowards));
        if (level.getBlockEntity(pos) instanceof CreativeMotorBlockEntity m) {
            m.generatedSpeed.setValue(128);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static BlockState rsDirectional(final String id, final Direction direction) {
        final BlockState state = block(id).defaultBlockState();
        final Property property = state.getBlock().getStateDefinition().getProperty("direction");
        return state.setValue(property, (Comparable) property.getValue(direction.getSerializedName()).orElseThrow());
    }

    /**
     * @param network if true, puts a test network on top of the controller (creative RS controller, item and
     *                fluid storage).
     */
    public static Stations build(final ServerLevel level, final BlockPos o, final List<String> kinds, final boolean network) {
        set(level, o, Registration.ASSEMBLY_CONTROLLER.get().defaultBlockState());
        if (network) {
            set(level, o.above(), block("refinedstorage:creative_controller").defaultBlockState());
            set(level, o.above(2), block("refinedstorage:64k_storage_block").defaultBlockState());
            set(level, o.above(3), block("refinedstorage:creative_fluid_storage_block").defaultBlockState());
        }
        final BlockState cable = block("refinedstorage:cable").defaultBlockState();
        for (int z = 1; z <= 3; z++) {
            set(level, o.offset(0, 0, z), cable);
        }
        final List<BlockPos> ports = new ArrayList<>();
        final List<BlockPos> depots = new ArrayList<>();
        final List<BlockPos> machines = new ArrayList<>();
        final List<BlockPos> motors = new ArrayList<>();
        for (int i = 0; i < kinds.size(); i++) {
            final int x = 2 + 3 * i;
            for (int cx = x - 2; cx <= x; cx++) {
                set(level, o.offset(cx, 0, 3), cable);
            }
            final String kind = kinds.get(i);
            final boolean heated = kind.endsWith("_heated");
            final boolean onBasin = kind.startsWith("mixer") || kind.startsWith("basin_press");
            // With heat, the basin goes one block higher (on top of the blaze burner) and so does the port,
            // with a cable below it joining the row.
            final BlockPos depot = o.offset(x, heated ? 1 : 0, 1);
            final BlockPos port = depot.south();
            final BlockPos machine = depot.above(2);
            if (heated) {
                set(level, depot.below(), block("create:blaze_burner").defaultBlockState()
                    .setValue(BlazeBurnerBlock.HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.KINDLED));
                if (level.getBlockEntity(depot.below()) instanceof BlazeBurnerBlockEntity burner) {
                    burner.isCreative = true;
                }
                set(level, port.below(), cable);
            }
            set(level, depot, block(onBasin ? "create:basin" : "create:depot").defaultBlockState());
            set(level, port, Registration.ASSEMBLY_PORT.get().defaultBlockState().setValue(AssemblyPortBlock.FACING, Direction.NORTH));
            switch (heated ? kind.substring(0, kind.length() - "_heated".length()) : kind) {
                case "mixer" -> {
                    // The mixer turns like a cogwheel (it has no shaft): a cogwheel next to it and the motor on top of that.
                    set(level, machine, block("create:mechanical_mixer").defaultBlockState());
                    set(level, machine.north(), block("create:cogwheel").defaultBlockState()
                        .setValue(BlockStateProperties.AXIS, Direction.Axis.Y));
                    motor(level, machine.north().above(), Direction.DOWN);
                    motors.add(machine.north().above());
                }
                case "basin_press" -> {
                    set(level, machine, block("create:mechanical_press").defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
                    motor(level, machine.north(), Direction.SOUTH);
                    motors.add(machine.north());
                }
                case "press" -> {
                    set(level, machine, block("create:mechanical_press").defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
                    motor(level, machine.north(), Direction.SOUTH);
                    motors.add(machine.north());
                }
                case "deployer" -> {
                    set(level, machine, block("create:deployer").defaultBlockState()
                        .setValue(BlockStateProperties.FACING, Direction.DOWN)
                        .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, false));
                    motor(level, machine.north(), Direction.SOUTH);
                    motors.add(machine.north());
                }
                case "spout" -> set(level, machine, block("create:spout").defaultBlockState());
                default -> throw new IllegalArgumentException(kind);
            }
            ports.add(port);
            depots.add(depot);
            machines.add(machine);
        }
        return new Stations(o, ports, depots, machines, motors);
    }
}
