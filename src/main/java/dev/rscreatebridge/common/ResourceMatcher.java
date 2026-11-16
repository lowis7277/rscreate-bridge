package dev.rscreatebridge.common;

import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

/**
 * Decides whether a multiset of resources covers a list of requirements exactly
 * (each requirement accepts several resources, for example a tag). Solved as a bipartite max flow:
 * requirement -> compatible resource. It matches if everything requested and everything delivered
 * is assigned (nothing missing, nothing left over).
 */
public final class ResourceMatcher {
    public record Requirement(Predicate<ResourceKey> accepts, long amount) {
    }

    private ResourceMatcher() {
    }

    /**
     * @return {@code assignment[i][j]} = amount of resource j assigned to requirement i, or null if there is no exact match.
     */
    @Nullable
    public static long[][] matchExactly(final List<Requirement> requirements, final List<ResourceAmount> resources) {
        long totalNeeded = 0;
        for (final Requirement r : requirements) {
            totalNeeded += r.amount();
        }
        long totalGiven = 0;
        for (final ResourceAmount r : resources) {
            totalGiven += r.amount();
        }
        if (totalNeeded != totalGiven || totalNeeded == 0) {
            return null;
        }
        final int reqCount = requirements.size();
        final int resCount = resources.size();
        // Nodes: 0 = source, 1..reqCount = requirements, then resources, last = sink.
        final int n = reqCount + resCount + 2;
        final int source = 0;
        final int sink = n - 1;
        final long[][] cap = new long[n][n];
        boolean anyEdge = false;
        for (int i = 0; i < reqCount; i++) {
            cap[source][1 + i] = requirements.get(i).amount();
            boolean reqHasEdge = false;
            for (int j = 0; j < resCount; j++) {
                if (requirements.get(i).accepts().test(resources.get(j).resource())) {
                    cap[1 + i][1 + reqCount + j] = Long.MAX_VALUE / 4;
                    reqHasEdge = true;
                    anyEdge = true;
                }
            }
            if (!reqHasEdge) {
                return null;
            }
        }
        if (!anyEdge) {
            return null;
        }
        for (int j = 0; j < resCount; j++) {
            cap[1 + reqCount + j][sink] = resources.get(j).amount();
        }
        final long[][] flow = new long[n][n];
        long total = 0;
        final int[] parent = new int[n];
        while (true) {
            Arrays.fill(parent, -1);
            parent[source] = source;
            final ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(source);
            while (!queue.isEmpty() && parent[sink] == -1) {
                final int u = queue.poll();
                for (int v = 0; v < n; v++) {
                    if (parent[v] == -1 && cap[u][v] - flow[u][v] > 0) {
                        parent[v] = u;
                        queue.add(v);
                    }
                }
            }
            if (parent[sink] == -1) {
                break;
            }
            long push = Long.MAX_VALUE;
            for (int v = sink; v != source; v = parent[v]) {
                push = Math.min(push, cap[parent[v]][v] - flow[parent[v]][v]);
            }
            for (int v = sink; v != source; v = parent[v]) {
                flow[parent[v]][v] += push;
                flow[v][parent[v]] -= push;
            }
            total += push;
        }
        if (total != totalNeeded) {
            return null;
        }
        final long[][] assignment = new long[reqCount][resCount];
        for (int i = 0; i < reqCount; i++) {
            for (int j = 0; j < resCount; j++) {
                assignment[i][j] = Math.max(0, flow[1 + i][1 + reqCount + j]);
            }
        }
        return assignment;
    }
}
