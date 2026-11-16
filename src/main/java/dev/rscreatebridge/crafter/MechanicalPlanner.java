package dev.rscreatebridge.crafter;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.simibubi.create.content.kinetics.crafter.MechanicalCraftingRecipe;

import dev.rscreatebridge.RSCreateBridge;
import dev.rscreatebridge.common.ResourceMatcher;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Turns "a multiset of items" (what an RS processing pattern delivers) into "which item goes into
 * which crafter", by finding the create:mechanical_crafting recipe that is built exactly from those items
 * and a valid placement inside the array.
 */
public final class MechanicalPlanner {
    /** A plan ready to run: crafter -> item (1 unit). */
    public record Plan(ResourceLocation recipeId, ItemStack output, Map<MechanicalCrafterBlockEntity, ItemStack> placements) {
    }

    /** Recipe already broken down into cells, cached. */
    private record Shape(ResourceLocation id, ItemStack output, int width, int height, boolean mirrorable,
                         List<CellSpec> cells) {
    }

    private record CellSpec(int col, int row, Ingredient ingredient) {
    }

    private static final Set<String> WARNED_AMBIGUOUS = ConcurrentHashMap.newKeySet();

    @Nullable
    private static RecipeManager cachedManager;
    private static List<Shape> cachedShapes = List.of();

    private MechanicalPlanner() {
    }

    public sealed interface Outcome permits Planned, Rejected {
    }

    public record Planned(Plan plan) implements Outcome {
    }

    public record Rejected(String translationKey) implements Outcome {
    }

    public static Outcome plan(final Level level, final CrafterGrid grid, final Collection<ResourceAmount> resources) {
        final List<ResourceAmount> items = new ArrayList<>();
        long total = 0;
        for (final ResourceAmount r : resources) {
            if (!(r.resource() instanceof ItemResource)) {
                return new Rejected("rscreatebridge.crafter.problem.non_item");
            }
            items.add(r);
            total += r.amount();
        }
        if (total > grid.size()) {
            return new Rejected("rscreatebridge.crafter.problem.too_many_items");
        }
        final List<Plan> plans = new ArrayList<>();
        boolean recipeFound = false;
        for (final Shape shape : shapes(level)) {
            if (shape.cells().size() != total) {
                continue;
            }
            final List<ItemStack> assigned = assign(shape, items);
            if (assigned == null) {
                continue;
            }
            recipeFound = true;
            final Map<MechanicalCrafterBlockEntity, ItemStack> placement = place(shape, assigned, grid);
            if (placement != null) {
                plans.add(new Plan(shape.id(), shape.output(), placement));
            }
        }
        if (plans.isEmpty()) {
            return new Rejected(recipeFound
                ? "rscreatebridge.crafter.problem.does_not_fit"
                : "rscreatebridge.crafter.problem.no_recipe");
        }
        if (plans.size() > 1) {
            final Plan first = plans.getFirst();
            final boolean sameOutput = plans.stream()
                .allMatch(p -> ItemStack.isSameItemSameComponents(p.output(), first.output()));
            if (!sameOutput) {
                final String key = plans.stream().map(p -> p.recipeId().toString()).sorted().toList().toString();
                if (WARNED_AMBIGUOUS.add(key)) {
                    RSCreateBridge.LOGGER.warn(
                        "Several mechanical_crafting recipes use the same ingredients and fit the array: {}. "
                            + "Using {}. (This warning is shown only once.)", key, first.recipeId());
                }
            }
        }
        return new Planned(plans.getFirst());
    }

    /** Assigns one resource unit to each cell (max flow). Null if it isn't exact. */
    @Nullable
    private static List<ItemStack> assign(final Shape shape, final List<ResourceAmount> items) {
        final List<ResourceMatcher.Requirement> reqs = new ArrayList<>(shape.cells().size());
        for (final CellSpec cell : shape.cells()) {
            reqs.add(new ResourceMatcher.Requirement(
                key -> key instanceof ItemResource ir && cell.ingredient().test(ir.toItemStack(1)), 1));
        }
        final long[][] assignment = ResourceMatcher.matchExactly(reqs, items);
        if (assignment == null) {
            return null;
        }
        final List<ItemStack> result = new ArrayList<>(shape.cells().size());
        for (int i = 0; i < shape.cells().size(); i++) {
            ItemStack chosen = ItemStack.EMPTY;
            for (int j = 0; j < items.size(); j++) {
                if (assignment[i][j] > 0) {
                    chosen = ((ItemResource) items.get(j).resource()).toItemStack(1);
                    break;
                }
            }
            if (chosen.isEmpty()) {
                return null;
            }
            result.add(chosen);
        }
        return result;
    }

    /**
     * Looks for an offset (and mirror, if the recipe allows it) where every non-empty cell lands on a
     * crafter that accepts the item. Row 0 of the recipe goes on top (highest Y).
     */
    @Nullable
    private static Map<MechanicalCrafterBlockEntity, ItemStack> place(final Shape shape,
                                                                      final List<ItemStack> assigned,
                                                                      final CrafterGrid grid) {
        if (shape.width() > grid.width() || shape.height() > grid.height()) {
            return null;
        }
        final boolean[] mirrors = shape.mirrorable() ? new boolean[]{false, true} : new boolean[]{false};
        for (final boolean mirrored : mirrors) {
            for (int topY = grid.maxY(); topY - shape.height() + 1 >= grid.minY(); topY--) {
                for (int left = grid.minX(); left + shape.width() - 1 <= grid.maxX(); left++) {
                    final Map<MechanicalCrafterBlockEntity, ItemStack> placement = new LinkedHashMap<>();
                    boolean ok = true;
                    for (int i = 0; i < shape.cells().size() && ok; i++) {
                        final CellSpec cell = shape.cells().get(i);
                        final int col = mirrored ? shape.width() - 1 - cell.col() : cell.col();
                        final CrafterGrid.Coord coord = new CrafterGrid.Coord(left + col, topY - cell.row());
                        final MechanicalCrafterBlockEntity crafter = grid.crafters().get(coord);
                        if (crafter == null || !CrafterGrid.accepts(crafter, assigned.get(i))) {
                            ok = false;
                        } else {
                            placement.put(crafter, assigned.get(i));
                        }
                    }
                    if (ok) {
                        return placement;
                    }
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static synchronized List<Shape> shapes(final Level level) {
        final RecipeManager manager = level.getRecipeManager();
        if (manager == cachedManager) {
            return cachedShapes;
        }
        final RecipeType<MechanicalCraftingRecipe> type =
            (RecipeType<MechanicalCraftingRecipe>) (RecipeType<?>) AllRecipeTypes.MECHANICAL_CRAFTING.getType();
        final List<Shape> shapes = new ArrayList<>();
        for (final RecipeHolder<MechanicalCraftingRecipe> holder : manager.getAllRecipesFor(type)) {
            final MechanicalCraftingRecipe recipe = holder.value();
            final int width = recipe.getWidth();
            final int height = recipe.getHeight();
            final List<CellSpec> cells = new ArrayList<>();
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    final Ingredient ingredient = recipe.getIngredients().get(col + row * width);
                    if (!ingredient.isEmpty()) {
                        cells.add(new CellSpec(col, row, ingredient));
                    }
                }
            }
            if (cells.isEmpty()) {
                continue;
            }
            shapes.add(new Shape(holder.id(), recipe.getResultItem(level.registryAccess()).copy(), width, height,
                recipe.acceptsMirrored(), List.copyOf(cells)));
        }
        shapes.sort(Comparator.comparing(s -> s.id().toString()));
        cachedManager = manager;
        cachedShapes = List.copyOf(shapes);
        RSCreateBridge.LOGGER.debug("Indexed mechanical_crafting recipes: {}", shapes.size());
        return cachedShapes;
    }

    static String describe(final Plan plan) {
        return BuiltInRegistries.ITEM.getKey(plan.output().getItem()) + " (" + plan.recipeId() + ")";
    }
}
