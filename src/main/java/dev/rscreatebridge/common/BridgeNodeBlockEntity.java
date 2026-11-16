package dev.rscreatebridge.common;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.impl.node.SimpleNetworkNode;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.support.network.AbstractNetworkNodeContainerBlockEntity;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import dev.rscreatebridge.RSCreateBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Base for the mod's blocks: a simple RS2 network node (uses energy, can insert into and extract
 * from the network), with an owner and a readable status that warns without spamming.
 */
public abstract class BridgeNodeBlockEntity extends AbstractNetworkNodeContainerBlockEntity<SimpleNetworkNode>
    implements IHaveGoggleInformation {
    public static final Actor ACTOR = () -> RSCreateBridge.MOD_ID;

    private static final String TAG_OWNER = "Owner";
    private static final String TAG_GOGGLES = "GoggleLines";

    @Nullable
    private UUID owner;
    private int activenessTicks;

    /** Current status (shown on right click). */
    private Component status = Component.translatable("rscreatebridge.status.idle");
    private boolean statusIsProblem;
    private String lastStatusKey = "";
    private final java.util.Map<String, Long> lastReported = new java.util.HashMap<>();

    protected BridgeNodeBlockEntity(final BlockEntityType<?> type,
                                    final BlockPos pos,
                                    final BlockState state,
                                    final long energyUsage) {
        super(type, pos, state, new SimpleNetworkNode(energyUsage));
    }

    // ---------------------------------------------------------------- network

    public void serverTick() {
        if (activenessTicks++ % 20 == 0) {
            final boolean active = calculateActive();
            if (active != mainNetworkNode.isActive()) {
                mainNetworkNode.setActive(active);
                onNetworkActivenessChanged(active);
            }
            syncGoggleLines();
        }
        mainNetworkNode.doWork();
    }

    // ---------------------------------------------------------------- Create Goggles

    /** What is shown on right click and with Goggles on. */
    public abstract List<Component> describe();

    /** Copy of describe() sent to the client for the Goggles overlay. */
    private List<Component> goggleLines = List.of();

    private void syncGoggleLines() {
        if (level == null || level.isClientSide()) {
            return;
        }
        final List<Component> lines = describe();
        if (!lines.equals(goggleLines)) {
            goggleLines = List.copyOf(lines);
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public boolean addToGoggleTooltip(final List<Component> tooltip, final boolean isPlayerSneaking) {
        if (goggleLines.isEmpty()) {
            return false;
        }
        boolean first = true;
        for (final Component line : goggleLines) {
            tooltip.add(Component.literal(first ? "    " : "     ").append(line));
            first = false;
        }
        return true;
    }

    @Override
    public CompoundTag getUpdateTag(final HolderLookup.Provider registries) {
        final CompoundTag tag = super.getUpdateTag(registries);
        final ListTag lines = new ListTag();
        for (final Component line : goggleLines) {
            lines.add(StringTag.valueOf(Component.Serializer.toJson(line, registries)));
        }
        tag.put(TAG_GOGGLES, lines);
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private void readGoggleLines(final CompoundTag tag, final HolderLookup.Provider registries) {
        if (!tag.contains(TAG_GOGGLES)) {
            return;
        }
        final List<Component> lines = new ArrayList<>();
        for (final Tag raw : tag.getList(TAG_GOGGLES, Tag.TAG_STRING)) {
            final Component line = Component.Serializer.fromJson(raw.getAsString(), registries);
            if (line != null) {
                lines.add(line);
            }
        }
        goggleLines = List.copyOf(lines);
    }

    private boolean calculateActive() {
        if (level == null || !level.isLoaded(worldPosition)) {
            return false;
        }
        final Network network = mainNetworkNode.getNetwork();
        if (network == null) {
            return false;
        }
        return !RefinedStorageApi.INSTANCE.isEnergyRequired()
            || network.getComponent(EnergyNetworkComponent.class).getStored() >= mainNetworkNode.getEnergyUsage();
    }

    /** Is there pending work? (to warn when the network goes down). */
    protected boolean hasWork() {
        return false;
    }

    private void onNetworkActivenessChanged(final boolean active) {
        if (!active && hasWork()) {
            // Warn when the network drops, not only once it comes back.
            setStatus(true, "rscreatebridge.status.no_network");
        } else if (active && "rscreatebridge.status.no_network".equals(lastStatusKey)) {
            RSCreateBridge.LOGGER.info("[{} @ {}] RS network is back; resuming work.", getClass().getSimpleName(),
                worldPosition.toShortString());
            clearProblem();
        }
    }

    @Nullable
    public Network getNetwork() {
        return mainNetworkNode.getNetwork();
    }

    public boolean isNetworkActive() {
        return mainNetworkNode.isActive() && mainNetworkNode.getNetwork() != null;
    }

    @Nullable
    protected StorageNetworkComponent storage() {
        if (!isNetworkActive()) {
            return null;
        }
        return Objects.requireNonNull(mainNetworkNode.getNetwork()).getComponent(StorageNetworkComponent.class);
    }

    /** Inserts into the network. Returns the inserted amount (0 without an active network). */
    public long insertIntoNetwork(final ResourceKey resource, final long amount, final Action action) {
        final StorageNetworkComponent storage = storage();
        if (storage == null || amount <= 0) {
            return 0;
        }
        return storage.insert(resource, amount, action, ACTOR);
    }

    /** Extracts from the network. Returns the extracted amount (0 without an active network). */
    public long extractFromNetwork(final ResourceKey resource, final long amount, final Action action) {
        final StorageNetworkComponent storage = storage();
        if (storage == null || amount <= 0) {
            return 0;
        }
        return storage.extract(resource, amount, action, ACTOR);
    }

    /** Amount of a resource in the network (0 without an active network). */
    public long getNetworkAmount(final ResourceKey resource) {
        final StorageNetworkComponent storage = storage();
        return storage == null ? 0 : storage.get(resource);
    }

    /** Amount of an item in the network, adding up all its component variants. */
    public long getNetworkItemCountAnyComponents(final net.minecraft.world.item.Item item) {
        final StorageNetworkComponent storage = storage();
        if (storage == null) {
            return 0;
        }
        long total = 0;
        for (final com.refinedmods.refinedstorage.api.resource.ResourceAmount r : storage.getAll()) {
            if (r.resource() instanceof ItemResource ir && ir.item() == item) {
                total += r.amount();
            }
        }
        return total;
    }

    /** Takes {@code amount} units of a resource matching the predicate from the network (all or nothing). */
    @Nullable
    public ResourceKey extractMatching(final java.util.function.Predicate<ResourceKey> filter, final long amount) {
        final StorageNetworkComponent storage = storage();
        if (storage == null || amount <= 0) {
            return null;
        }
        for (final com.refinedmods.refinedstorage.api.resource.ResourceAmount r : storage.getAll()) {
            if (r.amount() >= amount && filter.test(r.resource())
                && storage.extract(r.resource(), amount, Action.SIMULATE, ACTOR) == amount) {
                storage.extract(r.resource(), amount, Action.EXECUTE, ACTOR);
                return r.resource();
            }
        }
        return null;
    }

    /** Returns an ItemStack to the network. Whatever doesn't fit stays in the returned stack. */
    public ItemStack insertStackIntoNetwork(final ItemStack stack, final boolean simulate) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        final long inserted = insertIntoNetwork(ItemResource.ofItemStack(stack), stack.getCount(),
            simulate ? Action.SIMULATE : Action.EXECUTE);
        if (inserted >= stack.getCount()) {
            return ItemStack.EMPTY;
        }
        return stack.copyWithCount(stack.getCount() - (int) inserted);
    }

    /** Last resort: if the network refuses it, it is dropped in the world. An item is never deleted. */
    protected void returnOrDrop(final ItemStack stack) {
        final ItemStack rest = insertStackIntoNetwork(stack, false);
        if (!rest.isEmpty() && level != null) {
            RSCreateBridge.LOGGER.warn("[{}] The network refused {}x {}; dropping it in the world at {}",
                getClass().getSimpleName(), rest.getCount(), rest.getItem(), worldPosition);
            Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1.0,
                worldPosition.getZ() + 0.5, rest);
        }
    }

    // ---------------------------------------------------------------- owner and permissions

    public void setOwner(@Nullable final UUID owner) {
        this.owner = owner;
        setChanged();
    }

    @Nullable
    public UUID getOwner() {
        return owner;
    }

    public boolean canAdminister(final Player player) {
        return player.hasPermissions(2) || (owner != null && owner.equals(player.getUUID()));
    }

    // ---------------------------------------------------------------- status and warnings

    public Component getStatus() {
        return status;
    }

    public boolean isStatusProblem() {
        return statusIsProblem;
    }

    /**
     * Changes the status. It only logs when the status actually changes (compared by translation key),
     * so a stuck status doesn't spam.
     */
    protected void setStatus(final boolean problem, final String key, final Object... args) {
        this.status = Component.translatable(key, args);
        this.statusIsProblem = problem;
        if (key.equals(lastStatusKey)) {
            return;
        }
        lastStatusKey = key;
        final String plain = status.getString();
        // Besides "only on change", each key is reported at most once per minute,
        // in case two different requests make the status flip back and forth.
        final long now = level != null ? level.getGameTime() : 0;
        final Long last = lastReported.get(key);
        if (last != null && now - last < 1200) {
            return;
        }
        lastReported.put(key, now);
        // Statuses don't go to chat: they are shown with Goggles or on right click. They only go to the log.
        if (problem) {
            RSCreateBridge.LOGGER.info("[{} @ {}] {}", getClass().getSimpleName(), worldPosition.toShortString(), plain);
        } else {
            RSCreateBridge.LOGGER.debug("[{} @ {}] {}", getClass().getSimpleName(), worldPosition.toShortString(), plain);
        }
    }

    /** Changes the status without logging or warning (the caller already announced the event). */
    protected void setStatusSilently(final boolean problem, final String key, final Object... args) {
        this.status = Component.translatable(key, args);
        this.statusIsProblem = problem;
        this.lastStatusKey = key;
        if (level != null) {
            lastReported.put(key, level.getGameTime());
        }
    }

    /** Back to the "waiting" status without warning (after a manual abort). */
    protected void clearProblem() {
        this.status = Component.translatable("rscreatebridge.status.idle");
        this.statusIsProblem = false;
        this.lastStatusKey = "rscreatebridge.status.idle";
    }

    /** A one-off event that only goes to the log (INFO), for example a retried scrap. */
    protected void logEvent(final Component message) {
        RSCreateBridge.LOGGER.info("[{} @ {}] {}", getClass().getSimpleName(), worldPosition.toShortString(),
            message.getString());
    }

    /**
     * A final event that does go to chat: only for when the mod gives up on a request (out of retries).
     * Logged at INFO level.
     */
    protected void announceEvent(final Component message) {
        RSCreateBridge.LOGGER.info("[{} @ {}] {}", getClass().getSimpleName(), worldPosition.toShortString(),
            message.getString());
        announce(message);
    }

    /**
     * Sends a chat message to the owner if they are online. If the block has no owner (for example it was
     * placed by a structure or a command) or the owner is offline, it tells players within
     * {@value #NEARBY_RADIUS} blocks.
     */
    protected void announce(final Component message) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final Component line = Component.empty()
            .append(Component.translatable("rscreatebridge.prefix").withStyle(ChatFormatting.GOLD))
            .append(Component.literal(" " + worldPosition.toShortString() + ": ").withStyle(ChatFormatting.GRAY))
            .append(message.copy().withStyle(ChatFormatting.YELLOW));
        if (owner != null) {
            final ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(owner);
            if (player != null) {
                player.sendSystemMessage(line);
                return;
            }
        }
        for (final ServerPlayer player : serverLevel.players()) {
            if (player.blockPosition().distSqr(worldPosition) <= (double) NEARBY_RADIUS * NEARBY_RADIUS) {
                player.sendSystemMessage(line);
            }
        }
    }

    private static final int NEARBY_RADIUS = 32;

    public Component describeNetwork() {
        if (mainNetworkNode.getNetwork() == null) {
            return Component.translatable("rscreatebridge.network.disconnected").withStyle(ChatFormatting.RED);
        }
        if (!mainNetworkNode.isActive()) {
            return Component.translatable("rscreatebridge.network.inactive").withStyle(ChatFormatting.RED);
        }
        return Component.translatable("rscreatebridge.network.ok").withStyle(ChatFormatting.GREEN);
    }

    // ---------------------------------------------------------------- NBT

    @Override
    protected void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) {
            tag.putUUID(TAG_OWNER, owner);
        }
    }

    @Override
    protected void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID(TAG_OWNER) ? tag.getUUID(TAG_OWNER) : null;
        readGoggleLines(tag, registries);
    }

    protected static boolean isServer(@Nullable final Level level) {
        return level != null && !level.isClientSide();
    }
}
