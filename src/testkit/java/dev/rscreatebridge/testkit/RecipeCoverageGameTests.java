package dev.rscreatebridge.testkit;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;

import dev.rscreatebridge.RSCreateBridge;
import dev.rscreatebridge.assembly.AssemblyRecipeInfo;
import dev.rscreatebridge.assembly.BasinRecipeInfo;
import dev.rscreatebridge.assembly.ProcessRecipe;
import dev.rscreatebridge.assembly.Recipes;
import dev.rscreatebridge.common.Stock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Every recipe the Assembly Controller lists must be recognised from the pattern a player would make for it
 * (one of each ingredient, as JEI shows it). Catches recipe types or ingredient shapes the matcher misses.
 */
@GameTestHolder(RSCBTestKit.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RecipeCoverageGameTests {
    private RecipeCoverageGameTests() {
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void everyListedRecipeIsRecognised(final GameTestHelper h) {
        final List<String> missed = new ArrayList<>();
        final Map<String, Integer> checked = new LinkedHashMap<>();
        for (final ProcessRecipe recipe : Recipes.all(h.getLevel())) {
            final List<ResourceAmount> pattern = patternFor(recipe);
            if (pattern == null) {
                continue; // an ingredient with no items at all (empty tag): nobody can make a pattern for it
            }
            checked.merge(recipe.getClass().getSimpleName() + "/" + recipe.machines(), 1, Integer::sum);
            final boolean found = Recipes.matches(h.getLevel(), pattern).stream()
                .anyMatch(c -> c.recipe().id().equals(recipe.id()));
            if (!found) {
                missed.add(recipe.id() + " " + pattern);
            }
        }
        RSCreateBridge.LOGGER.info("Recipe coverage: {}", checked);
        h.assertTrue(missed.isEmpty(), missed.size() + " recipe(s) not recognised: " + missed);
        h.succeed();
    }

    private static List<ResourceAmount> patternFor(final ProcessRecipe recipe) {
        final Map<ResourceKey, Long> kit = new LinkedHashMap<>();
        if (recipe instanceof AssemblyRecipeInfo assembly) {
            for (final List<ResourceAmount> alternatives : assembly.fullKitForPattern()) {
                kit.merge(alternatives.getFirst().resource(), alternatives.getFirst().amount(), Long::sum);
            }
        } else if (recipe instanceof BasinRecipeInfo basin) {
            for (final Ingredient ingredient : basin.items()) {
                final ItemStack[] stacks = ingredient.getItems();
                if (stacks.length == 0) {
                    return null;
                }
                kit.merge(ItemResource.ofItemStack(stacks[0]), 1L, Long::sum);
            }
            for (final SizedFluidIngredient fluid : basin.fluids()) {
                final FluidStack[] stacks = fluid.getFluids();
                if (stacks.length == 0) {
                    return null;
                }
                kit.merge(Stock.ofFluidStack(stacks[0]), (long) fluid.amount(), Long::sum);
            }
        }
        if (kit.isEmpty()) {
            return null;
        }
        final List<ResourceAmount> list = new ArrayList<>();
        kit.forEach((key, amount) -> list.add(new ResourceAmount(key, amount)));
        return list;
    }
}
