package com.mineutze.worldgen.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The live value of every constant {@link CalibrationManifest} tracks, read by DIRECT field
 * reference so renaming or deleting a tracked constant breaks THIS class's own compilation - the
 * one enforcement point that catches structural drift; {@link CalibrationManifestTest} catches
 * value drift on top of that. Every value is normalized to {@link Double}, {@link String} or a
 * {@code List<Double>} so it compares directly (via plain {@code equals}) against
 * {@link com.mineutze.worldgen.biomegraph.MinimalJson}'s own parsed shape, with no per-field
 * special-casing in the test that compares them.
 */
final class CalibrationConstants {

    private CalibrationConstants() {
    }

    static Map<String, Map<String, Object>> allGroups() {
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        groups.put("identity-and-size-search", identityAndSizeSearchGroup());
        groups.put("shape-and-realization", shapeAndRealizationGroup());
        return groups;
    }

    private static Map<String, Object> identityAndSizeSearchGroup() {
        Map<String, Object> live = new LinkedHashMap<>();
        live.put("PlanSettings.DEFAULT.gridSize", (double) PlanSettings.DEFAULT.gridSize());
        live.put("PlanSettings.DEFAULT.atomWidthBlocks", PlanSettings.DEFAULT.atomWidthBlocks());
        live.put("PlanSettings.DEFAULT.perimeterRatio", PlanSettings.DEFAULT.perimeterRatio());
        live.put("ToroidalSizedPatternGenerator.DEFAULT_STEPS_PER_SEED", (double) ToroidalSizedPatternGenerator.DEFAULT_STEPS_PER_SEED);
        live.put("ToroidalSizedPatternGenerator.DEFAULT_MAX_SEEDS", (double) ToroidalSizedPatternGenerator.DEFAULT_MAX_SEEDS);
        live.put("ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE.highTemperature", ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE.highTemperature());
        live.put("ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE.lowTemperature", ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE.lowTemperature());
        live.put("ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE.cycleSteps", (double) ToroidalSizedPatternGenerator.DEFAULT_SCHEDULE.cycleSteps());
        live.put("ToroidalSizedLocalSearch.DEFAULT_RELABEL_PROBABILITY", ToroidalSizedLocalSearch.DEFAULT_RELABEL_PROBABILITY);
        live.put("ToroidalSizedLocalSearch.DEFAULT_TEMPERATURE", ToroidalSizedLocalSearch.DEFAULT_TEMPERATURE);
        live.put("ToroidalSizedLocalSearch.NUCLEATION_PROBABILITY", ToroidalSizedLocalSearch.NUCLEATION_PROBABILITY);
        live.put("ToroidalSizedLocalSearch.MISSING_NUCLEATION_SHARE", ToroidalSizedLocalSearch.MISSING_NUCLEATION_SHARE);
        live.put("ToroidalSizedLocalSearch.MISSING_BIOME_COST", (double) ToroidalSizedLocalSearch.MISSING_BIOME_COST);
        live.put("ToroidalSizedLocalSearch.FOCUS_PROBABILITY", ToroidalSizedLocalSearch.FOCUS_PROBABILITY);
        live.put("ToroidalSizedLocalSearch.ACCEPTANCE_TABLE_SIZE", (double) ToroidalSizedLocalSearch.ACCEPTANCE_TABLE_SIZE);
        live.put("ToroidalSizedLocalSearch.SCHEDULE_LEVELS", (double) ToroidalSizedLocalSearch.SCHEDULE_LEVELS);
        live.put("ToroidalSizedLocalSearch.BEST_TRACKING_INTERVAL_STEPS", (double) ToroidalSizedLocalSearch.BEST_TRACKING_INTERVAL_STEPS);
        return live;
    }

    private static Map<String, Object> shapeAndRealizationGroup() {
        Map<String, Object> live = new LinkedHashMap<>();
        live.put("PlanSettings.DEFAULT.warpStrengthsStrongestFirst", boxed(PlanSettings.DEFAULT.warpStrengthsStrongestFirst()));
        live.put("PlanSettings.DEFAULT.rasterSamplesPerSide", (double) PlanSettings.DEFAULT.rasterSamplesPerSide());
        live.put("PlanSettings.DEFAULT.sizeToleranceOfMinimum", PlanSettings.DEFAULT.sizeToleranceOfMinimum());
        live.put("RealizationSelector.WAVE_COUNT", (double) RealizationSelector.WAVE_COUNT);
        live.put("RealizationSelector.SHORTEST_WAVELENGTH_BLOCKS", RealizationSelector.SHORTEST_WAVELENGTH_BLOCKS);
        live.put("RealizationSelector.LONGEST_WAVELENGTH_BLOCKS", RealizationSelector.LONGEST_WAVELENGTH_BLOCKS);
        live.put("RealizationSelector.ROUGHNESS", RealizationSelector.ROUGHNESS);
        live.put("RealizationSelector.FLOW_STEPS", (double) RealizationSelector.FLOW_STEPS);
        live.put("RealizationSelector.REALIZED_BORDER_FRACTION", RealizationSelector.REALIZED_BORDER_FRACTION);
        return live;
    }

    private static List<Double> boxed(double[] values) {
        List<Double> boxed = new ArrayList<>(values.length);
        for (double value : values) {
            boxed.add(value);
        }
        return boxed;
    }
}
