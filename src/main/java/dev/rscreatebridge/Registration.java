package dev.rscreatebridge;

import dev.rscreatebridge.assembly.AssemblyControllerBlock;
import dev.rscreatebridge.assembly.AssemblyControllerBlockEntity;
import dev.rscreatebridge.assembly.AssemblyPortBlock;
import dev.rscreatebridge.assembly.AssemblyPortBlockEntity;
import dev.rscreatebridge.crafter.CrafterInterfaceBlock;
import dev.rscreatebridge.crafter.CrafterInterfaceBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class Registration {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(RSCreateBridge.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RSCreateBridge.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, RSCreateBridge.MOD_ID);
    private static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, RSCreateBridge.MOD_ID);

    private static BlockBehaviour.Properties machineProperties() {
        return BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_YELLOW)
            .strength(1.9f, 6.0f)
            .sound(SoundType.METAL)
            .requiresCorrectToolForDrops();
    }

    public static final DeferredBlock<CrafterInterfaceBlock> CRAFTER_INTERFACE =
        BLOCKS.register("crafter_interface", () -> new CrafterInterfaceBlock(machineProperties()));
    public static final DeferredBlock<AssemblyControllerBlock> ASSEMBLY_CONTROLLER =
        BLOCKS.register("assembly_controller", () -> new AssemblyControllerBlock(machineProperties()));

    public static final DeferredBlock<AssemblyPortBlock> ASSEMBLY_PORT =
        BLOCKS.register("assembly_port", () -> new AssemblyPortBlock(machineProperties().noOcclusion()));

    public static final DeferredItem<BlockItem> ASSEMBLY_PORT_ITEM =
        ITEMS.registerSimpleBlockItem("assembly_port", ASSEMBLY_PORT);
    public static final DeferredItem<BlockItem> CRAFTER_INTERFACE_ITEM =
        ITEMS.registerSimpleBlockItem("crafter_interface", CRAFTER_INTERFACE);
    public static final DeferredItem<BlockItem> ASSEMBLY_CONTROLLER_ITEM =
        ITEMS.registerSimpleBlockItem("assembly_controller", ASSEMBLY_CONTROLLER);

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CrafterInterfaceBlockEntity>> CRAFTER_INTERFACE_BE =
        BLOCK_ENTITIES.register("crafter_interface",
            () -> BlockEntityType.Builder.of(CrafterInterfaceBlockEntity::new, CRAFTER_INTERFACE.get()).build(null));
    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AssemblyControllerBlockEntity>> ASSEMBLY_CONTROLLER_BE =
        BLOCK_ENTITIES.register("assembly_controller",
            () -> BlockEntityType.Builder.of(AssemblyControllerBlockEntity::new, ASSEMBLY_CONTROLLER.get()).build(null));

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AssemblyPortBlockEntity>> ASSEMBLY_PORT_BE =
        BLOCK_ENTITIES.register("assembly_port",
            () -> BlockEntityType.Builder.of(AssemblyPortBlockEntity::new, ASSEMBLY_PORT.get()).build(null));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main",
        () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.rscreatebridge"))
            .icon(() -> new ItemStack(CRAFTER_INTERFACE_ITEM.get()))
            .displayItems((params, output) -> {
                output.accept(CRAFTER_INTERFACE_ITEM.get());
                output.accept(ASSEMBLY_CONTROLLER_ITEM.get());
                output.accept(ASSEMBLY_PORT_ITEM.get());
            })
            .build());

    private Registration() {
    }

    static void register(final IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        TABS.register(modBus);
    }

    @SuppressWarnings("unused")
    private static Item.Properties itemProperties() {
        return new Item.Properties();
    }
}
