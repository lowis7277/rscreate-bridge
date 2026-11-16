package dev.rscreatebridge.compat.jei;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternGridContainerMenu;
import com.refinedmods.refinedstorage.common.content.Menus;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;

import dev.rscreatebridge.assembly.AssemblyRecipeInfo;

import java.util.List;
import java.util.Optional;

import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.jetbrains.annotations.Nullable;

/**
 * The JEI "+" in the RS Pattern Grid for Create sequenced assembly recipes.
 * <p>
 * JEI shows one loop of these recipes (1 cogwheel, 1 large cogwheel, 1 nugget), even though the
 * Precision Mechanism does 5. RS's generic handler copies that as is, so RS doesn't reserve the other
 * 4 loops. This handler builds the pattern with what the full recipe consumes (base item + each step's
 * input times the number of loops), so RS reserves everything and the player doesn't have to do the math.
 * <p>
 * JEI uses a category-specific handler before RS's universal one, so neither RS nor its JEI integration
 * need to be touched.
 */
final class SequencedAssemblyPatternTransfer
    implements IRecipeTransferHandler<PatternGridContainerMenu, RecipeHolder<SequencedAssemblyRecipe>> {

    @Override
    public Class<? extends PatternGridContainerMenu> getContainerClass() {
        return PatternGridContainerMenu.class;
    }

    @Override
    public Optional<MenuType<PatternGridContainerMenu>> getMenuType() {
        return Optional.of(Menus.INSTANCE.getPatternGrid());
    }

    @Override
    public RecipeType<RecipeHolder<SequencedAssemblyRecipe>> getRecipeType() {
        return RSCBJeiPlugin.SEQUENCED_ASSEMBLY;
    }

    @Nullable
    @Override
    public IRecipeTransferError transferRecipe(final PatternGridContainerMenu menu,
                                               final RecipeHolder<SequencedAssemblyRecipe> recipe,
                                               final IRecipeSlotsView slots, final Player player,
                                               final boolean maxTransfer, final boolean doTransfer) {
        if (!doTransfer) {
            return null;
        }
        final AssemblyRecipeInfo info = AssemblyRecipeInfo.byId(player.level(), recipe.id());
        if (info == null || info.mainOutput().isEmpty()) {
            return null;
        }
        menu.transferProcessingRecipe(info.fullKitForPattern(), List.of(List.of(
            new ResourceAmount(ItemResource.ofItemStack(info.mainOutput()), Math.max(1, info.mainOutput().getCount())))));
        return null;
    }

}
