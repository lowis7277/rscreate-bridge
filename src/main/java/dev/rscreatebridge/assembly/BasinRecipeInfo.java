package dev.rscreatebridge.assembly;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.support.resource.FluidResource;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.processing.recipe.HeatCondition;
import com.simibubi.create.content.processing.recipe.ProcessingOutput;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;

import dev.rscreatebridge.common.ResourceMatcher;
import dev.rscreatebridge.common.Stock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import org.jetbrains.annotations.Nullable;

/**
 * A parsed basin recipe: {@code create:mixing} (mixer) or {@code create:compacting} (press over a basin).
 * Each item ingredient is 1 unit (Create repeats the ingredient when several are needed), plus the fluids and
 * the heat it requires (blaze burner).
 */
public record BasinRecipeInfo(ResourceLocation id,
                              MachineType type,
                              List<Ingredient> items,
                              List<SizedFluidIngredient> fluids,
                              HeatCondition heat,
                              ItemStack mainOutput) implements ProcessRecipe {

    /** Is that heat enough for this recipe? */
    public boolean heatOk(final BlazeBurnerBlock.HeatLevel level) {
        return heat.testBlazeBurner(level);
    }

    public Component heatName() {
        return Component.translatable("rscreatebridge.heat_needed." + heat.getSerializedName());
    }

    // ------------------------------------------------------------------ ProcessRecipe

    @Override
    public Job.Kind kind() {
        return Job.Kind.BASIN;
    }

    @Override
    public Set<MachineType> machines() {
        return Set.of(type);
    }

    /** One job per kit: each kit is one run of the recipe, processed in one basin. */
    @Nullable
    @Override
    public Match match(final Collection<ResourceAmount> resources) {
        final List<Stock> kits = split(resources);
        return kits == null ? null : new Match(List.copyOf(kits), new Stock());
    }

    @Override
    public Operation next(final Job job) {
        return new Operation(type, null, null, heat);
    }

    @Override
    public Component productName() {
        return name();
    }

    public Component name() {
        return mainOutput.isEmpty() ? Component.literal(id.toString()) : mainOutput.getHoverName();
    }

    private List<ResourceMatcher.Requirement> requirements(final long times) {
        final List<ResourceMatcher.Requirement> reqs = new ArrayList<>();
        for (final Ingredient ingredient : items) {
            reqs.add(new ResourceMatcher.Requirement(
                key -> key instanceof ItemResource ir && ingredient.test(ir.toItemStack(1)), times));
        }
        for (final SizedFluidIngredient fluid : fluids) {
            reqs.add(new ResourceMatcher.Requirement(
                key -> key instanceof FluidResource fr && fluid.ingredient().test(Stock.toFluidStack(fr, 1)),
                (long) fluid.amount() * times));
        }
        return reqs;
    }

    /**
     * Splits what RS delivered into kits of one recipe each (one per basin), or null if it isn't exactly
     * k times this recipe's ingredients.
     */
    @Nullable
    public List<Stock> split(final Collection<ResourceAmount> resources) {
        if (resources.isEmpty() || (items.isEmpty() && fluids.isEmpty())) {
            return null;
        }
        long givenItems = 0;
        long givenFluid = 0;
        for (final ResourceAmount r : resources) {
            if (r.resource() instanceof ItemResource) {
                givenItems += r.amount();
            } else if (r.resource() instanceof FluidResource) {
                givenFluid += r.amount();
            } else {
                return null;
            }
        }
        long fluidPerRun = 0;
        for (final SizedFluidIngredient fluid : fluids) {
            fluidPerRun += fluid.amount();
        }
        final long k;
        if (!items.isEmpty()) {
            if (givenItems == 0 || givenItems % items.size() != 0) {
                return null;
            }
            k = givenItems / items.size();
        } else {
            if (givenItems != 0 || fluidPerRun == 0 || givenFluid % fluidPerRun != 0) {
                return null;
            }
            k = givenFluid / fluidPerRun;
        }
        if (k <= 0 || k > 64 || givenFluid != fluidPerRun * k) {
            return null;
        }
        final List<ResourceAmount> list = List.copyOf(resources);
        final List<ResourceMatcher.Requirement> reqs = requirements(k);
        final long[][] assignment = ResourceMatcher.matchExactly(reqs, list);
        if (assignment == null) {
            return null;
        }
        // One kit per run: each requirement takes its share (1 item, or the fluid amount) of what was assigned.
        final List<Stock> kits = new ArrayList<>();
        for (int run = 0; run < k; run++) {
            kits.add(new Stock());
        }
        for (int i = 0; i < reqs.size(); i++) {
            final long perRun = reqs.get(i).amount() / k;
            int run = 0;
            long need = perRun;
            for (int j = 0; j < list.size() && run < k; j++) {
                long available = assignment[i][j];
                while (available > 0 && run < k) {
                    final long take = Math.min(available, need);
                    kits.get(run).add(list.get(j).resource(), take);
                    available -= take;
                    need -= take;
                    if (need == 0) {
                        run++;
                        need = perRun;
                    }
                }
            }
        }
        return kits;
    }

    // ------------------------------------------------------------------ parsing

    @Nullable
    private static RecipeManager cachedManager;
    private static List<BasinRecipeInfo> cached = List.of();

    public static List<BasinRecipeInfo> all(final Level level) {
        final RecipeManager manager = level.getRecipeManager();
        if (manager == cachedManager) {
            return cached;
        }
        final List<BasinRecipeInfo> list = new ArrayList<>();
        collect(manager, AllRecipeTypes.MIXING.getType(), MachineType.MIXING, list);
        collect(manager, AllRecipeTypes.COMPACTING.getType(), MachineType.COMPACTING, list);
        list.sort(Comparator.comparing(i -> i.id().toString()));
        cachedManager = manager;
        cached = List.copyOf(list);
        return cached;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void collect(final RecipeManager manager, final RecipeType<?> type,
                                final MachineType stepType, final List<BasinRecipeInfo> out) {
        final List<RecipeHolder<?>> holders = (List) manager.getAllRecipesFor((RecipeType) type);
        for (final RecipeHolder<?> holder : holders) {
            if (!(holder.value() instanceof ProcessingRecipe<?, ?> recipe)) {
                continue;
            }
            ItemStack output = ItemStack.EMPTY;
            final List<ProcessingOutput> results = recipe.getRollableResults();
            if (!results.isEmpty()) {
                output = results.getFirst().getStack().copy();
            }
            out.add(new BasinRecipeInfo(holder.id(), stepType, List.copyOf(recipe.getIngredients()),
                List.copyOf(recipe.getFluidIngredients()), recipe.getRequiredHeat(), output));
        }
    }
}
