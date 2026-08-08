package com.mineutze.worldgen.regionsolve.mesh;

import com.mineutze.worldgen.biomegraph.BiomeId;

import java.util.Map;
import java.util.Set;

/**
 * @param faceIdentity mutable — later passes (gap-fill, displacement) keep writing into it
 * @param fixedFaceIds faces committed directly from an already-solved neighbor region's
 *                      boundary context ({@code PatchMerger.seedFixedGerms}) — ground
 *                      truth this solve never gets to revise. Every later pass that
 *                      reassigns or drops a face (mesh analog of {@code GapFiller}'s
 *                      "never touches a cell outside regionCellSet") must check this
 *                      set first, mirroring the pixel pipeline's own boundary discipline.
 */
public record MeshAssignment(Map<Integer, BiomeId> faceIdentity, Set<Integer> fixedFaceIds) {
}
