package com.mineutze.worldgen.plan;

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
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * Joint identity + size search on a FINE lattice (2026-09-19). Every cell is a small "atom" of
 * area; a biome is a connected cluster of same-biome cells, so a biome's SIZE is simply how many
 * cells it covers and size coupling between neighbors is exact and automatic - a bigger biome
 * literally consumes more of the shared tile, and touches more neighbors, which a fixed
 * one-cell-per-region pattern cannot express. This is the joint alternative to solving identity
 * first and fitting sizes afterwards. Local search, unlike the complete backtracking that
 * failed on the earlier clustering model, solves this kind of pattern quickly.
 *
 * <p>A pattern is VALID when: every edge between two DIFFERENT biomes is legal (same-biome
 * adjacency is just the inside of a cluster); every cluster's cell count is inside its biome's
 * range and its shape passes the {@link ShapeRule} - irregular is fine, a thin snake is not;
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

    static final int MISSING_BIOME_COST = 4;
    static final double NUCLEATION_PROBABILITY = 0.02;
    static final double MISSING_NUCLEATION_SHARE = 0.5;
    static final int BEST_TRACKING_INTERVAL_STEPS = 1024;
    static final int ACCEPTANCE_TABLE_SIZE = 64;
    static final int SCHEDULE_LEVELS = 16;
    static final double FOCUS_PROBABILITY = 0.3;
    static final double DEFAULT_RELABEL_PROBABILITY = 0.02;

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
        return search(table, grid, model, randomSeed, maxSteps, schedule, shouldStop, DEFAULT_RELABEL_PROBABILITY);
    }

    /**
     * {@code relabelProbability} is the share of steps spent on a whole-cluster relabel instead of a
     * single-cell move. Single cells cannot fix a cluster that touches a biome it may not touch
     * (2026-09-19: every stuck pattern was ocean-family beside a wrong-climate land biome): to
     * relabel a cluster one cell at a time the walk passes through half-and-half states with size
     * violations far too costly to accept. A relabel changes the whole cluster in one move.
     */
    static Result search(
        RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, long randomSeed, long maxSteps, Schedule schedule, BooleanSupplier shouldStop, double relabelProbability
    ) {
        if (relabelProbability < 0.0 || relabelProbability > 1.0) {
            throw new IllegalArgumentException("ToroidalSizedLocalSearch: relabelProbability must be within 0..1, got " + relabelProbability);
        }
        return new Search(table, grid, model, new Random(randomSeed), schedule, shouldStop, relabelProbability).run(maxSteps);
    }

    /**
     * As {@link #search(RawRuleTable, ToroidalGrid, ClusterSizeModel, long, long, Schedule, BooleanSupplier, double)}, but starting from
     * {@code initialAssignment} (which must cover every cell with a known biome) instead of a random Voronoi partition - for a search that
     * already has a good starting point (a coarser-resolution solve upsampled to this grid, say) and only needs to refine it.
     */
    static Result searchFromSeed(
        RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, long randomSeed, Map<ToroidalPos, BiomeId> initialAssignment, long maxSteps, Schedule schedule,
        BooleanSupplier shouldStop, double relabelProbability
    ) {
        if (relabelProbability < 0.0 || relabelProbability > 1.0) {
            throw new IllegalArgumentException("ToroidalSizedLocalSearch: relabelProbability must be within 0..1, got " + relabelProbability);
        }
        return new Search(table, grid, model, new Random(randomSeed), schedule, shouldStop, relabelProbability).run(maxSteps, initialAssignment);
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
            return highTemperature * StrictMath.pow(lowTemperature / highTemperature, level / (double) (SCHEDULE_LEVELS - 1));
        }

        int levelAtStep(long step) {
            return (int) ((step % cycleSteps) * SCHEDULE_LEVELS / cycleSteps);
        }
    }

    static int cost(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, Map<ToroidalPos, BiomeId> assignment) {
        return breakdown(table, grid, model, assignment).cost();
    }

    /** Which biome pairs the illegal edges of {@code assignment} join, and how many edges each - "a | b" with the names in sorted order. Says WHAT a stuck pattern is stuck on. */
    static Map<String, Integer> illegalPairCounts(RawRuleTable table, ToroidalGrid grid, Map<ToroidalPos, BiomeId> assignment) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Map.Entry<ToroidalPos, BiomeId> entry : assignment.entrySet()) {
            tallyIllegalPairsFromCell(table, grid, assignment, counts, entry);
        }
        return counts;
    }

    private static void tallyIllegalPairsFromCell(
        RawRuleTable table, ToroidalGrid grid, Map<ToroidalPos, BiomeId> assignment, Map<String, Integer> counts, Map.Entry<ToroidalPos, BiomeId> entry
    ) {
        for (ToroidalPos neighbor : grid.neighborsOf(entry.getKey())) {
            boolean firstOfPair = entry.getKey().r() < neighbor.r() || (entry.getKey().r() == neighbor.r() && entry.getKey().q() < neighbor.q());
            BiomeId neighborBiome = assignment.get(neighbor);
            if (firstOfPair && !entry.getValue().equals(neighborBiome) && !table.isLegal(entry.getValue(), neighborBiome)) {
                BiomeId lower = entry.getValue().compareTo(neighborBiome) <= 0 ? entry.getValue() : neighborBiome;
                BiomeId higher = lower == entry.getValue() ? neighborBiome : entry.getValue();
                counts.merge(lower + " | " + higher, 1, Integer::sum);
            }
        }
    }

    /** Independent, map-based recomputation of everything wrong with {@code assignment} - shares no bookkeeping with the search, so it is what the search's own accounting is checked against. */
    static Breakdown breakdown(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, Map<ToroidalPos, BiomeId> assignment) {
        int illegalEdges = countIllegalEdges(table, grid, assignment);
        int clusterViolation = sumClusterViolations(table, grid, model, assignment);
        int missingBiomes = countMissingBiomes(table, assignment);
        return new Breakdown(illegalEdges, clusterViolation, missingBiomes);
    }

    /** Same traversal as {@link #illegalPairCounts}, just summed instead of tallied per pair - reuses it rather than repeating the walk. */
    private static int countIllegalEdges(RawRuleTable table, ToroidalGrid grid, Map<ToroidalPos, BiomeId> assignment) {
        return illegalPairCounts(table, grid, assignment).values().stream().mapToInt(Integer::intValue).sum();
    }

    /** One same-biome cluster, breadth-first from {@code start}: its own cells, its perimeter, and how many perimeter edges go to each other biome. */
    private record ExploredCluster(BiomeId biome, Set<ToroidalPos> cells, int perimeter, Map<BiomeId, Integer> edgesToBiome) {
    }

    private static ExploredCluster exploreCluster(ToroidalGrid grid, Map<ToroidalPos, BiomeId> assignment, ToroidalPos start, Set<ToroidalPos> visited) {
        BiomeId biome = assignment.get(start);
        Set<ToroidalPos> cluster = new HashSet<>();
        int perimeter = 0;
        Map<BiomeId, Integer> edgesToBiome = new HashMap<>();
        Deque<ToroidalPos> frontier = new ArrayDeque<>();
        frontier.add(start);
        visited.add(start);
        while (!frontier.isEmpty()) {
            ToroidalPos pos = frontier.poll();
            cluster.add(pos);
            for (ToroidalPos neighbor : grid.neighborsOf(pos)) {
                if (!biome.equals(assignment.get(neighbor))) {
                    perimeter++;
                    edgesToBiome.merge(assignment.get(neighbor), 1, Integer::sum);
                } else if (visited.add(neighbor)) {
                    frontier.add(neighbor);
                }
            }
        }
        return new ExploredCluster(biome, cluster, perimeter, edgesToBiome);
    }

    private static int sumClusterViolations(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, Map<ToroidalPos, BiomeId> assignment) {
        int clusterViolation = 0;
        Set<ToroidalPos> visited = new HashSet<>();
        for (ToroidalPos start : assignment.keySet()) {
            if (visited.contains(start)) {
                continue;
            }
            ExploredCluster cluster = exploreCluster(grid, assignment, start, visited);
            int thinCells = thinCellsOf(grid, model, cluster.biome(), cluster.cells());
            int borderShortfall = borderShortfallOf(table, cluster.biome(), cluster.edgesToBiome());
            clusterViolation += model.violation(cluster.biome(), cluster.cells().size(), thinCells, cluster.perimeter(), borderShortfall);
        }
        return clusterViolation;
    }

    private static int countMissingBiomes(RawRuleTable table, Map<ToroidalPos, BiomeId> assignment) {
        int missingBiomes = 0;
        for (BiomeId biome : table.allBiomeIds()) {
            if (!assignment.containsValue(biome)) {
                missingBiomes++;
            }
        }
        return missingBiomes;
    }

    /** Independent count of how many shared edges a cluster of {@code biome} is short of its required borders, given how many edges it shares with each other biome: the plain reference reading of the requirement. */
    private static int borderShortfallOf(RawRuleTable table, BiomeId biome, Map<BiomeId, Integer> edgesToBiome) {
        return table.requirementOf(biome).map(requirement -> requirement.shortfall(other -> edgesToBiome.getOrDefault(other, 0))).orElse(0);
    }

    /** Independent, set-based count of a cluster's thin cells (see {@link ShapeRule.Thickness}) - shares nothing with the search's array version. */
    private static int thinCellsOf(ToroidalGrid grid, ClusterSizeModel model, BiomeId biome, Set<ToroidalPos> cluster) {
        int tailReachCells = model.shape(biome).tailReachCells();
        switch (model.shape(biome).thicknessRequiredFor(cluster.size())) {
            case NONE:
                return 0;
            case TRIANGLE:
                int outsideTriangles = 0;
                for (ToroidalPos pos : cluster) {
                    if (isOutsideAnyTriangle(grid, cluster, pos)) {
                        outsideTriangles++;
                    }
                }
                return outsideTriangles;
            default:
                Map<ToroidalPos, Integer> stepsFromFlowerCenter = new HashMap<>();
                Deque<ToroidalPos> frontier = new ArrayDeque<>();
                for (ToroidalPos pos : cluster) {
                    addIfFlowerCenter(grid, cluster, stepsFromFlowerCenter, frontier, pos);
                }
                while (!frontier.isEmpty()) {
                    expandFlowerReach(grid, cluster, stepsFromFlowerCenter, frontier, tailReachCells, frontier.poll());
                }
                return cluster.size() - stepsFromFlowerCenter.size();
        }
    }

    private static boolean isOutsideAnyTriangle(ToroidalGrid grid, Set<ToroidalPos> cluster, ToroidalPos pos) {
        List<ToroidalPos> ring = grid.neighborsOf(pos);
        boolean inTriangle = false;
        for (int corner = 0; corner < ring.size(); corner++) {
            inTriangle |= cluster.contains(ring.get(corner)) && cluster.contains(ring.get((corner + 1) % ring.size()));
        }
        return !inTriangle;
    }

    private static void addIfFlowerCenter(ToroidalGrid grid, Set<ToroidalPos> cluster, Map<ToroidalPos, Integer> stepsFromFlowerCenter, Deque<ToroidalPos> frontier, ToroidalPos pos) {
        if (cluster.containsAll(grid.neighborsOf(pos))) {
            stepsFromFlowerCenter.put(pos, 0);
            frontier.add(pos);
        }
    }

    private static void expandFlowerReach(
        ToroidalGrid grid, Set<ToroidalPos> cluster, Map<ToroidalPos, Integer> stepsFromFlowerCenter, Deque<ToroidalPos> frontier, int tailReachCells, ToroidalPos pos
    ) {
        int steps = stepsFromFlowerCenter.get(pos);
        if (steps < tailReachCells) {
            for (ToroidalPos neighbor : grid.neighborsOf(pos)) {
                if (cluster.contains(neighbor) && stepsFromFlowerCenter.putIfAbsent(neighbor, steps + 1) == null) {
                    frontier.add(neighbor);
                }
            }
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
        private int exploredPerimeter;
        private int exploredBorderShortfall;
        private final boolean[] needsBorder;
        private final int[] borderEdges;
        private final int[] borderEdgeBiomes;
        private int borderEdgeBiomeCount;
        private final int[] reachDistance;
        private final int[] reachQueue;
        private final double relabelProbability;
        private final int[] changedCells;
        private final int[] changedStamp;
        private int changedGeneration;
        private final int[] seenBiomeStamp;
        private final int[] seenBiomes;
        private int seenBiomeGeneration;

        Search(RawRuleTable table, ToroidalGrid grid, ClusterSizeModel model, Random random, Schedule schedule, BooleanSupplier shouldStop, double relabelProbability) {
            this.table = table;
            this.relabelProbability = relabelProbability;
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
                    acceptanceProbabilityByLevel[level][delta] = StrictMath.exp(-delta / temperature);
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
            this.changedCells = new int[cellCount];
            this.changedStamp = new int[cellCount];
            this.needsBorder = new boolean[biomeCount];
            for (int biome = 0; biome < biomeCount; biome++) {
                needsBorder[biome] = model.hasRequirement(biome);
            }
            this.borderEdges = new int[biomeCount];
            this.borderEdgeBiomes = new int[biomeCount];
            this.seenBiomeStamp = new int[biomeCount];
            this.seenBiomes = new int[biomeCount];
        }

        Result run(long maxSteps) {
            initializeFromRandomVoronoi();
            return runFrom(maxSteps);
        }

        /** As {@link #run(long)}, but starting from {@code initialAssignment} instead of a random Voronoi partition. */
        Result run(long maxSteps, Map<ToroidalPos, BiomeId> initialAssignment) {
            initializeFrom(initialAssignment);
            return runFrom(maxSteps);
        }

        private Result runFrom(long maxSteps) {
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
                    TrackResult tracked = trackBestAndCheckStop(step, bestCost, bestValue);
                    bestCost = tracked.bestCost();
                    if (tracked.stop()) {
                        stepsTaken = step + 1;
                        break;
                    }
                }
            }
            if (cost == 0) {
                return solved(stepsTaken);
            }
            failIfBookkeepingDrifted();
            if (cost < bestCost) {
                bestCost = cost;
                System.arraycopy(value, 0, bestValue, 0, cellCount);
            }
            return new Result(null, toAssignment(bestValue), stepsTaken, bestCost);
        }

        /** Refreshes the acceptance table and conflicted-cell snapshot for the level {@code step} falls in, updates {@code bestCost}/{@code bestValue} if the walk improved, and polls {@code shouldStop}. */
        private record TrackResult(int bestCost, boolean stop) {
        }

        private TrackResult trackBestAndCheckStop(long step, int bestCost, int[] bestValue) {
            acceptanceProbability = acceptanceProbabilityByLevel[schedule.levelAtStep(step)];
            collectConflictedCells();
            if (cost < bestCost) {
                bestCost = cost;
                System.arraycopy(value, 0, bestValue, 0, cellCount);
            }
            return new TrackResult(bestCost, shouldStop.getAsBoolean());
        }

        /**
         * The search keeps its cost, per-biome cell counts and missing-biome count up to date move by
         * move. Recomputes all three from the cell values once at the end of a run and throws if
         * they disagree: a drift would mean the walk had been steering by a wrong cost, and a wrong
         * "closest state" report is worse than a crash.
         */
        private void failIfBookkeepingDrifted() {
            int[] recountedUses = new int[biomeCount];
            for (int cell = 0; cell < cellCount; cell++) {
                recountedUses[value[cell]]++;
            }
            int recountedMissing = 0;
            for (int biome = 0; biome < biomeCount; biome++) {
                if (validateBiomeCountAndCheckMissing(biome, recountedUses)) {
                    recountedMissing++;
                }
            }
            if (recountedMissing != missingBiomes) {
                throw new IllegalStateException("ToroidalSizedLocalSearch: missing-biome count drifted: tracked " + missingBiomes + ", actual " + recountedMissing);
            }
            int recomputedCost = fullCost();
            if (recomputedCost != cost) {
                throw new IllegalStateException("ToroidalSizedLocalSearch: cost drifted: tracked " + cost + ", recomputed from scratch " + recomputedCost);
            }
        }

        /** Throws if {@code biome}'s recounted cell total disagrees with the tracked one; otherwise returns whether it has become missing. */
        private boolean validateBiomeCountAndCheckMissing(int biome, int[] recountedUses) {
            if (recountedUses[biome] != usesOfBiome[biome]) {
                throw new IllegalStateException(
                    "ToroidalSizedLocalSearch: cell count of " + biomes.get(biome) + " drifted: tracked " + usesOfBiome[biome] + ", actual " + recountedUses[biome]
                );
            }
            return recountedUses[biome] == 0;
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
                assignNearestSeedBiome(shuffledCells, seedBiome, seedCount, cell);
            }
            recomputeUsesAndMissing();
        }

        /** Sets {@code value[]} from {@code assignment} directly - every cell must be covered, every biome named must be one of {@code table}'s. */
        private void initializeFrom(Map<ToroidalPos, BiomeId> assignment) {
            if (assignment.size() != cellCount) {
                throw new IllegalArgumentException("ToroidalSizedLocalSearch: initial assignment covers " + assignment.size() + " of " + cellCount + " cells");
            }
            for (int cell = 0; cell < cellCount; cell++) {
                assignSeededCell(assignment, cell);
            }
            recomputeUsesAndMissing();
        }

        private void assignSeededCell(Map<ToroidalPos, BiomeId> assignment, int cell) {
            ToroidalPos position = positions.get(cell);
            BiomeId biome = assignment.get(position);
            if (biome == null) {
                throw new IllegalArgumentException("ToroidalSizedLocalSearch: initial assignment has no biome for " + position);
            }
            int index = biomes.indexOf(biome);
            if (index < 0) {
                throw new IllegalArgumentException("ToroidalSizedLocalSearch: initial assignment names " + biome + " at " + position + ", which is not in the catalog");
            }
            value[cell] = index;
        }

        /** Recomputes {@code usesOfBiome}/{@code missingBiomes} from {@code value[]} - shared by every way of seeding it. */
        private void recomputeUsesAndMissing() {
            for (int biome = 0; biome < biomeCount; biome++) {
                usesOfBiome[biome] = 0;
            }
            for (int cell = 0; cell < cellCount; cell++) {
                usesOfBiome[value[cell]]++;
            }
            missingBiomes = 0;
            for (int biome = 0; biome < biomeCount; biome++) {
                if (isBiomeMissing(biome)) {
                    missingBiomes++;
                }
            }
        }

        private void assignNearestSeedBiome(int[] shuffledCells, int[] seedBiome, int seedCount, int cell) {
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

        private boolean isBiomeMissing(int biome) {
            return usesOfBiome[biome] == 0;
        }

        /** Hex distance on the wrapped grid: the smallest over the nine ways the torus can wrap the offset between the two cells. */
        private int torusDistance(ToroidalPos first, ToroidalPos second) {
            int deltaQ = first.q() - second.q();
            int deltaR = first.r() - second.r();
            int best = Integer.MAX_VALUE;
            for (int wrapQ = -1; wrapQ <= 1; wrapQ++) {
                for (int wrapR = -1; wrapR <= 1; wrapR++) {
                    best = Math.min(best, wrappedDistanceCandidate(deltaQ, deltaR, wrapQ, wrapR));
                }
            }
            return best;
        }

        private int wrappedDistanceCandidate(int deltaQ, int deltaR, int wrapQ, int wrapR) {
            int q = deltaQ + wrapQ * grid.width();
            int r = deltaR + wrapR * grid.height();
            return (Math.abs(q) + Math.abs(r) + Math.abs(q + r)) / 2;
        }

        private int fullCost() {
            stamp++;
            int total = 0;
            for (int cell = 0; cell < cellCount; cell++) {
                total += countIllegalEdgesFromCell(cell);
            }
            for (int cell = 0; cell < cellCount; cell++) {
                if (visitStamp[cell] != stamp) {
                    exploreCluster(cell);
                    total += model.violation(value[cell], exploredSize, exploredThinCells, exploredPerimeter, exploredBorderShortfall);
                }
            }
            return total + MISSING_BIOME_COST * missingBiomes;
        }

        private int countIllegalEdgesFromCell(int cell) {
            int count = 0;
            for (int neighbor : neighbors[cell]) {
                if (cell < neighbor && value[cell] != value[neighbor] && !legal[value[cell]][value[neighbor]]) {
                    count++;
                }
            }
            return count;
        }

        /** Snapshot of the cells touching an illegal edge. It goes stale between refreshes, which only costs a wasted proposal - it is just where the walk looks, never a cost input. */
        private void collectConflictedCells() {
            conflictedCount = 0;
            for (int cell = 0; cell < cellCount; cell++) {
                if (cellHasConflict(cell)) {
                    conflictedCells[conflictedCount++] = cell;
                }
            }
        }

        private boolean cellHasConflict(int cell) {
            for (int neighbor : neighbors[cell]) {
                if (value[cell] != value[neighbor] && !legal[value[cell]][value[neighbor]]) {
                    return true;
                }
            }
            return false;
        }

        private void proposeAndMaybeApply() {
            if (relabelProbability > 0.0 && random.nextDouble() < relabelProbability) {
                proposeWholeClusterRelabel();
                return;
            }
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

        /**
         * Relabels the whole cluster around a (preferably conflicted) cell to another biome in one
         * move, judged by the same Metropolis rule as a single-cell move. The new biome must be
         * legal beside every biome the cluster touches and its size range must contain the
         * cluster's cell count, so the move is only tried where it could plausibly help. The cost
         * change is measured by applying the move and re-measuring just the affected region
         * ({@link #localCost}); a rejected move is undone.
         */
        private void proposeWholeClusterRelabel() {
            int cell = conflictedCount > 0 && random.nextDouble() < FOCUS_PROBABILITY ? conflictedCells[random.nextInt(conflictedCount)] : random.nextInt(cellCount);
            int old = value[cell];
            stamp++;
            exploreCluster(cell);
            int size = exploredSize;
            System.arraycopy(queue, 0, changedCells, 0, size);
            int candidate = randomBiomeFittingClusterAndItsSurroundings(old, size);
            if (candidate < 0) {
                return;
            }

            int costBefore = localCost(size);
            int missingBefore = missingBiomes;
            setChangedCells(size, candidate);
            usesOfBiome[old] -= size;
            usesOfBiome[candidate] += size;
            int missingAfter = missingBefore + (usesOfBiome[old] == 0 ? 1 : 0) - (usesOfBiome[candidate] == size ? 1 : 0);
            int costAfter = localCost(size);

            int delta = costAfter - costBefore + MISSING_BIOME_COST * (missingAfter - missingBefore);
            boolean accept = delta <= 0 || (delta < ACCEPTANCE_TABLE_SIZE && random.nextDouble() < acceptanceProbability[delta]);
            if (accept) {
                cost += delta;
                missingBiomes = missingAfter;
            } else {
                setChangedCells(size, old);
                usesOfBiome[old] += size;
                usesOfBiome[candidate] -= size;
            }
        }

        private void setChangedCells(int size, int biome) {
            for (int index = 0; index < size; index++) {
                value[changedCells[index]] = biome;
            }
        }

        /** A uniformly random biome other than {@code old} that is legal beside every biome bordering the cluster in {@code changedCells} and whose size range holds {@code size} cells; -1 if there is none. */
        private int randomBiomeFittingClusterAndItsSurroundings(int old, int size) {
            seenBiomeGeneration++;
            int seenCount = 0;
            for (int index = 0; index < size; index++) {
                for (int neighbor : neighbors[changedCells[index]]) {
                    seenCount = gatherNeighborBiome(neighbor, old, seenCount);
                }
            }
            FitCandidate fit = new FitCandidate(0, -1);
            for (int biome = 0; biome < biomeCount; biome++) {
                fit = considerBiomeAsFit(biome, old, size, seenCount, fit);
            }
            return fit.chosen();
        }

        /** Records neighbor {@code neighbor}'s biome into {@code seenBiomes} if it is not {@code old} and not already recorded this generation; returns the updated count. */
        private int gatherNeighborBiome(int neighbor, int old, int seenCount) {
            int neighborBiome = value[neighbor];
            if (neighborBiome != old && seenBiomeStamp[neighborBiome] != seenBiomeGeneration) {
                seenBiomeStamp[neighborBiome] = seenBiomeGeneration;
                seenBiomes[seenCount++] = neighborBiome;
            }
            return seenCount;
        }

        /** Reservoir sample over the fitting biomes seen so far, updated by whether {@code biome} itself fits. */
        private record FitCandidate(int fitting, int chosen) {
        }

        private FitCandidate considerBiomeAsFit(int biome, int old, int size, int seenCount, FitCandidate soFar) {
            if (biome == old || size < model.minCells(biome) || size > model.maxCells(biome)) {
                return soFar;
            }
            boolean legalBesideAll = true;
            for (int index = 0; index < seenCount && legalBesideAll; index++) {
                legalBesideAll = seenBiomes[index] == biome || legal[biome][seenBiomes[index]];
            }
            if (!legalBesideAll) {
                return soFar;
            }
            int fitting = soFar.fitting() + 1;
            int chosen = random.nextInt(fitting) == 0 ? biome : soFar.chosen();
            return new FitCandidate(fitting, chosen);
        }

        /**
         * Everything in the cost that depends on the {@code changedSize} cells in {@code changedCells}
         * (given their CURRENT biomes): illegal edges touching them, and the violation of every
         * cluster that contains one of them or borders one - so a cluster that merges, splits or
         * loses cells is counted whole. The difference between two calls, before and after changing
         * those cells, is the exact cost change of the move.
         */
        private int localCost(int changedSize) {
            changedGeneration++;
            for (int index = 0; index < changedSize; index++) {
                changedStamp[changedCells[index]] = changedGeneration;
            }
            int total = 0;
            for (int index = 0; index < changedSize; index++) {
                total += countIllegalEdgesForChangedCell(changedCells[index]);
            }
            stamp++;
            for (int index = 0; index < changedSize; index++) {
                total += clusterViolationForChangedCell(changedCells[index]);
            }
            return total;
        }

        private int countIllegalEdgesForChangedCell(int cell) {
            int count = 0;
            for (int neighbor : neighbors[cell]) {
                boolean illegalEdge = value[cell] != value[neighbor] && !legal[value[cell]][value[neighbor]];
                if (illegalEdge && (changedStamp[neighbor] != changedGeneration || cell < neighbor)) {
                    count++;
                }
            }
            return count;
        }

        private int clusterViolationForChangedCell(int cell) {
            int total = 0;
            if (visitStamp[cell] != stamp) {
                exploreCluster(cell);
                total += model.violation(value[cell], exploredSize, exploredThinCells, exploredPerimeter, exploredBorderShortfall);
            }
            for (int neighbor : neighbors[cell]) {
                if (visitStamp[neighbor] != stamp) {
                    exploreCluster(neighbor);
                    total += model.violation(value[neighbor], exploredSize, exploredThinCells, exploredPerimeter, exploredBorderShortfall);
                }
            }
            return total;
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
         * the missing-biome count. Every other cluster keeps its size, perimeter and thin cells, because a
         * neighbor of some third biome sees a different biome across that edge either way.
         */
        private int costChangeOfReassigning(int cell, int old, int candidate) {
            int[] cellNeighbors = neighbors[cell];
            int edgeDelta = edgeCountDelta(cellNeighbors, old, candidate);

            int clustersBefore = clusterCostBesideCell(cell, cellNeighbors, candidate, old, candidate);
            value[cell] = candidate;
            int clustersAfter = clusterCostBesideCell(cell, cellNeighbors, old, old, candidate);
            value[cell] = old;

            int missingChange = (usesOfBiome[old] == 1 ? MISSING_BIOME_COST : 0) - (usesOfBiome[candidate] == 0 ? MISSING_BIOME_COST : 0);
            return edgeDelta + clustersAfter - clustersBefore + missingChange;
        }

        /** How many more illegal edges {@code cell} would have as {@code candidate} than it has as {@code old}, counting only {@code cell}'s own edges (every other cell's edges are unaffected). */
        private int edgeCountDelta(int[] cellNeighbors, int old, int candidate) {
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
            return edgesAfter - edgesBefore;
        }

        /**
         * Violation of the cluster {@code cell} currently sits in (its biome read straight from {@code value[cell]}, so the caller must set that
         * biome before calling), plus any not-yet-visited {@code alsoExplore}-biome cluster beside it (the one {@code cell} would merge with or
         * split from), plus third-biome requirement clusters beside it. Called once with {@code cell} still {@code old} and once with it already
         * {@code candidate} - see {@link #costChangeOfReassigning}.
         */
        private int clusterCostBesideCell(int cell, int[] cellNeighbors, int alsoExplore, int old, int candidate) {
            stamp++;
            exploreCluster(cell);
            int cost = model.violation(value[cell], exploredSize, exploredThinCells, exploredPerimeter, exploredBorderShortfall);
            for (int neighbor : cellNeighbors) {
                if (value[neighbor] == alsoExplore && visitStamp[neighbor] != stamp) {
                    exploreCluster(neighbor);
                    cost += model.violation(alsoExplore, exploredSize, exploredThinCells, exploredPerimeter, exploredBorderShortfall);
                }
            }
            return cost + requirementClustersBesideThatAreNeitherBiome(cellNeighbors, old, candidate);
        }

        /**
         * Violation of each cluster, of some third biome with border requirements, that touches the cell being changed from {@code old} to {@code candidate}. Its size and shape do not
         * depend on the change, but the edges it shares with {@code old} or {@code candidate} do, so the move's cost change must include it. Uses the current stamp: a cluster already
         * measured in this pass is not counted twice.
         */
        private int requirementClustersBesideThatAreNeitherBiome(int[] cellNeighbors, int old, int candidate) {
            int total = 0;
            for (int neighbor : cellNeighbors) {
                int neighborValue = value[neighbor];
                if (neighborValue != old && neighborValue != candidate && needsBorder[neighborValue] && visitStamp[neighbor] != stamp) {
                    exploreCluster(neighbor);
                    total += model.violation(neighborValue, exploredSize, exploredThinCells, exploredPerimeter, exploredBorderShortfall);
                }
            }
            return total;
        }

        /** Breadth-first over the same-biome cluster containing {@code start}, marking it visited with the current stamp; leaves its cell count, perimeter (cell-edges to a different biome) and thin-cell count (see {@link ShapeRule}) in {@code exploredSize}/{@code exploredPerimeter}/{@code exploredThinCells}. */
        private void exploreCluster(int start) {
            int biome = value[start];
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            visitStamp[start] = stamp;
            int perimeter = 0;
            boolean countsBorders = needsBorder[biome];
            while (head < tail) {
                int cell = queue[head++];
                for (int neighbor : neighbors[cell]) {
                    if (value[neighbor] != biome) {
                        perimeter++;
                        if (countsBorders) {
                            countEdgeTo(value[neighbor]);
                        }
                    } else {
                        tail = enqueueIfUnvisited(neighbor, tail);
                    }
                }
            }
            exploredSize = tail;
            exploredPerimeter = perimeter;
            exploredBorderShortfall = countsBorders ? borderShortfallOfExploredCluster(biome) : 0;
            exploredThinCells = switch (model.shape(biome).thicknessRequiredFor(tail)) {
                case NONE -> 0;
                case TRIANGLE -> countCellsOutsideAnyTriangle(biome, tail);
                case FLOWER -> countCellsBeyondReachOfAFlower(biome, tail);
            };
        }

        /** Enqueues {@code neighbor} for the cluster BFS if not already visited this stamp; returns the updated queue tail. */
        private int enqueueIfUnvisited(int neighbor, int tail) {
            if (visitStamp[neighbor] != stamp) {
                visitStamp[neighbor] = stamp;
                queue[tail++] = neighbor;
            }
            return tail;
        }

        /** Counts one more edge of the cluster being explored towards {@code otherBiome}. */
        private void countEdgeTo(int otherBiome) {
            if (borderEdges[otherBiome]++ == 0) {
                borderEdgeBiomes[borderEdgeBiomeCount++] = otherBiome;
            }
        }

        /** How many shared edges the cluster just explored is short of the borders {@code biome} requires; leaves the edge counters zeroed for the next cluster. */
        private int borderShortfallOfExploredCluster(int biome) {
            int shortfall = model.borderShortfall(biome, borderEdges);
            for (int index = 0; index < borderEdgeBiomeCount; index++) {
                borderEdges[borderEdgeBiomes[index]] = 0;
            }
            borderEdgeBiomeCount = 0;
            return shortfall;
        }

        /** Of the {@code clusterSize} cells in {@code queue}, how many have no two consecutive neighbors of the same cluster - i.e. are in no triangle. Neighbors are in ring order, so consecutive ones touch each other. */
        private int countCellsOutsideAnyTriangle(int biome, int clusterSize) {
            int outside = 0;
            for (int index = 0; index < clusterSize; index++) {
                if (cellIsOutsideAnyTriangle(queue[index], biome)) {
                    outside++;
                }
            }
            return outside;
        }

        private boolean cellIsOutsideAnyTriangle(int cell, int biome) {
            int[] ring = neighbors[cell];
            boolean inTriangle = false;
            for (int corner = 0; corner < ring.length && !inTriangle; corner++) {
                inTriangle = value[ring[corner]] == biome && value[ring[(corner + 1) % ring.length]] == biome;
            }
            return !inTriangle;
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
                if (isFlowerCenter(cell, biome)) {
                    reachDistance[cell] = 0;
                    reachQueue[tail++] = cell;
                }
            }
            int reach = model.shape(biome).tailReachCells();
            while (head < tail) {
                int cell = reachQueue[head++];
                if (reachDistance[cell] == reach) {
                    continue;
                }
                for (int neighbor : neighbors[cell]) {
                    tail = visitFlowerNeighborIfReachable(neighbor, reachDistance[cell], biome, tail);
                }
            }
            return clusterSize - tail;
        }

        private boolean isFlowerCenter(int cell, int biome) {
            boolean isFlowerCenter = true;
            for (int neighbor : neighbors[cell]) {
                isFlowerCenter &= value[neighbor] == biome;
            }
            return isFlowerCenter;
        }

        private int visitFlowerNeighborIfReachable(int neighbor, int distance, int biome, int tail) {
            if (value[neighbor] == biome && reachDistance[neighbor] == -1) {
                reachDistance[neighbor] = distance + 1;
                reachQueue[tail++] = neighbor;
            }
            return tail;
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
