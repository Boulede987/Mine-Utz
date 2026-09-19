package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Joint identity + size search on a FINE lattice (2026-09-19). Every cell is a small "atom" of
 * area; a biome is a connected cluster of same-biome cells, so a biome's SIZE is simply how many
 * cells it covers and size coupling between neighbors is exact and automatic - a bigger biome
 * literally consumes more of the shared tile, and touches more neighbors, which a fixed
 * one-cell-per-region pattern cannot express. This is the joint alternative to solving identity
 * first and fitting sizes afterwards, made possible by {@link ToroidalIdentityLocalSearch}'s
 * result that local search, unlike the complete backtracking that failed on the earlier
 * clustering model, solves this kind of pattern quickly.
 *
 * <p>A pattern is VALID when: every edge between two DIFFERENT biomes is legal (same-biome
 * adjacency is just the inside of a cluster); every cluster's cell count is inside its biome's
 * range and it is thick enough - irregular is fine, a thin snake is not ({@link ClusterSizeModel});
 * every biome appears.
 *
 * <p>Method: simulated annealing over single-cell reassignments. A cell either adopts a
 * neighbor's biome - the boundary of a cluster moves, which is how clusters grow, shrink and
 * split - or, rarely, is "nucleated" to an arbitrary biome (preferring one still missing) so a
 * new cluster can appear. The cost is tracked incrementally: a move only changes the clusters
 * touching the cell, so only those are re-measured. It starts from a random Voronoi partition,
 * which is already made of blob-shaped clusters of roughly the right size.
 */
final class ToroidalSizedLocalSearch {

    private static final int MISSING_BIOME_COST = 4;
    private static final double NUCLEATION_PROBABILITY = 0.02;
    private static final double MISSING_NUCLEATION_SHARE = 0.5;
    private static final int BEST_TRACKING_INTERVAL_STEPS = 1024;
    private static final int ACCEPTANCE_TABLE_SIZE = 64;
    private static final int SCHEDULE_LEVELS = 16;
    private static final double FOCUS_PROBABILITY = 0.3;

    static final double DEFAULT_TEMPERATURE = 0.5;

    private ToroidalSizedLocalSearch() {
    }

    /** {@code assignment()==null} means the step budget ran out; {@code closestAssignment} and {@code bestCost} then describe the lowest-cost state seen (equal to {@code assignment} when solved). */
    record Result(Map<ToroidalPos, BiomeId> assignment, Map<ToroidalPos, BiomeId> closestAssignment, long steps, int bestCost) {
    }

    /** What is wrong with a pattern, by kind - {@link #cost} is their weighted sum. */
    record Breakdown(int illegalEdges, int clusterViolation, int missingBiomes) {
        int cost() {
            return illegalEdges + clusterViolation + MISSING_BIOME_COST * missingBiomes;
        }
    }

    static Result search(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, long randomSeed, long maxSteps) {
        return search(table, grid, model, randomSeed, maxSteps, DEFAULT_TEMPERATURE);
    }

    static Result search(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, long randomSeed, long maxSteps, double temperature) {
        return search(table, grid, model, randomSeed, maxSteps, Schedule.constant(temperature));
    }

    static Result search(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, long randomSeed, long maxSteps, Schedule schedule) {
        return search(table, grid, model, randomSeed, maxSteps, schedule, () -> false);
    }

    /** {@code shouldStop} is polled every {@value #BEST_TRACKING_INTERVAL_STEPS} steps; once it returns true the search ends early, reported like an exhausted budget (not solved, closest state so far). */
    static Result search(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, long randomSeed, long maxSteps, Schedule schedule, BooleanSupplier shouldStop) {
        return new Search(table, grid, model, new Random(randomSeed), schedule, shouldStop).run(maxSteps);
    }

    /**
     * Sawtooth annealing: every {@code cycleSteps} steps the temperature restarts at {@code
     * highTemperature} and cools geometrically to {@code lowTemperature}. The hot phase lets the
     * walk escape a frozen state that a fixed low temperature is stuck in (2026-09-19: fixed
     * T=0.25 froze at 2-16 defects on 40-64 grids); the cold phase lets it settle.
     */
    record Schedule(double highTemperature, double lowTemperature, long cycleSteps) {
        Schedule {
            if (lowTemperature <= 0) {
                throw new IllegalArgumentException("ToroidalSizedLocalSearch.Schedule: lowTemperature must be positive, got " + lowTemperature);
            }
            if (highTemperature < lowTemperature) {
                throw new IllegalArgumentException(
                    "ToroidalSizedLocalSearch.Schedule: highTemperature " + highTemperature + " is below lowTemperature " + lowTemperature
                );
            }
            if (cycleSteps <= 0) {
                throw new IllegalArgumentException("ToroidalSizedLocalSearch.Schedule: cycleSteps must be positive, got " + cycleSteps);
            }
        }

        static Schedule constant(double temperature) {
            return new Schedule(temperature, temperature, 1);
        }

        double temperatureAtLevel(int level) {
            return highTemperature * Math.pow(lowTemperature / highTemperature, level / (double) (SCHEDULE_LEVELS - 1));
        }

        int levelAtStep(long step) {
            return (int) ((step % cycleSteps) * SCHEDULE_LEVELS / cycleSteps);
        }
    }

    static int cost(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, Map<ToroidalPos, BiomeId> assignment) {
        return breakdown(table, grid, model, assignment).cost();
    }

    /** Independent, map-based recomputation of everything wrong with {@code assignment} - shares no bookkeeping with the search, so it is what the search's own accounting is checked against. */
    static Breakdown breakdown(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, Map<ToroidalPos, BiomeId> assignment) {
        int illegalEdges = 0;
        for (Map.Entry<ToroidalPos, BiomeId> entry : assignment.entrySet()) {
            for (ToroidalPos neighbor : grid.neighborsOf(entry.getKey())) {
                boolean firstOfPair = entry.getKey().r() < neighbor.r() || (entry.getKey().r() == neighbor.r() && entry.getKey().q() < neighbor.q());
                BiomeId neighborBiome = assignment.get(neighbor);
                if (firstOfPair && !entry.getValue().equals(neighborBiome) && !table.isLegal(entry.getValue(), neighborBiome)) {
                    illegalEdges++;
                }
            }
        }

        int clusterViolation = 0;
        Set<ToroidalPos> visited = new HashSet<>();
        for (ToroidalPos start : assignment.keySet()) {
            if (visited.contains(start)) {
                continue;
            }
            BiomeId biome = assignment.get(start);
            Set<ToroidalPos> cluster = new HashSet<>();
            Deque<ToroidalPos> frontier = new ArrayDeque<>();
            frontier.add(start);
            visited.add(start);
            while (!frontier.isEmpty()) {
                ToroidalPos pos = frontier.poll();
                cluster.add(pos);
                for (ToroidalPos neighbor : grid.neighborsOf(pos)) {
                    if (biome.equals(assignment.get(neighbor)) && visited.add(neighbor)) {
                        frontier.add(neighbor);
                    }
                }
            }
            clusterViolation += model.violation(biome, cluster.size(), thinCellsOf(grid, model, cluster));
        }

        int missingBiomes = 0;
        for (BiomeId biome : table.allBiomeIds()) {
            if (!assignment.containsValue(biome)) {
                missingBiomes++;
            }
        }
        return new Breakdown(illegalEdges, clusterViolation, missingBiomes);
    }

    /** Independent, set-based count of a cluster's thin cells (see {@link ClusterSizeModel.Thickness}) - shares nothing with the search's array version. */
    private static int thinCellsOf(ToroidalGrid grid, ClusterSizeModel model, Set<ToroidalPos> cluster) {
        int tailReachCells = model.tailReachCells();
        switch (model.thicknessRequiredFor(cluster.size())) {
            case NONE:
                return 0;
            case TRIANGLE:
                int outsideTriangles = 0;
                for (ToroidalPos pos : cluster) {
                    List<ToroidalPos> ring = grid.neighborsOf(pos);
                    boolean inTriangle = false;
                    for (int corner = 0; corner < ring.size(); corner++) {
                        inTriangle |= cluster.contains(ring.get(corner)) && cluster.contains(ring.get((corner + 1) % ring.size()));
                    }
                    if (!inTriangle) {
                        outsideTriangles++;
                    }
                }
                return outsideTriangles;
            default:
                Map<ToroidalPos, Integer> stepsFromFlowerCenter = new HashMap<>();
                Deque<ToroidalPos> frontier = new ArrayDeque<>();
                for (ToroidalPos pos : cluster) {
                    if (cluster.containsAll(grid.neighborsOf(pos))) {
                        stepsFromFlowerCenter.put(pos, 0);
                        frontier.add(pos);
                    }
                }
                while (!frontier.isEmpty()) {
                    ToroidalPos pos = frontier.poll();
                    int steps = stepsFromFlowerCenter.get(pos);
                    if (steps < tailReachCells) {
                        for (ToroidalPos neighbor : grid.neighborsOf(pos)) {
                            if (cluster.contains(neighbor) && stepsFromFlowerCenter.putIfAbsent(neighbor, steps + 1) == null) {
                                frontier.add(neighbor);
                            }
                        }
                    }
                }
                return cluster.size() - stepsFromFlowerCenter.size();
        }
    }

    /** Throws unless {@code assignment} is a complete, valid pattern. A "solution" that fails this is a bug in the search, never a result. */
    static void verify(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, Map<ToroidalPos, BiomeId> assignment) {
        if (assignment.size() != grid.cellCount()) {
            throw new IllegalStateException("ToroidalSizedLocalSearch: assignment covers " + assignment.size() + " of " + grid.cellCount() + " cells");
        }
        Breakdown breakdown = breakdown(table, grid, model, assignment);
        if (breakdown.cost() != 0) {
            throw new IllegalStateException(
                "ToroidalSizedLocalSearch: pattern is not valid: illegal edges=" + breakdown.illegalEdges() + ", cluster violation=" + breakdown.clusterViolation()
                    + ", missing biomes=" + breakdown.missingBiomes()
            );
        }
    }

    private static final class Search {
        private final RawRuleTable table;
        private final ToroidalGrid grid;
        private final ClusterSizeModel model;
        private final Random random;
        private final List<BiomeId> biomes;
        private final List<ToroidalPos> positions;
        private final int biomeCount;
        private final int cellCount;
        private final boolean[][] legal;
        private final int[][] neighbors;
        private final Schedule schedule;
        private final BooleanSupplier shouldStop;
        private final double[][] acceptanceProbabilityByLevel = new double[SCHEDULE_LEVELS][ACCEPTANCE_TABLE_SIZE];
        private double[] acceptanceProbability;

        private final int[] value;
        private final int[] conflictedCells;
        private int conflictedCount;
        private final int[] usesOfBiome;
        private int missingBiomes;
        private int cost;

        private final int[] visitStamp;
        private final int[] queue;
        private int stamp;
        private int exploredSize;
        private int exploredThinCells;
        private final int[] reachDistance;
        private final int[] reachQueue;

        Search(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, Random random, Schedule schedule, BooleanSupplier shouldStop) {
            this.table = table;
            this.schedule = schedule;
            this.shouldStop = shouldStop;
            this.grid = grid;
            this.model = model;
            this.random = random;
            this.biomes = table.allBiomeIds();
            this.positions = grid.allPositionsInFillOrder();
            this.biomeCount = biomes.size();
            this.cellCount = positions.size();

            this.legal = new boolean[biomeCount][biomeCount];
            for (int first = 0; first < biomeCount; first++) {
                for (int second = 0; second < biomeCount; second++) {
                    legal[first][second] = table.isLegal(biomes.get(first), biomes.get(second));
                }
            }
            Map<ToroidalPos, Integer> indexOf = new LinkedHashMap<>();
            for (int index = 0; index < cellCount; index++) {
                indexOf.put(positions.get(index), index);
            }
            this.neighbors = new int[cellCount][];
            for (int index = 0; index < cellCount; index++) {
                neighbors[index] = grid.neighborsOf(positions.get(index)).stream().mapToInt(indexOf::get).toArray();
            }
            for (int level = 0; level < SCHEDULE_LEVELS; level++) {
                double temperature = schedule.temperatureAtLevel(level);
                for (int delta = 1; delta < ACCEPTANCE_TABLE_SIZE; delta++) {
                    acceptanceProbabilityByLevel[level][delta] = Math.exp(-delta / temperature);
                }
            }
            this.acceptanceProbability = acceptanceProbabilityByLevel[0];

            this.value = new int[cellCount];
            this.conflictedCells = new int[cellCount];
            this.usesOfBiome = new int[biomeCount];
            this.visitStamp = new int[cellCount];
            this.queue = new int[cellCount];
            this.reachDistance = new int[cellCount];
            this.reachQueue = new int[cellCount];
        }

        Result run(long maxSteps) {
            initializeFromRandomVoronoi();
            cost = fullCost();
            int bestCost = cost;
            int[] bestValue = value.clone();

            long stepsTaken = maxSteps;
            for (long step = 0; step < maxSteps; step++) {
                if (cost == 0) {
                    return solved(step);
                }
                proposeAndMaybeApply();
                if (step % BEST_TRACKING_INTERVAL_STEPS == 0) {
                    acceptanceProbability = acceptanceProbabilityByLevel[schedule.levelAtStep(step)];
                    collectConflictedCells();
                    if (cost < bestCost) {
                        bestCost = cost;
                        System.arraycopy(value, 0, bestValue, 0, cellCount);
                    }
                    if (shouldStop.getAsBoolean()) {
                        stepsTaken = step + 1;
                        break;
                    }
                }
            }
            if (cost == 0) {
                return solved(stepsTaken);
            }
            if (cost < bestCost) {
                bestCost = cost;
                System.arraycopy(value, 0, bestValue, 0, cellCount);
            }
            return new Result(null, toAssignment(bestValue), stepsTaken, bestCost);
        }

        private Result solved(long steps) {
            Map<ToroidalPos, BiomeId> assignment = toAssignment(value);
            verify(table, grid, model, assignment);
            return new Result(assignment, assignment, steps, 0);
        }

        /** Seeds a random Voronoi partition - blob-shaped clusters of roughly the typical size - with every biome given at least one seed. */
        private void initializeFromRandomVoronoi() {
            double meanCells = 0;
            for (int biome = 0; biome < biomeCount; biome++) {
                meanCells += (model.minCells(biome) + model.maxCells(biome)) / 2.0;
            }
            meanCells /= biomeCount;
            int seedCount = Math.min(cellCount, Math.max(biomeCount, (int) Math.round(cellCount / Math.max(1.0, meanCells))));

            int[] shuffledCells = new int[cellCount];
            for (int index = 0; index < cellCount; index++) {
                shuffledCells[index] = index;
            }
            for (int index = cellCount - 1; index > 0; index--) {
                int swapWith = random.nextInt(index + 1);
                int held = shuffledCells[index];
                shuffledCells[index] = shuffledCells[swapWith];
                shuffledCells[swapWith] = held;
            }
            int[] biomeOrder = new int[biomeCount];
            for (int index = 0; index < biomeCount; index++) {
                biomeOrder[index] = index;
            }
            for (int index = biomeCount - 1; index > 0; index--) {
                int swapWith = random.nextInt(index + 1);
                int held = biomeOrder[index];
                biomeOrder[index] = biomeOrder[swapWith];
                biomeOrder[swapWith] = held;
            }
            int[] seedBiome = new int[seedCount];
            for (int seed = 0; seed < seedCount; seed++) {
                seedBiome[seed] = seed < biomeCount ? biomeOrder[seed] : random.nextInt(biomeCount);
            }

            for (int cell = 0; cell < cellCount; cell++) {
                int nearestSeed = 0;
                int nearestDistance = Integer.MAX_VALUE;
                for (int seed = 0; seed < seedCount; seed++) {
                    int distance = torusDistance(positions.get(cell), positions.get(shuffledCells[seed]));
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearestSeed = seed;
                    }
                }
                value[cell] = seedBiome[nearestSeed];
            }
            for (int biome = 0; biome < biomeCount; biome++) {
                usesOfBiome[biome] = 0;
            }
            for (int cell = 0; cell < cellCount; cell++) {
                usesOfBiome[value[cell]]++;
            }
            missingBiomes = 0;
            for (int biome = 0; biome < biomeCount; biome++) {
                if (usesOfBiome[biome] == 0) {
                    missingBiomes++;
                }
            }
        }

        /** Hex distance on the wrapped grid: the smallest over the nine ways the torus can wrap the offset between the two cells. */
        private int torusDistance(ToroidalPos first, ToroidalPos second) {
            int deltaQ = first.q() - second.q();
            int deltaR = first.r() - second.r();
            int best = Integer.MAX_VALUE;
            for (int wrapQ = -1; wrapQ <= 1; wrapQ++) {
                for (int wrapR = -1; wrapR <= 1; wrapR++) {
                    int q = deltaQ + wrapQ * grid.width();
                    int r = deltaR + wrapR * grid.height();
                    best = Math.min(best, (Math.abs(q) + Math.abs(r) + Math.abs(q + r)) / 2);
                }
            }
            return best;
        }

        private int fullCost() {
            stamp++;
            int total = 0;
            for (int cell = 0; cell < cellCount; cell++) {
                for (int neighbor : neighbors[cell]) {
                    if (cell < neighbor && value[cell] != value[neighbor] && !legal[value[cell]][value[neighbor]]) {
                        total++;
                    }
                }
            }
            for (int cell = 0; cell < cellCount; cell++) {
                if (visitStamp[cell] != stamp) {
                    exploreCluster(cell);
                    total += model.violation(value[cell], exploredSize, exploredThinCells);
                }
            }
            return total + MISSING_BIOME_COST * missingBiomes;
        }

        /** Snapshot of the cells touching an illegal edge. It goes stale between refreshes, which only costs a wasted proposal - it is just where the walk looks, never a cost input. */
        private void collectConflictedCells() {
            conflictedCount = 0;
            for (int cell = 0; cell < cellCount; cell++) {
                for (int neighbor : neighbors[cell]) {
                    if (value[cell] != value[neighbor] && !legal[value[cell]][value[neighbor]]) {
                        conflictedCells[conflictedCount++] = cell;
                        break;
                    }
                }
            }
        }

        private void proposeAndMaybeApply() {
            int cell = conflictedCount > 0 && random.nextDouble() < FOCUS_PROBABILITY ? conflictedCells[random.nextInt(conflictedCount)] : random.nextInt(cellCount);
            int old = value[cell];
            int candidate;
            if (random.nextDouble() < NUCLEATION_PROBABILITY) {
                candidate = missingBiomes > 0 && random.nextDouble() < MISSING_NUCLEATION_SHARE ? randomMissingBiome() : random.nextInt(biomeCount);
            } else {
                int[] cellNeighbors = neighbors[cell];
                candidate = value[cellNeighbors[random.nextInt(cellNeighbors.length)]];
            }
            if (candidate == old) {
                return;
            }
            int delta = costChangeOfReassigning(cell, old, candidate);
            boolean accept = delta <= 0 || (delta < ACCEPTANCE_TABLE_SIZE && random.nextDouble() < acceptanceProbability[delta]);
            if (accept) {
                value[cell] = candidate;
                usesOfBiome[old]--;
                if (usesOfBiome[old] == 0) {
                    missingBiomes++;
                }
                usesOfBiome[candidate]++;
                if (usesOfBiome[candidate] == 1) {
                    missingBiomes--;
                }
                cost += delta;
            }
        }

        private int randomMissingBiome() {
            int pick = random.nextInt(missingBiomes);
            for (int biome = 0; biome < biomeCount; biome++) {
                if (usesOfBiome[biome] == 0 && pick-- == 0) {
                    return biome;
                }
            }
            throw new IllegalStateException("ToroidalSizedLocalSearch: missingBiomes=" + missingBiomes + " but no unused biome found");
        }

        /**
         * Exact change in total cost if {@code cell} switched from {@code old} to {@code candidate}.
         * Only what the cell touches can change: its own edges, the cluster it leaves (which may
         * split into pieces), the candidate-biome clusters beside it (which merge with it), and
         * the missing-biome count. Every other cluster keeps its size and thin cells, because a
         * neighbor of some third biome sees a different biome across that edge either way.
         */
        private int costChangeOfReassigning(int cell, int old, int candidate) {
            int[] cellNeighbors = neighbors[cell];

            int edgesBefore = 0;
            int edgesAfter = 0;
            for (int neighbor : cellNeighbors) {
                int neighborValue = value[neighbor];
                if (neighborValue != old && !legal[old][neighborValue]) {
                    edgesBefore++;
                }
                if (neighborValue != candidate && !legal[candidate][neighborValue]) {
                    edgesAfter++;
                }
            }

            stamp++;
            int clustersBefore = 0;
            exploreCluster(cell);
            clustersBefore += model.violation(old, exploredSize, exploredThinCells);
            for (int neighbor : cellNeighbors) {
                if (value[neighbor] == candidate && visitStamp[neighbor] != stamp) {
                    exploreCluster(neighbor);
                    clustersBefore += model.violation(candidate, exploredSize, exploredThinCells);
                }
            }

            value[cell] = candidate;
            stamp++;
            int clustersAfter = 0;
            exploreCluster(cell);
            clustersAfter += model.violation(candidate, exploredSize, exploredThinCells);
            for (int neighbor : cellNeighbors) {
                if (value[neighbor] == old && visitStamp[neighbor] != stamp) {
                    exploreCluster(neighbor);
                    clustersAfter += model.violation(old, exploredSize, exploredThinCells);
                }
            }
            value[cell] = old;

            int missingChange = (usesOfBiome[old] == 1 ? MISSING_BIOME_COST : 0) - (usesOfBiome[candidate] == 0 ? MISSING_BIOME_COST : 0);
            return edgesAfter - edgesBefore + clustersAfter - clustersBefore + missingChange;
        }

        /** Breadth-first over the same-biome cluster containing {@code start}, marking it visited with the current stamp; leaves its cell count and its thin-cell count (see {@link ClusterSizeModel}) in {@code exploredSize}/{@code exploredThinCells}. */
        private void exploreCluster(int start) {
            int biome = value[start];
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            visitStamp[start] = stamp;
            while (head < tail) {
                int cell = queue[head++];
                for (int neighbor : neighbors[cell]) {
                    if (value[neighbor] == biome && visitStamp[neighbor] != stamp) {
                        visitStamp[neighbor] = stamp;
                        queue[tail++] = neighbor;
                    }
                }
            }
            exploredSize = tail;
            exploredThinCells = switch (model.thicknessRequiredFor(tail)) {
                case NONE -> 0;
                case TRIANGLE -> countCellsOutsideAnyTriangle(biome, tail);
                case FLOWER -> countCellsBeyondReachOfAFlower(biome, tail);
            };
        }

        /** Of the {@code clusterSize} cells in {@code queue}, how many have no two consecutive neighbors of the same cluster - i.e. are in no triangle. Neighbors are in ring order, so consecutive ones touch each other. */
        private int countCellsOutsideAnyTriangle(int biome, int clusterSize) {
            int outside = 0;
            for (int index = 0; index < clusterSize; index++) {
                int[] ring = neighbors[queue[index]];
                boolean inTriangle = false;
                for (int corner = 0; corner < ring.length && !inTriangle; corner++) {
                    inTriangle = value[ring[corner]] == biome && value[ring[(corner + 1) % ring.length]] == biome;
                }
                if (!inTriangle) {
                    outside++;
                }
            }
            return outside;
        }

        /** Of the {@code clusterSize} cells in {@code queue}, how many are more than the tail reach of steps (through the cluster) from every flower center - a cell whose six neighbors are all in the cluster. With no flower at all, every cell counts. */
        private int countCellsBeyondReachOfAFlower(int biome, int clusterSize) {
            for (int index = 0; index < clusterSize; index++) {
                reachDistance[queue[index]] = -1;
            }
            int head = 0;
            int tail = 0;
            for (int index = 0; index < clusterSize; index++) {
                int cell = queue[index];
                boolean isFlowerCenter = true;
                for (int neighbor : neighbors[cell]) {
                    isFlowerCenter &= value[neighbor] == biome;
                }
                if (isFlowerCenter) {
                    reachDistance[cell] = 0;
                    reachQueue[tail++] = cell;
                }
            }
            int reach = model.tailReachCells();
            while (head < tail) {
                int cell = reachQueue[head++];
                if (reachDistance[cell] == reach) {
                    continue;
                }
                for (int neighbor : neighbors[cell]) {
                    if (value[neighbor] == biome && reachDistance[neighbor] == -1) {
                        reachDistance[neighbor] = reachDistance[cell] + 1;
                        reachQueue[tail++] = neighbor;
                    }
                }
            }
            return clusterSize - tail;
        }

        private Map<ToroidalPos, BiomeId> toAssignment(int[] values) {
            Map<ToroidalPos, BiomeId> assignment = new LinkedHashMap<>();
            for (int cell = 0; cell < cellCount; cell++) {
                assignment.put(positions.get(cell), biomes.get(values[cell]));
            }
            return assignment;
        }
    }
}
