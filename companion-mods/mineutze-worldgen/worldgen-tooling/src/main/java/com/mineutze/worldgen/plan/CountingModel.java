package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;
import org.chocosolver.solver.Model;
import org.chocosolver.solver.Solver;
import org.chocosolver.solver.variables.IntVar;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Counting relaxation of the coarse pattern problem, solved exactly by Choco: cells, patches, cell-edges per biome
 * pair, perimeter bounds, required borders, and map corners. CONTRADICTION = proof no coarse pattern exists, so no
 * world. Sound only while every rule here is implied by the coarse solver's own rules (see each post method's comment);
 * adding a rule the solver does not enforce would turn a solvable graph into a false INFEASIBLE.
 */
final class CountingModel {

    private static final Duration CHECK_TIME_LIMIT = Duration.ofSeconds(10);
    /** Hex cell has 6 edges. */
    private static final int EDGES_PER_CELL = 6;

    private final List<BiomeId> biomes;
    private final Map<BiomeId, Integer> indexOfBiome;
    private final int[] minCells;
    private final int[] maxCells;
    private final int[] perimeterCeilingPerPatch;
    private final int gridSide;
    private final int gridCells;

    private CountingModel(List<BiomeId> biomes, int[] minCells, int[] maxCells, int[] perimeterCeilingPerPatch, int gridSide) {
        this.biomes = List.copyOf(biomes);
        this.indexOfBiome = new HashMap<>();
        for (int index = 0; index < biomes.size(); index++) {
            indexOfBiome.put(biomes.get(index), index);
        }
        this.minCells = minCells;
        this.maxCells = maxCells;
        this.perimeterCeilingPerPatch = perimeterCeilingPerPatch;
        this.gridSide = gridSide;
        this.gridCells = gridSide * gridSide;
    }

    static CountingModel of(RawRuleTable table, PlanSettings settings) {
        ClusterSizeModel sizes = MultigridPatternGenerator.coarseModelOf(table, settings);
        List<BiomeId> biomes = table.allBiomeIds();
        int[] minCells = new int[biomes.size()];
        int[] maxCells = new int[biomes.size()];
        int[] ceiling = new int[biomes.size()];
        for (int index = 0; index < biomes.size(); index++) {
            BiomeId biome = biomes.get(index);
            minCells[index] = sizes.minCells(biome);
            maxCells[index] = sizes.maxCells(biome);
            if (minCells[index] < 1) {
                throw new IllegalArgumentException("CountingModel: " + biome + " has a minimum of " + minCells[index] + " cells per patch; a patch has at least 1");
            }
            ceiling[index] = sizes.shape(biome).allowedPerimeter(maxCells[index]);
        }
        return new CountingModel(biomes, minCells, maxCells, ceiling, settings.gridSize());
    }

    /** SATISFIABLE / CONTRADICTION are exact for the active laws; UNKNOWN = time limit hit, never a proof. */
    CountingStatus check(Set<GraphLaw> active) {
        boolean[] everyBiome = new boolean[biomes.size()];
        Arrays.fill(everyBiome, true);
        return solve(new Build(active, everyBiome).model);
    }

    /**
     * Same rules, variables built for {@code focus} only (others' cells, borders, corners left free): a relaxation, so
     * CONTRADICTION is still a proof. Much cheaper than {@link #check} - for laws that all concern {@code focus}.
     */
    CountingStatus checkFocused(Set<GraphLaw> active, BiomeId focus) {
        boolean[] onlyFocus = new boolean[biomes.size()];
        onlyFocus[indexOf(focus)] = true;
        return solve(new Build(active, onlyFocus).model);
    }

    private static CountingStatus solve(Model model) {
        Solver solver = model.getSolver();
        solver.limitTime(CHECK_TIME_LIMIT.toMillis());
        if (solver.solve()) {
            return CountingStatus.SATISFIABLE;
        }
        return solver.isStopCriterionMet() ? CountingStatus.UNKNOWN : CountingStatus.CONTRADICTION;
    }

    private int indexOf(BiomeId biome) {
        Integer index = indexOfBiome.get(biome);
        if (index == null) {
            throw new IllegalArgumentException("CountingModel: law names biome " + biome + ", not in this graph");
        }
        return index;
    }

    /** One Choco model for one set of active laws. */
    private final class Build {

        private final Model model = new Model();
        private final int count = biomes.size();
        private final boolean[] sized = new boolean[count];
        private final boolean[][] forbidden = new boolean[count][count];
        private final List<RequiredBorderLaw> requirements = new ArrayList<>();
        private final IntVar[] patches = new IntVar[count];
        private final IntVar[] cells = new IntVar[count];
        private final IntVar[] perimeter = new IntVar[count];
        private final IntVar[][] edges = new IntVar[count][count];
        private final boolean[] modelled;
        private final boolean everyBiomeModelled;

        Build(Set<GraphLaw> active, boolean[] modelled) {
            this.modelled = modelled;
            this.everyBiomeModelled = allTrue(modelled);
            active.forEach(this::classify);
            declarePatchesAndCells();
            declareEdgesAndPerimeters();
            for (int biome = 0; biome < count; biome++) {
                postSizeAndPerimeterBounds(biome);
            }
            postIllegalPairs();
            requirements.stream().filter(law -> modelled[indexOf(law.biome())]).forEach(this::postRequirement);
            new Corners(this).post();
        }

        private static boolean allTrue(boolean[] flags) {
            for (boolean flag : flags) {
                if (!flag) {
                    return false;
                }
            }
            return true;
        }

        private int outerGridCells() {
            return gridCells;
        }

        private boolean pairModelled(int first, int second) {
            return modelled[first] || modelled[second];
        }

        private void classify(GraphLaw law) {
            switch (law) {
                case SizeRangeLaw size -> sized[indexOf(size.biome())] = true;
                case IllegalPairLaw pair -> markForbidden(indexOf(pair.first()), indexOf(pair.second()));
                case RequiredBorderLaw required -> requirements.add(required);
            }
        }

        private void markForbidden(int first, int second) {
            forbidden[first][second] = true;
            forbidden[second][first] = true;
        }

        /** Every biome present (production: missing-biome cost must be 0), so at least 1 patch; cells sum to the grid. */
        private void declarePatchesAndCells() {
            for (int biome = 0; biome < count; biome++) {
                if (!modelled[biome]) {
                    continue;
                }
                int lowestCells = sized[biome] ? minCells[biome] : 1;
                int patchCap = gridCells / lowestCells;
                patches[biome] = model.intVar("patches" + biome, 1, patchCap);
                int highestCells = sized[biome] ? (int) Math.min(gridCells, (long) patchCap * maxCells[biome]) : gridCells;
                cells[biome] = model.intVar("cells" + biome, lowestCells, highestCells);
            }
            if (everyBiomeModelled) {
                model.sum(cells, "=", gridCells).post();
            }
        }

        /** Hex edge identity: 6 * cells = 2 * internal edges + perimeter; perimeter = edges shared with every other biome. */
        private void declareEdgesAndPerimeters() {
            for (int first = 0; first < count; first++) {
                for (int second = first + 1; second < count; second++) {
                    if (!pairModelled(first, second)) {
                        continue;
                    }
                    IntVar shared = model.intVar("edges" + first + "_" + second, 0, EDGES_PER_CELL * gridCells / 2);
                    edges[first][second] = shared;
                    edges[second][first] = shared;
                }
            }
            for (int biome = 0; biome < count; biome++) {
                if (modelled[biome]) {
                    postPerimeterIdentity(biome);
                }
            }
        }

        private void postPerimeterIdentity(int biome) {
            List<IntVar> touching = new ArrayList<>();
            for (int other = 0; other < count; other++) {
                if (other != biome) {
                    touching.add(edges[biome][other]);
                }
            }
            perimeter[biome] = model.intVar("perimeter" + biome, 0, EDGES_PER_CELL * gridCells);
            model.sum(touching.toArray(IntVar[]::new), "=", perimeter[biome]).post();
            IntVar internal = model.intVar("internal" + biome, 0, EDGES_PER_CELL * gridCells / 2);
            model.scalar(new IntVar[] {cells[biome], perimeter[biome], internal}, new int[] {EDGES_PER_CELL, -1, -2}, "=", 0).post();
        }

        /**
         * Production checks size and perimeter per patch: minCells..maxCells cells, perimeter <= allowedPerimeter(cells)
         * <= allowedPerimeter(maxCells). Floor minimumPerimeter(minCells): coarse rules have no floor, so it must be a
         * geometric fact - true only for a patch that cannot wrap the torus. Axial hex torus (ToroidalGrid wraps q mod
         * width, r mod height): shortest non-contractible cell cycle = grid side, so a patch of < grid side cells is a
         * contractible region, and its perimeter (outer boundary + holes) >= minimumPerimeter(its filled area) >=
         * minimumPerimeter(minCells). A wrapping band beats the flat minimum (see ShapeRule.requiredMinimumPerimeter).
         */
        private void postSizeAndPerimeterBounds(int biome) {
            if (!sized[biome] || !modelled[biome]) {
                return;
            }
            model.scalar(new IntVar[] {cells[biome], patches[biome]}, new int[] {1, -minCells[biome]}, ">=", 0).post();
            model.scalar(new IntVar[] {cells[biome], patches[biome]}, new int[] {1, -maxCells[biome]}, "<=", 0).post();
            if (perimeterCeilingPerPatch[biome] != Integer.MAX_VALUE) {
                model.scalar(new IntVar[] {perimeter[biome], patches[biome]}, new int[] {1, -perimeterCeilingPerPatch[biome]}, "<=", 0).post();
            }
            if (maxCells[biome] < gridSide) {
                model.scalar(new IntVar[] {perimeter[biome], patches[biome]}, new int[] {1, -ShapeRule.minimumPerimeter(minCells[biome])}, ">=", 0).post();
            }
        }

        private void postIllegalPairs() {
            for (int first = 0; first < count; first++) {
                for (int second = first + 1; second < count; second++) {
                    if (forbidden[first][second] && pairModelled(first, second)) {
                        model.arithm(edges[first][second], "=", 0).post();
                    }
                }
            }
        }

        /** Per patch: shared edges >= minEdges with the named biomes. Summed over patches. Any-of: each patch meets one part. */
        private void postRequirement(RequiredBorderLaw law) {
            postRequirementPart(indexOf(law.biome()), law.requirement());
        }

        private void postRequirementPart(int biome, ResolvedBorderRequirement requirement) {
            switch (requirement) {
                case ResolvedBorderRequirement.Share share -> postShares(biome, List.of(share));
                case ResolvedBorderRequirement.AllOf all -> all.parts().forEach(part -> postRequirementPart(biome, part));
                case ResolvedBorderRequirement.AnyOf any -> postShares(biome, alternativesOf(any));
            }
        }

        /** Each patch meets at least one share: sum over shares of weighted edges >= weighted patches (see ShareWeights). */
        private void postShares(int biome, List<ResolvedBorderRequirement.Share> shares) {
            if (shares.stream().anyMatch(share -> share.minEdges() <= 0)) {
                return;
            }
            long edgeBound = EDGES_PER_CELL * gridCells / 2;
            long maxEdgesPerShare = shares.stream().mapToLong(share -> share.biomes().size() * edgeBound).max().orElseThrow();
            List<Integer> minimums = shares.stream().map(ResolvedBorderRequirement.Share::minEdges).toList();
            ShareWeights.of(minimums, maxEdgesPerShare, patches[biome].getUB()).ifPresent(weights -> postWeightedShares(biome, shares, weights));
        }

        private void postWeightedShares(int biome, List<ResolvedBorderRequirement.Share> shares, ShareWeights weights) {
            List<IntVar> terms = new ArrayList<>();
            List<Integer> coefficients = new ArrayList<>();
            for (int index = 0; index < shares.size(); index++) {
                addShareTerms(biome, shares.get(index), weights.shareWeights().get(index), terms, coefficients);
            }
            terms.add(patches[biome]);
            coefficients.add(-weights.patchWeight());
            model.scalar(terms.toArray(IntVar[]::new), coefficients.stream().mapToInt(Integer::intValue).toArray(), ">=", 0).post();
        }

        private void addShareTerms(int biome, ResolvedBorderRequirement.Share share, int weight, List<IntVar> terms, List<Integer> coefficients) {
            for (BiomeId partner : share.biomes()) {
                terms.add(edges[biome][indexOf(partner)]);
                coefficients.add(weight);
            }
        }
    }

    /**
     * Shares of which every patch meeting {@code requirement} meets at least one: a share itself; for any-of, the union
     * over its parts; for all-of, its first part's alternatives (all-of implies each part). Always implied - never stronger.
     */
    private static List<ResolvedBorderRequirement.Share> alternativesOf(ResolvedBorderRequirement requirement) {
        return switch (requirement) {
            case ResolvedBorderRequirement.Share share -> List.of(share);
            case ResolvedBorderRequirement.AllOf all -> alternativesOf(all.parts().get(0));
            case ResolvedBorderRequirement.AnyOf any -> any.parts().stream().flatMap(part -> alternativesOf(part).stream()).toList();
        };
    }

    /**
     * Map topology, per pair (O(n^2) variables; per-triple form too slow, see DECISIONS.md 2026-10-31). Patches are
     * regions of a map whose vertices have degree 3 (hex grid); a vertex joins 3 pairwise touching patches = 3 distinct
     * biomes, pairwise not forbidden. A border segment between a and b is a closed loop or has 2 ends, each at a vertex
     * whose third biome is legal with both - so no such third biome means loops only. Each vertex on a patch of b ends
     * 2 of b's segments, so b's vertex count = its open segments. A patch not enclosed by loops has >= 2 vertices -
     * except a patch with no border at all, which covers the whole torus: possible only with a single biome, so skipped then.
     */
    private record Corners(Build build) {

        void post() {
            int count = build.count;
            if (count == 1) {
                return;
            }
            IntVar[][] openSegments = new IntVar[count][count];
            List<IntVar>[] loopsAround = newLists(count);
            for (int first = 0; first < count; first++) {
                for (int second = first + 1; second < count; second++) {
                    if (!build.pairModelled(first, second)) {
                        continue;
                    }
                    IntVar open = postSegments(first, second, loopsAround);
                    openSegments[first][second] = open;
                    openSegments[second][first] = open;
                }
            }
            for (int biome = 0; biome < count; biome++) {
                if (build.modelled[biome]) {
                    postPatchVertices(biome, openSegments[biome], loopsAround[biome]);
                }
            }
        }

        private boolean hasLegalThird(int first, int second) {
            boolean[][] forbidden = build.forbidden;
            for (int third = 0; third < build.count; third++) {
                if (third != first && third != second && !forbidden[first][third] && !forbidden[second][third]) {
                    return true;
                }
            }
            return false;
        }

        private IntVar postSegments(int first, int second, List<IntVar>[] loopsAround) {
            Model model = build.model;
            int bound = EDGES_PER_CELL * build.outerGridCells() / 2;
            IntVar shared = build.edges[first][second];
            IntVar segments = model.intVar("segments" + first + "_" + second, 0, bound);
            IntVar loops = model.intVar("loops" + first + "_" + second, 0, bound);
            model.arithm(segments, "<=", shared).post();
            model.arithm(loops, "<=", segments).post();
            model.ifThen(model.arithm(shared, ">", 0), model.arithm(segments, ">=", 1));
            IntVar open = model.intVar("open" + first + "_" + second, 0, bound);
            model.arithm(open, "=", segments, "-", loops).post();
            if (build.forbidden[first][second] || !hasLegalThird(first, second)) {
                model.arithm(open, "=", 0).post();
            }
            loopsAround[first].add(loops);
            loopsAround[second].add(loops);
            return open;
        }

        private void postPatchVertices(int biome, IntVar[] openSegmentsOfBiome, List<IntVar> loopsAround) {
            Model model = build.model;
            int gridCells = build.outerGridCells();
            List<IntVar> open = new ArrayList<>();
            for (int other = 0; other < build.count; other++) {
                if (other != biome) {
                    open.add(openSegmentsOfBiome[other]);
                }
            }
            IntVar enclosedByLoops = model.intVar("enclosed" + biome, 0, gridCells);
            model.arithm(enclosedByLoops, "<=", build.patches[biome]).post();
            model.sum(loopsAround.toArray(IntVar[]::new), ">=", enclosedByLoops).post();
            IntVar vertices = model.intVar("vertices" + biome, 0, EDGES_PER_CELL * gridCells);
            model.sum(open.toArray(IntVar[]::new), "=", vertices).post();
            model.scalar(new IntVar[] {vertices, build.patches[biome], enclosedByLoops}, new int[] {1, -2, 2}, ">=", 0).post();
        }

        @SuppressWarnings("unchecked")
        private static List<IntVar>[] newLists(int count) {
            List<IntVar>[] lists = new List[count];
            for (int index = 0; index < count; index++) {
                lists[index] = new ArrayList<>();
            }
            return lists;
        }
    }
}
