package dev.rscreatebridge.common;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Internal inventory without amount limits, keyed by RS resource (item + components, or fluid).
 * Saved to NBT with long amounts (1.21 ItemStacks don't serialize more than 99).
 */
public final class Stock {
    private final Map<ItemResource, Long> items = new LinkedHashMap<>();
    private final Map<FluidResource, Long> fluids = new LinkedHashMap<>();

    public boolean isEmpty() {
        return items.isEmpty() && fluids.isEmpty();
    }

    public void add(final ResourceKey resource, final long amount) {
        if (amount <= 0) {
            return;
        }
        if (resource instanceof ItemResource item) {
            items.merge(item, amount, Long::sum);
        } else if (resource instanceof FluidResource fluid) {
            fluids.merge(fluid, amount, Long::sum);
        } else {
            throw new IllegalArgumentException("Unsupported resource: " + resource);
        }
    }

    public void addAll(final Collection<ResourceAmount> resources) {
        resources.forEach(r -> add(r.resource(), r.amount()));
    }

    public void addItem(final ItemStack stack) {
        if (!stack.isEmpty()) {
            add(ItemResource.ofItemStack(stack), stack.getCount());
        }
    }

    public void addFluid(final FluidStack stack) {
        if (!stack.isEmpty()) {
            add(new FluidResource(stack.getFluid(), stack.getComponentsPatch()), stack.getAmount());
        }
    }

    public long get(final ResourceKey resource) {
        if (resource instanceof ItemResource item) {
            return items.getOrDefault(item, 0L);
        }
        if (resource instanceof FluidResource fluid) {
            return fluids.getOrDefault(fluid, 0L);
        }
        return 0;
    }

    /** Takes up to {@code amount}. Returns what was actually taken. */
    public long remove(final ResourceKey resource, final long amount) {
        final long present = get(resource);
        final long removed = Math.min(present, amount);
        if (removed <= 0) {
            return 0;
        }
        if (resource instanceof ItemResource item) {
            if (present == removed) {
                items.remove(item);
            } else {
                items.put(item, present - removed);
            }
        } else if (resource instanceof FluidResource fluid) {
            if (present == removed) {
                fluids.remove(fluid);
            } else {
                fluids.put(fluid, present - removed);
            }
        }
        return removed;
    }

    /** Takes an item matching the predicate (1 unit by default). Returns EMPTY if there is none. */
    public ItemStack takeItem(final Predicate<ItemStack> filter, final int count) {
        for (final Map.Entry<ItemResource, Long> entry : items.entrySet()) {
            final ItemStack probe = entry.getKey().toItemStack(1);
            if (entry.getValue() >= count && filter.test(probe)) {
                remove(entry.getKey(), count);
                return entry.getKey().toItemStack(count);
            }
        }
        return ItemStack.EMPTY;
    }

    public boolean hasItem(final Predicate<ItemStack> filter, final int count) {
        for (final Map.Entry<ItemResource, Long> entry : items.entrySet()) {
            if (entry.getValue() >= count && filter.test(entry.getKey().toItemStack(1))) {
                return true;
            }
        }
        return false;
    }

    /** Takes exactly {@code amount} mB of a fluid matching the predicate. EMPTY if there isn't enough. */
    public FluidStack takeFluid(final Predicate<FluidStack> filter, final int amount) {
        for (final Map.Entry<FluidResource, Long> entry : fluids.entrySet()) {
            final FluidStack probe = toFluidStack(entry.getKey(), amount);
            if (entry.getValue() >= amount && filter.test(probe)) {
                remove(entry.getKey(), amount);
                return probe;
            }
        }
        return FluidStack.EMPTY;
    }

    public boolean hasFluid(final Predicate<FluidStack> filter, final int amount) {
        for (final Map.Entry<FluidResource, Long> entry : fluids.entrySet()) {
            if (entry.getValue() >= amount && filter.test(toFluidStack(entry.getKey(), amount))) {
                return true;
            }
        }
        return false;
    }

    public List<ResourceAmount> asList() {
        final List<ResourceAmount> list = new ArrayList<>();
        items.forEach((k, v) -> list.add(new ResourceAmount(k, v)));
        fluids.forEach((k, v) -> list.add(new ResourceAmount(k, v)));
        return list;
    }

    public void clear() {
        items.clear();
        fluids.clear();
    }

    public static FluidStack toFluidStack(final FluidResource resource, final int amount) {
        return new FluidStack(BuiltInRegistries.FLUID.wrapAsHolder(resource.fluid()), amount, resource.components());
    }

    public static FluidResource ofFluidStack(final FluidStack stack) {
        return new FluidResource(stack.getFluid(), stack.getComponentsPatch());
    }

    public ListTag save(final HolderLookup.Provider registries) {
        final ListTag list = new ListTag();
        items.forEach((item, amount) -> {
            final CompoundTag entry = new CompoundTag();
            entry.put("item", item.toItemStack(1).save(registries));
            entry.putLong("n", amount);
            list.add(entry);
        });
        fluids.forEach((fluid, amount) -> {
            final CompoundTag entry = new CompoundTag();
            entry.put("fluid", toFluidStack(fluid, 1).save(registries));
            entry.putLong("n", amount);
            list.add(entry);
        });
        return list;
    }

    public void load(final ListTag list, final HolderLookup.Provider registries) {
        clear();
        for (final Tag raw : list) {
            final CompoundTag entry = (CompoundTag) raw;
            final long amount = entry.getLong("n");
            if (entry.contains("item")) {
                final ItemStack stack = ItemStack.parseOptional(registries, entry.getCompound("item"));
                if (!stack.isEmpty()) {
                    add(ItemResource.ofItemStack(stack), amount);
                }
            } else if (entry.contains("fluid")) {
                final FluidStack stack = FluidStack.parseOptional(registries, entry.getCompound("fluid"));
                if (!stack.isEmpty()) {
                    add(ofFluidStack(stack), amount);
                }
            }
        }
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        items.forEach((k, v) -> sb.append(v).append("x ").append(BuiltInRegistries.ITEM.getKey(k.item())).append(", "));
        fluids.forEach((k, v) -> sb.append(v).append("mB ").append(fluidId(k.fluid())).append(", "));
        return sb.isEmpty() ? "(empty)" : sb.substring(0, sb.length() - 2);
    }

    private static String fluidId(final Fluid fluid) {
        return fluid == Fluids.EMPTY ? "empty" : BuiltInRegistries.FLUID.getKey(fluid).toString();
    }
}
