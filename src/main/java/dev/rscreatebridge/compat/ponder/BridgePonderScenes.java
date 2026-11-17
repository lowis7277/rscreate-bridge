package dev.rscreatebridge.compat.ponder;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.simibubi.create.content.kinetics.mixer.MechanicalMixerBlockEntity;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.content.kinetics.press.PressingBehaviour;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * The Ponder scenes. Positions match the structures built by the testkit's {@code PonderStructureExport}
 * (5x5 base plate, the viewer looks from the south).
 */
public final class BridgePonderScenes {
    private BridgePonderScenes() {
    }

    // ------------------------------------------------------------------ Crafter Interface

    public static void crafterInterface(final SceneBuilder builder, final SceneBuildingUtil util) {
        final CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("crafter_interface", "Running Mechanical Crafters from Refined Storage");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.idle(10);

        final BlockPos iface = util.grid().at(1, 1, 2);
        final BlockPos head = util.grid().at(2, 1, 2);
        final BlockPos autocrafter = util.grid().at(0, 1, 2);
        final BlockPos[] crafters = {head, util.grid().at(3, 1, 2), util.grid().at(2, 2, 2), util.grid().at(3, 2, 2)};
        final Selection array = util.select().fromTo(2, 1, 2, 3, 2, 2);
        final Selection kinetics = util.select().fromTo(4, 1, 1, 4, 1, 2);

        scene.world().showSection(array.add(kinetics), Direction.DOWN);
        scene.world().setKineticSpeed(array.add(kinetics), 32);
        scene.idle(15);

        scene.world().showSection(util.select().position(iface), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showText(70)
            .text("The Crafter Interface lets a Refined Storage Autocrafter use an array of Mechanical Crafters")
            .pointAt(util.vector().topOf(iface))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(80);

        scene.overlay().showText(80)
            .text("Place it against any crafter of the array. Ideally the head crafter points at it, so the result goes straight into the network")
            .pointAt(util.vector().blockSurface(head, Direction.WEST))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        scene.world().showSection(util.select().fromTo(0, 1, 2, 0, 1, 4), Direction.DOWN);
        faceAutocrafter(scene, autocrafter, Direction.EAST);
        scene.idle(10);
        scene.overlay().showText(80)
            .text("An Autocrafter facing the interface sends it the ingredients of one craft. Use a processing pattern: the JEI + button builds it")
            .pointAt(util.vector().topOf(autocrafter))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        final ItemStack[] ingredients = {AllItems.ANDESITE_ALLOY.asStack(), new ItemStack(Items.OAK_PLANKS),
            AllItems.ANDESITE_ALLOY.asStack(), new ItemStack(Items.STONE)};
        // The scene's level has no recipe for these items: tell the head what the craft makes (as Create's own
        // scenes do), otherwise the array crafts an empty result.
        scene.world().setCraftingResult(head, AllBlocks.CRUSHING_WHEEL.asStack());
        for (int i = 0; i < crafters.length; i++) {
            final ItemStack stack = ingredients[i];
            scene.world().modifyBlockEntity(crafters[i], MechanicalCrafterBlockEntity.class,
                be -> be.getInventory().insertItem(0, stack.copy(), false));
            scene.idle(5);
        }
        scene.overlay().showText(70)
            .text("The interface finds the Mechanical Crafting recipe, puts each item in its crafter and starts the array")
            .pointAt(util.vector().blockSurface(util.grid().at(3, 2, 2), Direction.SOUTH))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(80);

        // The head hands the result to the interface (here it pops out, the scene has no network).
        scene.world().modifyEntities(ItemEntity.class, ItemEntity::discard);
        scene.overlay().showControls(util.vector().topOf(iface), Pointing.DOWN, 50)
            .withItem(AllBlocks.CRUSHING_WHEEL.asStack());
        scene.overlay().showText(60)
            .text("The result goes back into the network")
            .pointAt(util.vector().topOf(iface))
            .placeNearTarget();
        scene.idle(70);

        scene.overlay().showText(70)
            .text("Right-click it, or look at it with Create Goggles, to see the array and any problem")
            .pointAt(util.vector().topOf(iface))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(80);
        scene.markAsFinished();
    }

    // ------------------------------------------------------------------ Assembly: depot station

    public static void depotStation(final SceneBuilder builder, final SceneBuildingUtil util) {
        final CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("assembly_depot", "Running depot machines from Refined Storage");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.idle(10);

        final BlockPos depot = util.grid().at(2, 1, 1);
        final BlockPos port = util.grid().at(2, 1, 2);
        final BlockPos cable = util.grid().at(2, 1, 3);
        final BlockPos controller = util.grid().at(2, 1, 4);
        final BlockPos machine = util.grid().at(2, 3, 1);
        final Selection machineAndMotor = util.select().fromTo(2, 3, 0, 2, 3, 1);
        final Selection motor = util.select().position(2, 3, 0);

        scene.world().showSection(util.select().position(depot), Direction.DOWN);
        scene.idle(5);
        scene.world().showSection(machineAndMotor, Direction.DOWN);
        scene.world().setKineticSpeed(machineAndMotor, 32);
        scene.idle(10);
        scene.overlay().showText(80)
            .text("A station is a Create depot with a machine two blocks above it: a mechanical press, a deployer facing down or a spout")
            .pointAt(util.vector().blockSurface(machine, Direction.WEST))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        scene.world().showSection(util.select().position(port), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showText(70)
            .text("Place an Assembly Port with its head against the depot")
            .pointAt(util.vector().blockSurface(port, Direction.NORTH))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(80);

        scene.world().showSection(util.select().position(cable), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showOutlineWithText(util.select().position(cable), 80)
            .text("The RS cable goes on the port's back: the port only connects to the network through that side")
            .colored(PonderPalette.GREEN)
            .pointAt(util.vector().topOf(cable))
            .placeNearTarget();
        scene.idle(90);

        scene.world().showSection(util.select().fromTo(0, 1, 4, 2, 1, 4), Direction.DOWN);
        faceAutocrafter(scene, util.grid().at(1, 1, 4), Direction.EAST);
        scene.idle(10);
        scene.overlay().showText(90)
            .text("The Assembly Controller uses every port on its network. An Autocrafter facing it hands it everything the pattern asks for")
            .pointAt(util.vector().topOf(controller))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        // A copper ingot reaches the depot, gets pressed and the sheet goes to the network.
        scene.world().createItemOnBeltLike(depot, Direction.SOUTH, new ItemStack(Items.COPPER_INGOT));
        scene.idle(10);
        scene.overlay().showText(90)
            .text("The controller takes each step to the right machine, loads what it needs (the deployer's item, the spout's fluid) and sends the result to the network")
            .pointAt(util.vector().topOf(depot))
            .placeNearTarget()
            .attachKeyFrame();
        scene.world().modifyBlockEntity(machine, MechanicalPressBlockEntity.class,
            be -> be.getPressingBehaviour().start(PressingBehaviour.Mode.BELT));
        scene.idle(20);
        scene.world().removeItemsFromBelt(depot);
        scene.world().createItemOnBeltLike(depot, Direction.UP, AllItems.COPPER_SHEET.asStack());
        scene.idle(30);
        scene.world().removeItemsFromBelt(depot);
        scene.overlay().showControls(util.vector().topOf(controller), Pointing.DOWN, 40)
            .withItem(AllItems.COPPER_SHEET.asStack());
        scene.idle(60);

        // The same station with a deployer, then a spout.
        scene.world().hideSection(machineAndMotor, Direction.UP);
        scene.idle(15);
        scene.world().setBlock(machine, AllBlocks.DEPLOYER.getDefaultState()
            .setValue(BlockStateProperties.FACING, Direction.DOWN)
            .setValue(DirectionalAxisKineticBlock.AXIS_ALONG_FIRST_COORDINATE, false), false);
        scene.world().showSection(machineAndMotor, Direction.DOWN);
        scene.world().setKineticSpeed(machineAndMotor, 32);
        scene.idle(30);
        scene.world().hideSection(machineAndMotor, Direction.UP);
        scene.idle(15);
        scene.world().setBlock(machine, AllBlocks.SPOUT.getDefaultState(), false);
        scene.world().setBlock(util.grid().at(2, 3, 0), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), false);
        scene.world().showSection(util.select().position(machine), Direction.DOWN);
        scene.overlay().showText(80)
            .text("Only the machines a recipe uses are needed. With several ports of the same machine, several pieces are processed at once")
            .pointAt(util.vector().blockSurface(machine, Direction.WEST))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        scene.overlay().showText(80)
            .text("Right-click the controller, or look at it with Create Goggles, to see the network, the ports and what is in progress")
            .pointAt(util.vector().topOf(controller))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);
        scene.markAsFinished();
    }

    // ------------------------------------------------------------------ Assembly: basin station

    public static void basinStation(final SceneBuilder builder, final SceneBuildingUtil util) {
        final CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("assembly_basin", "Running basin recipes from Refined Storage");
        scene.configureBasePlate(0, 0, 5);
        scene.showBasePlate();
        scene.idle(10);

        final BlockPos burner = util.grid().at(2, 1, 1);
        final BlockPos basin = util.grid().at(2, 2, 1);
        final BlockPos port = util.grid().at(2, 2, 2);
        final BlockPos controller = util.grid().at(2, 1, 4);
        final BlockPos mixer = util.grid().at(2, 4, 1);
        final Selection mixerKinetics = util.select().fromTo(2, 4, 0, 2, 5, 1);

        scene.world().showSection(util.select().fromTo(2, 1, 1, 2, 2, 1), Direction.DOWN);
        scene.idle(5);
        scene.world().showSection(mixerKinetics, Direction.DOWN);
        scene.world().setKineticSpeed(mixerKinetics, 64);
        scene.idle(10);
        scene.overlay().showText(80)
            .text("Ports also work with a basin, with a mechanical mixer or press two blocks above it")
            .pointAt(util.vector().blockSurface(mixer, Direction.WEST))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        scene.overlay().showText(70)
            .text("If the recipe needs heat, put a blaze burner under the basin")
            .pointAt(util.vector().blockSurface(burner, Direction.WEST))
            .placeNearTarget();
        scene.idle(80);

        scene.world().showSection(util.select().position(port), Direction.DOWN);
        scene.idle(5);
        scene.world().showSection(util.select().fromTo(2, 1, 3, 2, 2, 3), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showText(80)
            .text("The port goes at the basin's height: head against the basin, RS cable on its back")
            .pointAt(util.vector().blockSurface(port, Direction.NORTH))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        scene.world().showSection(util.select().fromTo(0, 1, 4, 2, 1, 4), Direction.DOWN);
        faceAutocrafter(scene, util.grid().at(1, 1, 4), Direction.EAST);
        scene.idle(15);

        // Copper and zinc go into the basin, the mixer turns them into brass.
        scene.world().modifyBlockEntity(basin, BasinBlockEntity.class, be -> {
            be.getInputInventory().setStackInSlot(0, new ItemStack(Items.COPPER_INGOT));
            be.getInputInventory().setStackInSlot(1, AllItems.ZINC_INGOT.asStack());
        });
        scene.overlay().showText(90)
            .text("The controller puts the whole recipe in the basin, waits for the machine and sends the result, items or fluid, to the network")
            .pointAt(util.vector().topOf(basin))
            .placeNearTarget()
            .attachKeyFrame();
        scene.world().modifyBlockEntity(mixer, MechanicalMixerBlockEntity.class, MechanicalMixerBlockEntity::startProcessingBasin);
        scene.idle(50);
        scene.world().modifyBlockEntity(basin, BasinBlockEntity.class, be -> {
            be.getInputInventory().setStackInSlot(0, ItemStack.EMPTY);
            be.getInputInventory().setStackInSlot(1, ItemStack.EMPTY);
            be.getOutputInventory().setStackInSlot(0, AllItems.BRASS_INGOT.asStack(2));
        });
        scene.idle(30);
        scene.world().modifyBlockEntity(basin, BasinBlockEntity.class,
            be -> be.getOutputInventory().setStackInSlot(0, ItemStack.EMPTY));
        scene.overlay().showControls(util.vector().topOf(controller), Pointing.DOWN, 40)
            .withItem(AllItems.BRASS_INGOT.asStack());
        scene.idle(60);

        scene.overlay().showOutlineWithText(util.select().position(basin), 90)
            .text("The basin must not output to the side: if it does, click it with a Create wrench until it has no output")
            .colored(PonderPalette.RED)
            .pointAt(util.vector().blockSurface(basin, Direction.WEST))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);
        scene.markAsFinished();
    }

    /** Makes sure the Autocrafter faces the block it feeds (its state can come out of the structure reset). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void faceAutocrafter(final CreateSceneBuilder scene, final BlockPos pos, final Direction facing) {
        scene.world().modifyBlock(pos, state -> {
            final Property property = state.getBlock().getStateDefinition().getProperty("direction");
            if (property == null) {
                return state;
            }
            return (BlockState) property.getValue(facing.getSerializedName())
                .map(value -> state.setValue(property, (Comparable) value))
                .orElse(state);
        }, false);
    }
}
