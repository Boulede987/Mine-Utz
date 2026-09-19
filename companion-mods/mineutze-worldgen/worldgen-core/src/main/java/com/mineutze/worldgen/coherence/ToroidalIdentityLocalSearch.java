package com.mineutze.worldgen.coherence;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Min-conflicts local search for the same identity-only toroidal coverage problem {@link
 * ToroidalIdentityCoverageProof} attacks with complete backtracking (2026-09-19). That solver
 * committed cells one at a time and could not undo an early bad commitment cheaply: depth 67 at
 * 50,000 backtracks and 129 at 2,000,000 - roughly 60 more cells per 40x more budget, against 127
 * still missing, so no realistic budget closes the gap.
 *
 * <p>This one never commits. It holds a FULL assignment of every cell, and its cost is the number
 * of illegal adjacent pairs plus a weighted count of still-missing biomes. Each step repairs the
 * worst violation: pick a conflicted cell and give it the value that minimizes the resulting cost
 * (min-conflicts), or, when a biome is missing, move it onto the cheapest redundant cell. A tabu
 * list stops immediate undo, a small noise probability breaks plateaus, and a long stall triggers
 * a random restart. A solution is a state with zero cost; it is then re-verified independently
 * against {@link RawRuleTable#isLegal} on every torus edge before being returned.
 */
final class ToroidalIdentityLocalSearch {

    private static final int MISSING_BIOME_WEIGHT = 2;
    private static final double COVERAGE_MOVE_PROBABILITY = 0.3;
    private static final double NOISE_PROBABILITY = 0.02;
    private static final int TABU_TENURE_BASE = 8;
    private static final int TABU_TENURE_SPREAD = 8;
    private static final int CLOCK_CHECK_INTERVAL_STEPS = 4096;
    private static final int WINDOW_REPAIR_INTERVAL_STEPS = 20_000;
    private static final int WINDOW_REPAIR_MAX_ILLEGAL_EDGES = 10;
    private static final int[] WINDOW_REPAIR_RADII = {2, 3, 4};
    private static final int WINDOW_NODE_BUDGET = 5_000;

    private ToroidalIdentityLocalSearch() {
    }

    /** {@code assignment()==null} means the time budget ran out; {@code closestAssignment}, {@code bestIllegalEdges} and {@code bestMissingBiomes} then describe the lowest-cost state ever reached (equal to {@code assignment} when solved). */
    record Result(Map<ToroidalPos, BiomeId> assignment, Map<ToroidalPos, BiomeId> closestAssignment, long steps, int restarts, int windowRepairs, int bestIllegalEdges, int bestMissingBiomes) {
    }

    /** Human-readable list of every illegal torus edge in {@code assignment} (each edge once), for diagnosing what a near-miss state is stuck on. */
    static List<String> illegalEdgeDescriptions(RawRuleTable table, ToroidalGrid grid, Map<ToroidalPos, BiomeId> assignment) {
        List<String> descriptions = new ArrayList<>();
        for (Map.Entry<ToroidalPos, BiomeId> entry : assignment.entrySet()) {
            for (ToroidalPos neighbor : grid.neighborsOf(entry.getKey())) {
                BiomeId neighborBiome = assignment.get(neighbor);
                boolean firstOfPair = entry.getKey().r() < neighbor.r() || (entry.getKey().r() == neighbor.r() && entry.getKey().q() < neighbor.q());
                if (firstOfPair && !table.isLegal(entry.getValue(), neighborBiome)) {
                    descriptions.add(entry.getKey() + " " + entry.getValue() + "  <->  " + neighbor + " " + neighborBiome);
                }
            }
        }
        return descriptions;
    }

    static Result search(RawRuleTable table, ToroidalGrid grid, long randomSeed, long maxMillis, long stallStepsBeforeRestart) {
        return search(table, grid, randomSeed, maxMillis, stallStepsBeforeRestart, Set.copyOf(table.allBiomeIds()));
    }

    /** As above, but only {@code requiredBiomes} must appear - used to find which biomes make the full problem frustrated, by solving progressively larger subsets. */
    static Result search(RawRuleTable table, ToroidalGrid grid, long randomSeed, long maxMillis, long stallStepsBeforeRestart, Set<BiomeId> requiredBiomes) {
        Search search = new Search(table, grid, new Random(randomSeed), requiredBiomes);
        return search.run(maxMillis, Long.MAX_VALUE, stallStepsBeforeRestart);
    }

    /**
     * Limited by search STEPS instead of wall-clock time. A step budget gives the same outcome on
     * every run and every machine - which pattern a world seed gets must never depend on how busy
     * the server was - whereas a time limit can cut the same seed off at different points.
     */
    static Result searchWithinSteps(RawRuleTable table, ToroidalGrid grid, long randomSeed, long maxSteps, long stallStepsBeforeRestart, Set<BiomeId> requiredBiomes) {
        Search search = new Search(table, grid, new Random(randomSeed), requiredBiomes);
        return search.run(Long.MAX_VALUE, maxSteps, stallStepsBeforeRestart);
    }

    /** Independent check: every torus edge legal by the real rule table, every biome present. Throws on any violation - a "solution" that fails this is a bug, never a result. */
    static void verify(RawRuleTable table, ToroidalGrid grid, Map<ToroidalPos, BiomeId> assignment, Set<BiomeId> requiredBiomes) {
        if (assignment.size() != grid.cellCount()) {
            throw new IllegalStateException("ToroidalIdentityLocalSearch: assignment covers " + assignment.size() + " of " + grid.cellCount() + " cells");
        }
        for (Map.Entry<ToroidalPos, BiomeId> entry : assignment.entrySet()) {
            for (ToroidalPos neighbor : grid.neighborsOf(entry.getKey())) {
                BiomeId neighborBiome = assignment.get(neighbor);
                if (!table.isLegal(entry.getValue(), neighborBiome)) {
                    throw new IllegalStateException(
                        "ToroidalIdentityLocalSearch: illegal adjacency " + entry.getKey() + "(" + entry.getValue() + ") <-> " + neighbor + "(" + neighborBiome + ")"
                    );
                }
            }
        }
        for (BiomeId biome : requiredBiomes) {
            if (!assignment.containsValue(biome)) {
                throw new IllegalStateException("ToroidalIdentityLocalSearch: biome " + biome + " missing from the assignment");
            }
        }
    }

    private static final class Search {
        private final RawRuleTable table;
        private final Set<BiomeId> requiredBiomes;
        private final boolean[] required;
        private final ToroidalGrid grid;
        private final Random random;
        private final List<BiomeId> biomes;
        private final List<ToroidalPos> positions;
        private final int biomeCount;
        private final int cellCount;
        private final boolean[][] legal;
        private final int[][] neighbors;
        private final int[][] edgeIds;
        private final int[] edgeWeight;

        private final int[] value;
        private final int[] violations;
        private final int[] weightedConflicts;
        private int lastBestScore;
        private final int[] usesOfBiome;
        private final long[] tabuUntilStep;
        private int illegalEdges;
        private int missingBiomes;

        private final int[] conflictedCells;
        private final int[] conflictedSlot;
        private int conflictedSize;

        private final int[] windowSlot;
        private int windowRepairs;
        private long step;

        Search(RawRuleTable table, ToroidalGrid grid, Random random, Set<BiomeId> requiredBiomes) {
            this.table = table;
            this.requiredBiomes = requiredBiomes;
            this.grid = grid;
            this.random = random;
            this.biomes = table.allBiomeIds();
            this.positions = grid.allPositionsInFillOrder();
            this.biomeCount = biomes.size();
            this.cellCount = positions.size();

            this.required = new boolean[biomeCount];
            for (int biome = 0; biome < biomeCount; biome++) {
                required[biome] = requiredBiomes.contains(biomes.get(biome));
            }

            this.legal = new boolean[biomeCount][biomeCount];
            for (int first = 0; first < biomeCount; first++) {
                for (int second = 0; second < biomeCount; second++) {
                    legal[first][second] = table.isLegal(biomes.get(first), biomes.get(second));
                }
            }

            Map<ToroidalPos, Integer> indexOf = new HashMap<>();
            for (int index = 0; index < cellCount; index++) {
                indexOf.put(positions.get(index), index);
            }
            this.neighbors = new int[cellCount][];
            for (int index = 0; index < cellCount; index++) {
                neighbors[index] = grid.neighborsOf(positions.get(index)).stream().mapToInt(indexOf::get).toArray();
            }

            this.edgeIds = new int[cellCount][];
            Map<Long, Integer> edgeIdByPair = new HashMap<>();
            for (int index = 0; index < cellCount; index++) {
                edgeIds[index] = new int[neighbors[index].length];
                for (int slot = 0; slot < neighbors[index].length; slot++) {
                    int other = neighbors[index][slot];
                    long pairKey = (long) Math.min(index, other) * cellCount + Math.max(index, other);
                    edgeIds[index][slot] = edgeIdByPair.computeIfAbsent(pairKey, key -> edgeIdByPair.size());
                }
            }
            this.edgeWeight = new int[edgeIdByPair.size()];

            this.value = new int[cellCount];
            this.violations = new int[cellCount];
            this.weightedConflicts = new int[cellCount];
            this.usesOfBiome = new int[biomeCount];
            this.tabuUntilStep = new long[cellCount * biomeCount];
            this.conflictedCells = new int[cellCount];
            this.conflictedSlot = new int[cellCount];
            this.windowSlot = new int[cellCount];
            Arrays.fill(windowSlot, -1);
        }

        Result run(long maxMillis, long maxSteps, long stallStepsBeforeRestart) {
            long startNanos = System.nanoTime();
            long maxNanos = maxMillis > Long.MAX_VALUE / 1_000_000L ? Long.MAX_VALUE : maxMillis * 1_000_000L;
            int restarts = 0;
            int bestIllegal = Integer.MAX_VALUE;
            int bestMissing = Integer.MAX_VALUE;
            int bestCost = Integer.MAX_VALUE;
            int[] bestValue = value.clone();

            randomize();
            int runBestCost = cost();
            long lastImprovementStep = 0;

            while (true) {
                if (illegalEdges == 0 && missingBiomes == 0) {
                    Map<ToroidalPos, BiomeId> assignment = toAssignment(value);
                    verify(table, grid, assignment, requiredBiomes);
                    return new Result(assignment, assignment, step, restarts, windowRepairs, 0, 0);
                }
                boolean outOfSteps = step >= maxSteps;
                boolean outOfTime = step % CLOCK_CHECK_INTERVAL_STEPS == 0 && System.nanoTime() - startNanos > maxNanos;
                if (outOfSteps || outOfTime) {
                    return new Result(null, toAssignment(bestValue), step, restarts, windowRepairs, bestIllegal, bestMissing);
                }

                stepOnce();
                step++;

                int currentCost = cost();
                if (currentCost < runBestCost) {
                    runBestCost = currentCost;
                    lastImprovementStep = step;
                }
                if (currentCost < bestCost) {
                    bestCost = currentCost;
                    bestIllegal = illegalEdges;
                    bestMissing = missingBiomes;
                    System.arraycopy(value, 0, bestValue, 0, cellCount);
                }
                if (step - lastImprovementStep > stallStepsBeforeRestart) {
                    randomize();
                    restarts++;
                    runBestCost = cost();
                    lastImprovementStep = step;
                }
            }
        }

        private int cost() {
            return illegalEdges + MISSING_BIOME_WEIGHT * missingBiomes;
        }

        private void randomize() {
            for (int cell = 0; cell < cellCount; cell++) {
                value[cell] = random.nextInt(biomeCount);
            }
            Arrays.fill(tabuUntilStep, 0L);
            Arrays.fill(usesOfBiome, 0);
            for (int cell = 0; cell < cellCount; cell++) {
                usesOfBiome[value[cell]]++;
            }
            missingBiomes = 0;
            for (int biome = 0; biome < biomeCount; biome++) {
                if (required[biome] && usesOfBiome[biome] == 0) {
                    missingBiomes++;
                }
            }
            Arrays.fill(conflictedSlot, -1);
            conflictedSize = 0;
            Arrays.fill(edgeWeight, 1);
            int violationSum = 0;
            for (int cell = 0; cell < cellCount; cell++) {
                violations[cell] = violationsIfValue(cell, value[cell]);
                weightedConflicts[cell] = weightedConflictsIfValue(cell, value[cell]);
                violationSum += violations[cell];
                if (violations[cell] > 0) {
                    addConflicted(cell);
                }
            }
            illegalEdges = violationSum / 2;
        }

        private int violationsIfValue(int cell, int candidateValue) {
            int count = 0;
            for (int neighbor : neighbors[cell]) {
                if (!legal[candidateValue][value[neighbor]]) {
                    count++;
                }
            }
            return count;
        }

        /** Sum of the CURRENT weights of the edges that would be illegal - the breakout method's cost, not the raw violation count. */
        private int weightedConflictsIfValue(int cell, int candidateValue) {
            int total = 0;
            int[] cellNeighbors = neighbors[cell];
            int[] cellEdges = edgeIds[cell];
            for (int slot = 0; slot < cellNeighbors.length; slot++) {
                if (!legal[candidateValue][value[cellNeighbors[slot]]]) {
                    total += edgeWeight[cellEdges[slot]];
                }
            }
            return total;
        }

        private void stepOnce() {
            if (illegalEdges > 0 && illegalEdges <= WINDOW_REPAIR_MAX_ILLEGAL_EDGES && step % WINDOW_REPAIR_INTERVAL_STEPS == 0) {
                repairAroundRandomViolation();
                if (illegalEdges == 0 && missingBiomes == 0) {
                    return;
                }
            }
            boolean coverageMove = missingBiomes > 0 && (illegalEdges == 0 || random.nextDouble() < COVERAGE_MOVE_PROBABILITY);
            if (coverageMove) {
                placeMissingBiome();
            } else {
                repairConflictedCell();
            }
        }

        private void repairConflictedCell() {
            int cell = conflictedCells[random.nextInt(conflictedSize)];
            int oldValue = value[cell];
            int chosen;
            if (random.nextDouble() < NOISE_PROBABILITY) {
                chosen = random.nextInt(biomeCount);
                if (chosen == oldValue) {
                    return;
                }
            } else {
                chosen = bestValueFor(cell, oldValue);
                if (chosen < 0) {
                    return;
                }
                if (lastBestScore >= 0) {
                    raiseWeightsOfViolatedEdges();
                }
            }
            applyMove(cell, chosen);
        }

        /** Best non-tabu value for {@code cell} by resulting cost change (conflicts plus coverage), random among ties; -1 if every alternative is tabu. */
        private int bestValueFor(int cell, int oldValue) {
            int bestScore = Integer.MAX_VALUE;
            int bestValue = -1;
            int ties = 0;
            int lostCoverage = required[oldValue] && usesOfBiome[oldValue] == 1 ? MISSING_BIOME_WEIGHT : 0;
            for (int candidate = 0; candidate < biomeCount; candidate++) {
                if (candidate == oldValue || tabuUntilStep[cell * biomeCount + candidate] > step) {
                    continue;
                }
                int gainedCoverage = required[candidate] && usesOfBiome[candidate] == 0 ? MISSING_BIOME_WEIGHT : 0;
                int score = weightedConflictsIfValue(cell, candidate) - weightedConflicts[cell] + lostCoverage - gainedCoverage;
                if (score < bestScore) {
                    bestScore = score;
                    bestValue = candidate;
                    ties = 1;
                } else if (score == bestScore) {
                    ties++;
                    if (random.nextInt(ties) == 0) {
                        bestValue = candidate;
                    }
                }
            }
            lastBestScore = bestScore;
            return bestValue;
        }

        /** Breakout method: the search just found no improving repair for a conflicted cell, so make every currently violated edge costlier - the local minimum stops being a minimum. */
        private void raiseWeightsOfViolatedEdges() {
            for (int index = 0; index < conflictedSize; index++) {
                int cell = conflictedCells[index];
                int[] cellNeighbors = neighbors[cell];
                for (int slot = 0; slot < cellNeighbors.length; slot++) {
                    int neighbor = cellNeighbors[slot];
                    if (cell < neighbor && !legal[value[cell]][value[neighbor]]) {
                        edgeWeight[edgeIds[cell][slot]]++;
                        weightedConflicts[cell]++;
                        weightedConflicts[neighbor]++;
                    }
                }
            }
        }

        /** Coverage move: put a random missing biome on the redundant cell (its current biome used more than once) where that adds the fewest conflicts. */
        private void placeMissingBiome() {
            int missingPick = random.nextInt(missingBiomes);
            int missingBiome = -1;
            for (int biome = 0; biome < biomeCount; biome++) {
                if (required[biome] && usesOfBiome[biome] == 0 && missingPick-- == 0) {
                    missingBiome = biome;
                    break;
                }
            }

            int bestScore = Integer.MAX_VALUE;
            int bestCell = -1;
            int ties = 0;
            for (int cell = 0; cell < cellCount; cell++) {
                if ((required[value[cell]] && usesOfBiome[value[cell]] <= 1) || tabuUntilStep[cell * biomeCount + missingBiome] > step) {
                    continue;
                }
                int score = weightedConflictsIfValue(cell, missingBiome) - weightedConflicts[cell];
                if (score < bestScore) {
                    bestScore = score;
                    bestCell = cell;
                    ties = 1;
                } else if (score == bestScore) {
                    ties++;
                    if (random.nextInt(ties) == 0) {
                        bestCell = cell;
                    }
                }
            }
            if (bestCell >= 0) {
                applyMove(bestCell, missingBiome);
            }
        }

        /**
         * Large-neighborhood repair: once only a few violations remain, unassign a hex window
         * around one of them and solve just that window EXACTLY against its fixed boundary. Radius-3
         * neighborhoods were verified exactly feasible for every biome, and a window of 19-61 cells
         * is trivial for exact search, so a solution here removes every violation touching the
         * window at once - something one-cell min-conflicts moves keep failing to do around the
         * few biomes with the fewest legal partners.
         */
        private void repairAroundRandomViolation() {
            int center = conflictedCells[random.nextInt(conflictedSize)];
            for (int radius : WINDOW_REPAIR_RADII) {
                if (repairWindow(center, radius)) {
                    windowRepairs++;
                    return;
                }
            }
        }

        private boolean repairWindow(int center, int radius) {
            int[] window = new int[cellCount];
            int[] distance = new int[cellCount];
            int size = 0;
            window[size] = center;
            windowSlot[center] = size++;
            try {
                for (int head = 0; head < size; head++) {
                    int cell = window[head];
                    if (distance[cell] == radius) {
                        continue;
                    }
                    for (int neighbor : neighbors[cell]) {
                        if (windowSlot[neighbor] < 0) {
                            windowSlot[neighbor] = size;
                            distance[neighbor] = distance[cell] + 1;
                            window[size++] = neighbor;
                        }
                    }
                }
                int[] cells = Arrays.copyOf(window, size);

                boolean[][] domains = new boolean[size][biomeCount];
                for (int slot = 0; slot < size; slot++) {
                    for (int candidate = 0; candidate < biomeCount; candidate++) {
                        domains[slot][candidate] = legalAgainstFixedBoundary(cells[slot], candidate);
                    }
                }

                int[] usesInWindow = new int[biomeCount];
                for (int cell : cells) {
                    usesInWindow[value[cell]]++;
                }
                List<Integer> requiredBiomes = new ArrayList<>();
                for (int biome = 0; biome < biomeCount; biome++) {
                    if (required[biome] && usesInWindow[biome] > 0 && usesInWindow[biome] == usesOfBiome[biome]) {
                        requiredBiomes.add(biome);
                    }
                }
                int[] required = requiredBiomes.stream().mapToInt(Integer::intValue).toArray();

                int[] chosen = new int[size];
                Arrays.fill(chosen, -1);
                if (!solveWindow(cells, domains, chosen, 0, required, new int[] {WINDOW_NODE_BUDGET})) {
                    return false;
                }
                for (int slot = 0; slot < size; slot++) {
                    if (value[cells[slot]] != chosen[slot]) {
                        applyMove(cells[slot], chosen[slot]);
                    }
                }
                return true;
            } finally {
                for (int index = 0; index < size; index++) {
                    windowSlot[window[index]] = -1;
                }
            }
        }

        /** A value is allowed in a window cell only if it is legal against every neighbor OUTSIDE the window (those stay fixed); neighbors inside are handled by the search. */
        private boolean legalAgainstFixedBoundary(int cell, int candidate) {
            for (int neighbor : neighbors[cell]) {
                if (windowSlot[neighbor] < 0 && !legal[candidate][value[neighbor]]) {
                    return false;
                }
            }
            return true;
        }

        private boolean solveWindow(int[] cells, boolean[][] domains, int[] chosen, int assigned, int[] required, int[] nodeBudget) {
            if (--nodeBudget[0] < 0) {
                return false;
            }
            int size = cells.length;
            if (assigned == size) {
                return containsAll(chosen, required);
            }

            int slot = -1;
            int slotDomainSize = Integer.MAX_VALUE;
            for (int index = 0; index < size; index++) {
                if (chosen[index] >= 0) {
                    continue;
                }
                int count = 0;
                for (boolean allowed : domains[index]) {
                    if (allowed) {
                        count++;
                    }
                }
                if (count < slotDomainSize) {
                    slotDomainSize = count;
                    slot = index;
                }
            }
            if (slotDomainSize == 0) {
                return false;
            }

            int[] values = new int[slotDomainSize];
            int filled = 0;
            for (int candidate = 0; candidate < biomeCount; candidate++) {
                if (domains[slot][candidate]) {
                    values[filled++] = candidate;
                }
            }
            for (int index = values.length - 1; index > 0; index--) {
                int swapWith = random.nextInt(index + 1);
                int held = values[index];
                values[index] = values[swapWith];
                values[swapWith] = held;
            }

            for (int candidate : values) {
                boolean[][] next = new boolean[size][];
                for (int index = 0; index < size; index++) {
                    next[index] = index == slot ? new boolean[biomeCount] : domains[index].clone();
                }
                next[slot][candidate] = true;
                chosen[slot] = candidate;
                if (forwardCheck(cells, next, chosen, slot, candidate, required) && solveWindow(cells, next, chosen, assigned + 1, required, nodeBudget)) {
                    return true;
                }
            }
            chosen[slot] = -1;
            return false;
        }

        /** After fixing {@code slot} to {@code candidate}: strip now-illegal values from its still-open window neighbors, and make sure every biome whose only occurrences were in this window can still be placed. */
        private boolean forwardCheck(int[] cells, boolean[][] domains, int[] chosen, int slot, int candidate, int[] required) {
            for (int neighbor : neighbors[cells[slot]]) {
                int neighborSlot = windowSlot[neighbor];
                if (neighborSlot < 0 || chosen[neighborSlot] >= 0) {
                    continue;
                }
                boolean any = false;
                for (int other = 0; other < biomeCount; other++) {
                    if (domains[neighborSlot][other] && !legal[candidate][other]) {
                        domains[neighborSlot][other] = false;
                    }
                    any |= domains[neighborSlot][other];
                }
                if (!any) {
                    return false;
                }
            }
            for (int biome : required) {
                if (contains(chosen, biome)) {
                    continue;
                }
                boolean possible = false;
                for (int index = 0; index < cells.length && !possible; index++) {
                    possible = chosen[index] < 0 && domains[index][biome];
                }
                if (!possible) {
                    return false;
                }
            }
            return true;
        }

        private boolean containsAll(int[] chosen, int[] required) {
            for (int biome : required) {
                if (!contains(chosen, biome)) {
                    return false;
                }
            }
            return true;
        }

        private boolean contains(int[] chosen, int biome) {
            for (int candidate : chosen) {
                if (candidate == biome) {
                    return true;
                }
            }
            return false;
        }

        private void applyMove(int cell, int newValue) {
            int oldValue = value[cell];
            int[] cellNeighbors = neighbors[cell];
            for (int slot = 0; slot < cellNeighbors.length; slot++) {
                int neighbor = cellNeighbors[slot];
                boolean wasIllegal = !legal[oldValue][value[neighbor]];
                boolean nowIllegal = !legal[newValue][value[neighbor]];
                if (wasIllegal != nowIllegal) {
                    int delta = nowIllegal ? 1 : -1;
                    int weightedDelta = delta * edgeWeight[edgeIds[cell][slot]];
                    violations[cell] += delta;
                    violations[neighbor] += delta;
                    weightedConflicts[cell] += weightedDelta;
                    weightedConflicts[neighbor] += weightedDelta;
                    illegalEdges += delta;
                    refreshConflictedMembership(neighbor);
                }
            }
            refreshConflictedMembership(cell);

            usesOfBiome[oldValue]--;
            if (required[oldValue] && usesOfBiome[oldValue] == 0) {
                missingBiomes++;
            }
            usesOfBiome[newValue]++;
            if (required[newValue] && usesOfBiome[newValue] == 1) {
                missingBiomes--;
            }
            value[cell] = newValue;
            tabuUntilStep[cell * biomeCount + oldValue] = step + TABU_TENURE_BASE + random.nextInt(TABU_TENURE_SPREAD);
        }

        private void refreshConflictedMembership(int cell) {
            boolean shouldBeMember = violations[cell] > 0;
            boolean isMember = conflictedSlot[cell] >= 0;
            if (shouldBeMember && !isMember) {
                addConflicted(cell);
            } else if (!shouldBeMember && isMember) {
                removeConflicted(cell);
            }
        }

        private void addConflicted(int cell) {
            conflictedSlot[cell] = conflictedSize;
            conflictedCells[conflictedSize++] = cell;
        }

        private void removeConflicted(int cell) {
            int slot = conflictedSlot[cell];
            int lastCell = conflictedCells[--conflictedSize];
            conflictedCells[slot] = lastCell;
            conflictedSlot[lastCell] = slot;
            conflictedSlot[cell] = -1;
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
