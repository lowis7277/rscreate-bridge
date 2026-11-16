package dev.rscreatebridge.crafter;

import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlock;
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlockEntity;
import com.simibubi.create.content.kinetics.crafter.RecipeGridHandler;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.createmod.catnip.math.Pointing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Topology of a Mechanical Crafter array, in the same coordinate system Create uses to build
 * the grid (see {@code RecipeGridHandler.GroupedItems#mergeOnto}): the head is at (0,0); a crafter
 * pointing at another is offset according to its POINTING (LEFT: x+1, RIGHT: x-1, DOWN: y+1, UP: y-1).
 * Y grows upwards, so row 0 of a recipe is the highest Y.
 */
public final class CrafterGrid {
    public record Coord(int x, int y) {
    }

    private final MechanicalCrafterBlockEntity head;
    private final Map<Coord, MechanicalCrafterBlockEntity> byCoord;
    private final int minX;
    private final int maxX;
    private final int minY;
    private final int maxY;

    private CrafterGrid(final MechanicalCrafterBlockEntity head, final Map<Coord, MechanicalCrafterBlockEntity> byCoord) {
        this.head = head;
        this.byCoord = byCoord;
        int x0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE;
        int y0 = Integer.MAX_VALUE;
        int y1 = Integer.MIN_VALUE;
        for (final Coord c : byCoord.keySet()) {
            x0 = Math.min(x0, c.x());
            x1 = Math.max(x1, c.x());
            y0 = Math.min(y0, c.y());
            y1 = Math.max(y1, c.y());
        }
        this.minX = x0;
        this.maxX = x1;
        this.minY = y0;
        this.maxY = y1;
    }

    public sealed interface Discovery permits Found, Problem {
    }

    public record Found(CrafterGrid grid) implements Discovery {
    }

    public record Problem(String translationKey) implements Discovery {
    }

    /** Finds the crafter array touching {@code pos} (any face). */
    public static Discovery discover(final Level level, final BlockPos pos) {
        final Set<MechanicalCrafterBlockEntity> heads = new LinkedHashSet<>();
        List<MechanicalCrafterBlockEntity> chain = null;
        for (final Direction direction : Direction.values()) {
            final BlockEntity be = level.getBlockEntity(pos.relative(direction));
            if (!(be instanceof MechanicalCrafterBlockEntity crafter)) {
                continue;
            }
            final List<MechanicalCrafterBlockEntity> found = RecipeGridHandler.getAllCraftersOfChain(crafter);
            if (found == null) {
                return new Problem("rscreatebridge.crafter.problem.loop");
            }
            final MechanicalCrafterBlockEntity foundHead = findHead(found);
            if (foundHead == null) {
                return new Problem("rscreatebridge.crafter.problem.loop");
            }
            heads.add(foundHead);
            chain = found;
        }
        if (heads.isEmpty() || chain == null) {
            return new Problem("rscreatebridge.crafter.problem.no_crafters");
        }
        if (heads.size() > 1) {
            return new Problem("rscreatebridge.crafter.problem.multiple_arrays");
        }
        final MechanicalCrafterBlockEntity head = heads.iterator().next();
        final Map<MechanicalCrafterBlockEntity, Coord> coords = new IdentityHashMap<>();
        coords.put(head, new Coord(0, 0));
        final Map<Coord, MechanicalCrafterBlockEntity> byCoord = new HashMap<>();
        for (final MechanicalCrafterBlockEntity crafter : chain) {
            final Coord coord = coordOf(crafter, coords, chain.size());
            if (coord == null) {
                return new Problem("rscreatebridge.crafter.problem.loop");
            }
            if (byCoord.put(coord, crafter) != null) {
                // Two crafters in the same logical cell: Create wouldn't handle it properly either.
                return new Problem("rscreatebridge.crafter.problem.overlap");
            }
        }
        return new Found(new CrafterGrid(head, byCoord));
    }

    @Nullable
    private static MechanicalCrafterBlockEntity findHead(final List<MechanicalCrafterBlockEntity> chain) {
        MechanicalCrafterBlockEntity head = null;
        for (final MechanicalCrafterBlockEntity crafter : chain) {
            if (RecipeGridHandler.getTargetingCrafter(crafter) == null) {
                if (head != null) {
                    return null;
                }
                head = crafter;
            }
        }
        return head;
    }

    @Nullable
    private static Coord coordOf(final MechanicalCrafterBlockEntity crafter,
                                 final Map<MechanicalCrafterBlockEntity, Coord> memo,
                                 final int maxDepth) {
        final Coord known = memo.get(crafter);
        if (known != null) {
            return known;
        }
        if (maxDepth <= 0) {
            return null;
        }
        final MechanicalCrafterBlockEntity target = RecipeGridHandler.getTargetingCrafter(crafter);
        if (target == null) {
            return null;
        }
        final Coord base = coordOf(target, memo, maxDepth - 1);
        if (base == null) {
            return null;
        }
        final Pointing pointing = crafter.getBlockState().getValue(MechanicalCrafterBlock.POINTING);
        final int dx = pointing == Pointing.LEFT ? 1 : pointing == Pointing.RIGHT ? -1 : 0;
        final int dy = pointing == Pointing.DOWN ? 1 : pointing == Pointing.UP ? -1 : 0;
        final Coord coord = new Coord(base.x() + dx, base.y() + dy);
        memo.put(crafter, coord);
        return coord;
    }

    // ------------------------------------------------------------------ queries

    public MechanicalCrafterBlockEntity head() {
        return head;
    }

    public Map<Coord, MechanicalCrafterBlockEntity> crafters() {
        return byCoord;
    }

    public int size() {
        return byCoord.size();
    }

    public int minX() {
        return minX;
    }

    public int maxX() {
        return maxX;
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    public int width() {
        return maxX - minX + 1;
    }

    public int height() {
        return maxY - minY + 1;
    }

    public boolean isRotating() {
        return head.getSpeed() != 0;
    }

    public static boolean isCovered(final MechanicalCrafterBlockEntity crafter) {
        return crafter.craftingItemOrCoverPresent() && !crafter.craftingItemPresent();
    }

    /** IDLE, uncovered and empty: exactly one item can be inserted. */
    public static boolean accepts(final MechanicalCrafterBlockEntity crafter, final ItemStack stack) {
        return crafter.getInventory().insertItem(0, stack.copyWithCount(1), true).isEmpty();
    }

    /** All crafters empty and idle (covered ones are ignored). */
    public boolean isIdleAndEmpty() {
        final ItemStack probe = new ItemStack(Items.STICK);
        for (final MechanicalCrafterBlockEntity crafter : byCoord.values()) {
            if (crafter.craftingItemPresent()) {
                return false;
            }
            if (!isCovered(crafter) && !accepts(crafter, probe)) {
                return false;
            }
        }
        return true;
    }

    public boolean anyItemPresent() {
        for (final MechanicalCrafterBlockEntity crafter : byCoord.values()) {
            if (crafter.craftingItemPresent()) {
                return true;
            }
        }
        return false;
    }
}
