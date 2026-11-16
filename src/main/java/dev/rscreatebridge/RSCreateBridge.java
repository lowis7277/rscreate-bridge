package dev.rscreatebridge;

import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.neoforge.api.RefinedStorageNeoForgeApi;

import dev.rscreatebridge.sink.BridgeSinkFactory;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(RSCreateBridge.MOD_ID)
public final class RSCreateBridge {
    public static final String MOD_ID = "rscreatebridge";
    public static final Logger LOGGER = LoggerFactory.getLogger("RSCreateBridge");

    public RSCreateBridge(final IEventBus modBus, final ModContainer container) {
        Registration.register(modBus);
        container.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        modBus.addListener(this::commonSetup);
        modBus.addListener(this::registerCapabilities);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            RefinedStorageApi.INSTANCE.addPatternProviderExternalPatternSinkFactory(new BridgeSinkFactory());
            LOGGER.info("Registered the external pattern sink in Refined Storage (Crafter Interface + Assembly Controller).");
        });
    }

    private void registerCapabilities(final RegisterCapabilitiesEvent event) {
        // RS2 network node (so cables connect).
        event.registerBlockEntity(RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            Registration.CRAFTER_INTERFACE_BE.get(), (be, side) -> be.getContainerProvider());
        event.registerBlockEntity(RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            Registration.ASSEMBLY_CONTROLLER_BE.get(), (be, side) -> be.getContainerProvider());
        event.registerBlockEntity(RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability(),
            Registration.ASSEMBLY_PORT_BE.get(), (be, side) -> be.getContainerProvider());
        // Crafter output: only towards faces touching a Mechanical Crafter.
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, Registration.CRAFTER_INTERFACE_BE.get(),
            (be, side) -> be.getOutputHandler(side));
        // The Controller and the Ports expose no inventory: everything goes through the RS network
        // (so RS's generic sink can't insert on its own: no double insertion).
    }
}
