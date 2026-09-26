package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;

import java.util.Map;

/**
 * Answers "how is X tuned, where, against what, is it still valid" in one instant command (no
 * world solving) instead of a document search (2026-09-26): prints {@link CalibrationManifest}'s
 * groups against {@link CalibrationConstants}'s live values and the real catalog's current
 * fingerprint. {@link CalibrationManifestTest} is what actually enforces this stays true; this
 * runner only reports it.
 */
public final class CalibrationReportRunner {

    private CalibrationReportRunner() {
    }

    public static void main(String[] args) {
        BiomeGraphData catalog = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        String liveFingerprint = WorldBiomePlan.fingerprintOf(catalog);
        Map<String, Map<String, Object>> liveGroups = CalibrationConstants.allGroups();
        CalibrationManifest manifest = CalibrationManifest.load();

        for (CalibrationManifest.Group group : manifest.groups()) {
            reportGroup(group, liveGroups.get(group.name()), liveFingerprint);
        }
    }

    private static void reportGroup(CalibrationManifest.Group group, Map<String, Object> live, String liveFingerprint) {
        System.out.println();
        System.out.println("GROUP " + group.name() + ": " + group.description());
        System.out.println("  validated " + group.validatedOn() + ", see DECISIONS.md: \"" + group.decisionsEntry() + "\"");
        System.out.println("  evidence: " + group.evidenceSummary());
        System.out.println("  verified by: " + String.join(", ", group.verifiedByGradleTasks()));
        System.out.println("  catalog fingerprint: " + fingerprintStatus(group, liveFingerprint));
        for (CalibrationManifest.RecordedConstant constant : group.constants()) {
            reportConstant(constant, live);
        }
    }

    private static String fingerprintStatus(CalibrationManifest.Group group, String liveFingerprint) {
        return liveFingerprint.equals(group.validatedAgainstCatalogFingerprint())
            ? "OK"
            : "STALE (recorded " + group.validatedAgainstCatalogFingerprint() + ", live " + liveFingerprint + ")";
    }

    private static void reportConstant(CalibrationManifest.RecordedConstant constant, Map<String, Object> live) {
        String key = constant.owner() + "." + constant.field();
        Object liveValue = live.get(key);
        boolean matches = constant.recordedValue().equals(liveValue);
        System.out.println(
            "  " + (matches ? "MATCH  " : "DRIFTED") + " " + key + " = " + liveValue + (matches ? "" : " (manifest records " + constant.recordedValue() + ")")
        );
    }
}
