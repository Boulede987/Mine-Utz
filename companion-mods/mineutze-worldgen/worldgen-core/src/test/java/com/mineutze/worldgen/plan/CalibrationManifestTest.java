package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Fails loud the moment a tracked constant's real value, or the real catalog, drifts from what
 * {@code calibration-manifest.json} still records - so nobody discovers a stale calibration only
 * when a long-running verification task breaks, or worse, not at all. Runs in the ordinary fast
 * test suite, not the long verification tasks it references.
 */
class CalibrationManifestTest {

    @Test
    void everyTrackedConstantMatchesItsLiveValue() {
        Map<String, Map<String, Object>> liveGroups = CalibrationConstants.allGroups();
        CalibrationManifest manifest = CalibrationManifest.load();
        List<String> mismatches = new ArrayList<>();
        for (CalibrationManifest.Group group : manifest.groups()) {
            checkGroup(group, liveGroups.get(group.name()), mismatches);
        }
        assertTrue(mismatches.isEmpty(), () -> "CalibrationManifestTest: tuning has drifted from calibration-manifest.json:\n" + String.join("\n", mismatches));
    }

    private void checkGroup(CalibrationManifest.Group group, Map<String, Object> live, List<String> mismatches) {
        for (CalibrationManifest.RecordedConstant constant : group.constants()) {
            checkConstant(group, constant, live, mismatches);
        }
    }

    private void checkConstant(CalibrationManifest.Group group, CalibrationManifest.RecordedConstant constant, Map<String, Object> live, List<String> mismatches) {
        String key = constant.owner() + "." + constant.field();
        Object liveValue = live.get(key);
        if (!constant.recordedValue().equals(liveValue)) {
            mismatches.add(
                key + " is " + liveValue + " in code but the manifest records " + constant.recordedValue() + " for group '" + group.name() + "', last validated "
                    + group.validatedOn() + " against " + group.verifiedByGradleTasks() + " - re-run those tasks, write a DECISIONS.md entry, then update the manifest."
            );
        }
    }

    @Test
    void everyGroupsCatalogFingerprintMatchesTheRealCatalog() {
        BiomeGraphData catalog = BiomeGraphJson.loadResource(RawRuleTable.class, "/biomegraph/biome-graph.json");
        String liveFingerprint = WorldBiomePlan.fingerprintOf(catalog);
        for (CalibrationManifest.Group group : CalibrationManifest.load().groups()) {
            assertGroupFingerprintMatches(group, liveFingerprint);
        }
    }

    private void assertGroupFingerprintMatches(CalibrationManifest.Group group, String liveFingerprint) {
        assertEquals(
            group.validatedAgainstCatalogFingerprint(), liveFingerprint,
            () -> "CalibrationManifestTest: group '" + group.name() + "' was validated against a different catalog (fingerprint " + group.validatedAgainstCatalogFingerprint()
                + ") than the one on the classpath now (" + liveFingerprint + "). Re-run " + group.verifiedByGradleTasks()
                + " against the current catalog, write a DECISIONS.md entry, then update the manifest's validatedAgainstCatalogFingerprint."
        );
    }
}
