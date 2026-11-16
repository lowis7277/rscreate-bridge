package dev.rscreatebridge.assembly;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.processing.basin.BasinBlock;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;

import dev.rscreatebridge.Config;
import dev.rscreatebridge.Registration;
import dev.rscreatebridge.common.BridgeNodeBlockEntity;
import dev.rscreatebridge.common.Stock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * Assembly Port: sits next to a Create depot or basin that has a machine 2 blocks above it
 * (deployer facing down, mechanical press, spout or mixer) and connects to the RS network with a cable.
 * <p>
 * The port makes no decisions: it is the "station" used by the Assembly Controller on the same network.
 * The controller places the piece on the depot, loads the machine (the deployer's held item, the
 * spout's lava) and takes it back when Create finishes the step. After a while without work, the port
 * returns to the network whatever it loaded into the machine itself.
 */
public class AssemblyPortBlockEntity extends BridgeNodeBlockEntity {
    private static final ResourceLocation DEPOT = ResourceLocation.fromNamespaceAndPath("create", "depot");
    private static final ResourceLocation DEPLOYER = ResourceLocation.fromNamespaceAndPath("create", "deployer");
    private static final ResourceLocation PRESS = ResourceLocation.fromNamespaceAndPath("create", "mechanical_press");
    private static final ResourceLocation SPOUT = ResourceLocation.fromNamespaceAndPath("create", "spout");
    private static final ResourceLocation BASIN = ResourceLocation.fromNamespaceAndPath("create", "basin");
    private static final ResourceLocation MIXER = ResourceLocation.fromNamespaceAndPath("create", "mechanical_mixer");

    /** Idle ticks before unloading the machine. */
    private static final int UNLOAD_AFTER_TICKS = 100;
    /** If the controller doesn't renew the claim within this time, the port becomes free. */
    private static final int CLAIM_TTL_TICKS = 40;

    /** Loaded ports per level (so the controller finds the ones on its network without scanning the world). */
    private static final Map<Level, Set<AssemblyPortBlockEntity>> LOADED = new WeakHashMap<>();

    @Nullable
    private BlockPos claimedBy;
    private long claimRefreshed;
    private long cooldownUntil;
    private long idleSince;
    /** What this port put in the deployer's hand (to return it later). */
    private ItemStack loadedItem = ItemStack.EMPTY;
    /** Did this port fill the spout? */
    private boolean loadedFluid;

    public AssemblyPortBlockEntity(final BlockPos pos, final BlockState state) {
        super(Registration.ASSEMBLY_PORT_BE.get(), pos, state, Config.ASSEMBLY_PORT_ENERGY.getAsInt());
    }

    // ================================================================== registry

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            synchronized (LOADED) {
                LOADED.computeIfAbsent(level, l -> Collections.newSetFromMap(new WeakHashMap<>())).add(this);
            }
        }
    }

    @Override
    public void setRemoved() {
        unregister();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        unregister();
        super.onChunkUnloaded();
    }

    private void unregister() {
        if (level != null && !level.isClientSide()) {
            synchronized (LOADED) {
                final Set<AssemblyPortBlockEntity> set = LOADED.get(level);
                if (set != null) {
                    set.remove(this);
                }
            }
        }
    }

    /** Loaded, active ports on the same RS network. */
    public static List<AssemblyPortBlockEntity> portsOn(final Level level, @Nullable final Network network) {
        final List<AssemblyPortBlockEntity> result = new ArrayList<>();
        if (network == null) {
            return result;
        }
        synchronized (LOADED) {
            final Set<AssemblyPortBlockEntity> set = LOADED.get(level);
            if (set == null) {
                return result;
            }
            for (final AssemblyPortBlockEntity port : set) {
                if (!port.isRemoved() && port.getNetwork() == network) {
                    result.add(port);
                }
            }
        }
        // Stable order (so dispatching is predictable).
        result.sort((a, b) -> Long.compare(a.getBlockPos().asLong(), b.getBlockPos().asLong()));
        return result;
    }

    // ================================================================== station

    public Direction facing() {
        final BlockState state = getBlockState();
        return state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
            ? state.getValue(BlockStateProperties.HORIZONTAL_FACING)
            : Direction.NORTH;
    }

    public BlockPos depotPos() {
        return worldPosition.relative(facing());
    }

    public BlockPos machinePos() {
        return depotPos().above(2);
    }

    public boolean hasDepot() {
        return level != null && DEPOT.equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(depotPos()).getBlock()));
    }

    /** Is there a basin in front (instead of a depot)? */
    public boolean hasBasin() {
        return level != null && BASIN.equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(depotPos()).getBlock()));
    }

    /** Is there a depot or a basin in front? */
    public boolean hasStation() {
        return hasDepot() || hasBasin();
    }

    @Nullable
    public BasinBlockEntity basin() {
        if (level == null || !hasBasin()) {
            return null;
        }
        return level.getBlockEntity(depotPos()) instanceof BasinBlockEntity basin ? basin : null;
    }

    /** Is the basin empty (inputs, outputs and tanks)? */
    public boolean basinEmpty() {
        final BasinBlockEntity basin = basin();
        if (basin == null) {
            return false;
        }
        for (int i = 0; i < basin.getInputInventory().getSlots(); i++) {
            if (!basin.getInputInventory().getStackInSlot(i).isEmpty()) {
                return false;
            }
        }
        for (int i = 0; i < basin.getOutputInventory().getSlots(); i++) {
            if (!basin.getOutputInventory().getStackInSlot(i).isEmpty()) {
                return false;
            }
        }
        return basin.getTanks().getFirst().isEmpty() && basin.getTanks().getSecond().isEmpty();
    }

    /** The heat under the basin (blaze burner), or NONE. */
    public BlazeBurnerBlock.HeatLevel heat() {
        if (level == null) {
            return BlazeBurnerBlock.HeatLevel.NONE;
        }
        return BasinBlockEntity.getHeatLevelOf(level.getBlockState(depotPos().below()));
    }

    /** Does the basin output to the side? (then results never reach the network) */
    private boolean basinOutputsSideways() {
        if (level == null || !hasBasin()) {
            return false;
        }
        final BlockState state = level.getBlockState(depotPos());
        return state.hasProperty(BasinBlock.FACING) && state.getValue(BasinBlock.FACING) != Direction.DOWN;
    }

    private boolean mixerOverDepot() {
        return level != null && hasDepot()
            && MIXER.equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(machinePos()).getBlock()));
    }

    /** Which step the machine above can do, or null if there is no valid machine. */
    @Nullable
    public AssemblyRecipeInfo.StepType machineType() {
        if (level == null) {
            return null;
        }
        final boolean basin = hasBasin();
        if (!basin && !hasDepot()) {
            return null;
        }
        final BlockState state = level.getBlockState(machinePos());
        final ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (basin) {
            // Over a basin: mixer (mixing) or press (compacting).
            if (MIXER.equals(id)) {
                return AssemblyRecipeInfo.StepType.MIXING;
            }
            return PRESS.equals(id) ? AssemblyRecipeInfo.StepType.COMPACTING : null;
        }
        if (DEPLOYER.equals(id)) {
            return state.hasProperty(BlockStateProperties.FACING) && state.getValue(BlockStateProperties.FACING) == Direction.DOWN
                ? AssemblyRecipeInfo.StepType.DEPLOYING : null;
        }
        if (PRESS.equals(id)) {
            return AssemblyRecipeInfo.StepType.PRESSING;
        }
        if (SPOUT.equals(id)) {
            return AssemblyRecipeInfo.StepType.FILLING;
        }
        return null;
    }

    /** Is the machine turning? (the spout needs no rotation). */
    public boolean machineRuns() {
        if (level == null) {
            return false;
        }
        final AssemblyRecipeInfo.StepType type = machineType();
        if (type == AssemblyRecipeInfo.StepType.FILLING) {
            return true;
        }
        final BlockEntity be = level.getBlockEntity(machinePos());
        return be instanceof KineticBlockEntity kinetic && kinetic.getSpeed() != 0;
    }

    @Nullable
    public IItemHandler depot() {
        if (level == null || !hasDepot()) {
            return null;
        }
        return level.getCapability(Capabilities.ItemHandler.BLOCK, depotPos(), facing().getOpposite());
    }

    /** Is the depot empty? */
    public boolean depotEmpty() {
        final IItemHandler depot = depot();
        if (depot == null) {
            return false;
        }
        for (int i = 0; i < depot.getSlots(); i++) {
            if (!depot.getStackInSlot(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    public IItemHandler deployer() {
        if (level == null || machineType() != AssemblyRecipeInfo.StepType.DEPLOYING) {
            return null;
        }
        final IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, machinePos(), null);
        if (handler != null) {
            return handler;
        }
        for (final Direction side : Direction.values()) {
            final IItemHandler sided = level.getCapability(Capabilities.ItemHandler.BLOCK, machinePos(), side);
            if (sided != null) {
                return sided;
            }
        }
        return null;
    }

    @Nullable
    public IFluidHandler spout() {
        if (level == null || machineType() != AssemblyRecipeInfo.StepType.FILLING) {
            return null;
        }
        final IFluidHandler top = level.getCapability(Capabilities.FluidHandler.BLOCK, machinePos(), Direction.UP);
        if (top != null) {
            return top;
        }
        final IFluidHandler any = level.getCapability(Capabilities.FluidHandler.BLOCK, machinePos(), null);
        if (any != null) {
            return any;
        }
        for (final Direction side : Direction.Plane.HORIZONTAL) {
            final IFluidHandler sided = level.getCapability(Capabilities.FluidHandler.BLOCK, machinePos(), side);
            if (sided != null) {
                return sided;
            }
        }
        return null;
    }

    /** The station's problem (ignoring whether it is busy), or null if it is fine. */
    @Nullable
    public Component stationProblem() {
        if (!isNetworkActive()) {
            return Component.translatable("rscreatebridge.status.no_network");
        }
        if (!hasStation()) {
            return Component.translatable("rscreatebridge.port.problem.no_depot", facing().getName());
        }
        if (machineType() == null) {
            return Component.translatable(mixerOverDepot() ? "rscreatebridge.port.problem.mixer_needs_basin"
                : hasBasin() ? "rscreatebridge.port.problem.no_basin_machine" : "rscreatebridge.port.problem.no_machine");
        }
        if (!machineRuns()) {
            return Component.translatable("rscreatebridge.port.problem.not_rotating");
        }
        if (basinOutputsSideways()) {
            return Component.translatable("rscreatebridge.port.problem.basin_sideways");
        }
        return null;
    }

    // ================================================================== claim (handled by the controller)

    public boolean isFreeFor(final BlockPos controller, final long now) {
        if (now < cooldownUntil) {
            return false;
        }
        return claimedBy == null || claimedBy.equals(controller) || now - claimRefreshed > CLAIM_TTL_TICKS;
    }

    public void claim(final BlockPos controller, final long now) {
        claimedBy = controller;
        claimRefreshed = now;
        idleSince = now;
    }

    public void release(final BlockPos controller) {
        if (controller.equals(claimedBy)) {
            claimedBy = null;
        }
    }

    /** The machine didn't process in time: the controller tries other stations for a while. */
    public void coolDown(final long until, final Component reason) {
        cooldownUntil = until;
        setPortStatus(true, reason);
    }

    public void setPortStatus(final boolean problem, final Component message) {
        if (message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc) {
            setStatus(problem, tc.getKey(), tc.getArgs());
        }
    }

    public void clearPortProblem() {
        if (isStatusProblem()) {
            clearProblem();
        }
    }

    public void noteLoadedItem(final ItemStack stack) {
        loadedItem = stack.copyWithCount(1);
        setChanged();
    }

    public void noteLoadedFluid() {
        loadedFluid = true;
        setChanged();
    }

    public boolean isLoadedItem(final ItemStack stack) {
        return !loadedItem.isEmpty() && ItemStack.isSameItemSameComponents(loadedItem, stack);
    }

    // ================================================================== tick

    @Override
    public void serverTick() {
        super.serverTick();
        if (level == null || level.getGameTime() % 20 != 0) {
            return;
        }
        final long now = level.getGameTime();
        final boolean claimed = claimedBy != null && now - claimRefreshed <= CLAIM_TTL_TICKS;
        if (claimed) {
            return;
        }
        if (claimedBy != null) {
            claimedBy = null;
            idleSince = now;
        }
        if (now - idleSince >= UNLOAD_AFTER_TICKS && isNetworkActive()) {
            unloadMachine();
        }
        if (isStatusProblem() && now >= cooldownUntil && stationProblem() == null) {
            clearProblem();
        }
    }

    /** Returns to the network what this port loaded into the machine (if it's still there). */
    private void unloadMachine() {
        if (!loadedItem.isEmpty()) {
            final IItemHandler hand = deployer();
            if (hand == null) {
                loadedItem = ItemStack.EMPTY; // the machine is gone: nothing to return
            } else {
                final ItemStack held = hand.getStackInSlot(0);
                if (held.isEmpty() || !isLoadedItem(held)) {
                    loadedItem = ItemStack.EMPTY;
                } else if (insertStackIntoNetwork(held, true).isEmpty()) {
                    final ItemStack taken = hand.extractItem(0, held.getCount(), false);
                    returnOrDrop(taken);
                    loadedItem = ItemStack.EMPTY;
                }
            }
            setChanged();
        }
        if (loadedFluid) {
            final IFluidHandler tank = spout();
            if (tank == null) {
                loadedFluid = false;
            } else {
                final FluidStack inside = tank.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.SIMULATE);
                if (inside.isEmpty()) {
                    loadedFluid = false;
                } else {
                    final long accepted = insertIntoNetwork(Stock.ofFluidStack(inside), inside.getAmount(), Action.SIMULATE);
                    if (accepted > 0) {
                        final FluidStack drained = tank.drain(inside.copyWithAmount((int) accepted), IFluidHandler.FluidAction.EXECUTE);
                        final long inserted = insertIntoNetwork(Stock.ofFluidStack(drained), drained.getAmount(), Action.EXECUTE);
                        if (inserted < drained.getAmount()) {
                            tank.fill(drained.copyWithAmount((int) (drained.getAmount() - inserted)), IFluidHandler.FluidAction.EXECUTE);
                        }
                        if (tank.drain(1, IFluidHandler.FluidAction.SIMULATE).isEmpty()) {
                            loadedFluid = false;
                        }
                    }
                }
            }
            setChanged();
        }
    }

    // ================================================================== info

    @Override
    public List<Component> describe() {
        final List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("block.rscreatebridge.assembly_port").withStyle(ChatFormatting.GOLD));
        lines.add(describeNetwork());
        final AssemblyRecipeInfo.StepType type = machineType();
        final Component problem = stationProblem();
        if (type != null) {
            lines.add(Component.translatable("rscreatebridge.port.info.machine", Component.translatable(type.machineKey()))
                .withStyle(ChatFormatting.GREEN));
            if (type.usesBasin()) {
                lines.add(Component.translatable("rscreatebridge.port.info.heat",
                    Component.translatable("rscreatebridge.heat." + heat().getSerializedName())).withStyle(ChatFormatting.GRAY));
            }
        }
        if (problem != null && isNetworkActive()) {
            lines.add(problem.copy().withStyle(ChatFormatting.RED));
        }
        final long now = level == null ? 0 : level.getGameTime();
        if (claimedBy != null && now - claimRefreshed <= CLAIM_TTL_TICKS) {
            lines.add(Component.translatable("rscreatebridge.port.info.working", claimedBy.toShortString())
                .withStyle(ChatFormatting.AQUA));
        } else {
            lines.add(Component.translatable("rscreatebridge.port.info.free").withStyle(ChatFormatting.GRAY));
        }
        if (isStatusProblem()) {
            lines.add(getStatus().copy().withStyle(ChatFormatting.YELLOW));
        }
        return lines;
    }

    // ================================================================== NBT

    @Override
    protected void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!loadedItem.isEmpty()) {
            tag.put("LoadedItem", loadedItem.save(registries));
        }
        tag.putBoolean("LoadedFluid", loadedFluid);
    }

    @Override
    protected void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        loadedItem = tag.contains("LoadedItem") ? ItemStack.parseOptional(registries, tag.getCompound("LoadedItem")) : ItemStack.EMPTY;
        loadedFluid = tag.getBoolean("LoadedFluid");
    }
}
