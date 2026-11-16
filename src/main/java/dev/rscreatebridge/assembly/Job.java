package dev.rscreatebridge.assembly;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;

import dev.rscreatebridge.common.Stock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * One product to make. The same job type covers the three kinds of work:
 * <ul>
 *   <li>{@link Kind#SEQUENCED}: a piece that goes through several depot stations, one step at a time;</li>
 *   <li>{@link Kind#SINGLE}: one depot step (for example ingot -> sheet with the press);</li>
 *   <li>{@link Kind#BASIN}: one kit of ingredients processed in a basin (mixer or press).</li>
 * </ul>
 * {@code start} is what the job starts from (base item, or the basin kit) and is used again after a failure.
 * {@code holding} is what the controller currently holds for it; while the job is at a station it holds
 * nothing and {@code placed} remembers what was put there.
 */
final class Job {
    enum Kind { SEQUENCED, SINGLE, BASIN }

    enum State {
        /** Waiting for a free station of the right type. */
        WAITING_STATION,
        /** Its inputs are at a station. */
        AT_STATION,
        /** It has to start over: the {@code start} resources have to be taken again. */
        WAITING_RESTART
    }

    final Kind kind;
    final ResourceLocation recipe;
    final Stock start = new Stock();
    final Stock holding = new Stock();
    final Stock placed = new Stock();
    State state = State.WAITING_STATION;
    @Nullable
    BlockPos port;
    long sentAt;
    long missingSince = -1;
    long nextTryAt;
    int attempts;

    Job(final Kind kind, final ResourceLocation recipe, final Stock start) {
        this.kind = kind;
        this.recipe = recipe;
        this.start.addAll(start.asList());
        this.holding.addAll(start.asList());
    }

    boolean usesBasin() {
        return kind == Kind.BASIN;
    }

    /** For depot jobs: the single item it holds (the piece), or EMPTY. */
    ItemStack piece() {
        return firstItem(holding);
    }

    /** For depot jobs: the item that was placed on the depot, or EMPTY. */
    ItemStack placedPiece() {
        return firstItem(placed);
    }

    /** For depot jobs: the base item it starts from. */
    ItemStack base() {
        return firstItem(start);
    }

    private static ItemStack firstItem(final Stock stock) {
        for (final ResourceAmount r : stock.asList()) {
            if (r.resource() instanceof ItemResource item) {
                return item.toItemStack(1);
            }
        }
        return ItemStack.EMPTY;
    }

    void setPiece(final ItemStack piece) {
        holding.clear();
        holding.addItem(piece.copyWithCount(1));
    }

    // ------------------------------------------------------------------ NBT

    CompoundTag save(final HolderLookup.Provider registries) {
        final CompoundTag tag = new CompoundTag();
        tag.putString("Kind", kind.name());
        tag.putString("Recipe", recipe.toString());
        tag.put("Start", start.save(registries));
        tag.put("Holding", holding.save(registries));
        tag.put("Placed", placed.save(registries));
        tag.putString("State", state.name());
        if (port != null) {
            tag.put("Port", NbtUtils.writeBlockPos(port));
        }
        tag.putLong("SentAt", sentAt);
        tag.putLong("NextTryAt", nextTryAt);
        tag.putInt("Attempts", attempts);
        return tag;
    }

    @Nullable
    static Job load(final CompoundTag tag, final HolderLookup.Provider registries) {
        final ResourceLocation recipe = ResourceLocation.tryParse(tag.getString("Recipe"));
        final Kind kind;
        try {
            kind = Kind.valueOf(tag.getString("Kind"));
        } catch (final IllegalArgumentException e) {
            return null;
        }
        if (recipe == null) {
            return null;
        }
        final Stock start = new Stock();
        start.load(tag.getList("Start", Tag.TAG_COMPOUND), registries);
        final Job job = new Job(kind, recipe, start);
        job.holding.load(tag.getList("Holding", Tag.TAG_COMPOUND), registries);
        job.placed.load(tag.getList("Placed", Tag.TAG_COMPOUND), registries);
        try {
            job.state = State.valueOf(tag.getString("State"));
        } catch (final IllegalArgumentException e) {
            job.state = State.WAITING_RESTART;
        }
        job.port = NbtUtils.readBlockPos(tag, "Port").orElse(null);
        if (job.state == State.AT_STATION && job.port == null) {
            job.state = State.WAITING_RESTART;
        }
        job.sentAt = tag.getLong("SentAt");
        job.nextTryAt = tag.getLong("NextTryAt");
        job.attempts = tag.getInt("Attempts");
        return job;
    }
}
