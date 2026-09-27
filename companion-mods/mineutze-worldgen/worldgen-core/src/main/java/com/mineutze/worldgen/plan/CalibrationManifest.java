package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.MinimalJson;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Which tuning constants (2026-09-26) were empirically validated TOGETHER, against what, and how -
 * read from the hand-maintained {@code /calibration/calibration-manifest.json} resource. This is
 * current-state truth ("is this still valid, right now"), not history: {@code DECISIONS.md} keeps
 * the narrative (context, alternatives rejected, evidence at the time) unchanged; this manifest is
 * the complementary artifact {@link CalibrationManifestTest} checks the real code and catalog
 * against, and {@link CalibrationReportRunner} prints in one command instead of a document search.
 */
final class CalibrationManifest {

    /** One constant this manifest tracks, keyed by where it lives in the real code (not enforced here - see {@link CalibrationConstants}). */
    record RecordedConstant(String owner, String field, Object recordedValue) {
    }

    /** A cluster of constants calibrated together, against one real-scale check or another (see {@code worldgen-core/build.gradle}'s verification tasks). */
    record Group(
        String name, String description, List<RecordedConstant> constants, String validatedOn, String validatedAgainstCatalogFingerprint,
        List<String> verifiedByGradleTasks, String decisionsEntry, String evidenceSummary
    ) {
    }

    private final List<Group> groups;

    private CalibrationManifest(List<Group> groups) {
        this.groups = groups;
    }

    static CalibrationManifest load() {
        return new CalibrationManifest(parseGroups(readResource()));
    }

    List<Group> groups() {
        return groups;
    }

    private static String readResource() {
        try (InputStream in = CalibrationManifest.class.getResourceAsStream("/calibration/calibration-manifest.json")) {
            if (in == null) {
                throw new IllegalStateException("CalibrationManifest: resource /calibration/calibration-manifest.json not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("CalibrationManifest: could not read the manifest resource", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Group> parseGroups(String json) {
        Map<String, Object> root = (Map<String, Object>) MinimalJson.parse(json);
        List<Group> groups = new ArrayList<>();
        for (Object rawGroup : (List<Object>) root.get("groups")) {
            groups.add(parseGroup((Map<String, Object>) rawGroup));
        }
        return groups;
    }

    @SuppressWarnings("unchecked")
    private static Group parseGroup(Map<String, Object> rawGroup) {
        List<RecordedConstant> constants = new ArrayList<>();
        for (Object rawConstant : (List<Object>) rawGroup.get("constants")) {
            constants.add(parseConstant((Map<String, Object>) rawConstant));
        }
        List<String> verifiedByGradleTasks = new ArrayList<>();
        for (Object task : (List<Object>) rawGroup.get("verifiedByGradleTasks")) {
            verifiedByGradleTasks.add((String) task);
        }
        return new Group(
            (String) rawGroup.get("name"), (String) rawGroup.get("description"), constants, (String) rawGroup.get("validatedOn"),
            (String) rawGroup.get("validatedAgainstCatalogFingerprint"), verifiedByGradleTasks, (String) rawGroup.get("decisionsEntry"),
            (String) rawGroup.get("evidenceSummary")
        );
    }

    private static RecordedConstant parseConstant(Map<String, Object> rawConstant) {
        return new RecordedConstant((String) rawConstant.get("owner"), (String) rawConstant.get("field"), rawConstant.get("recordedValue"));
    }
}
