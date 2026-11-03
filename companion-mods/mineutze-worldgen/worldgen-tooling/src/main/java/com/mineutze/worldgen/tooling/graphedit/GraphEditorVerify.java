package com.mineutze.worldgen.tooling.graphedit;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.plan.RankingUnavailable;
import com.mineutze.worldgen.plan.TightnessVerify;
import com.mineutze.worldgen.plan.WorldBiomePlan;

import java.util.List;
import java.util.Optional;

/**
 * Graph editor's "Verify" button. Prints {@code VERIFY: <status>} for the editor UI - a measurement has no pass/fail.
 * Own JVM like the Solve check: uses every core.
 */
final class GraphEditorVerify {

    private static final int EXIT_NO_RANKING = 2;
    /** Picks refused at run time: the ranking changed after the server's check (another editor's Solve check). */
    private static final int EXIT_REFUSED = 2;
    private static final int EXIT_CRASH = 3;

    private GraphEditorVerify() {
    }

    /** One argument {@code 1,4,7:<fingerprint>} = those picks; none = top 3 loosenable. @throws IllegalArgumentException anything else */
    static Optional<VerifyRequest> requestFrom(String[] args) {
        if (args.length == 0) {
            return Optional.empty();
        }
        if (args.length > 1) {
            throw new IllegalArgumentException("GraphEditorVerify: expected one argument like 1,4,7:<fingerprint>, got " + List.of(args));
        }
        return Optional.of(VerifyRequest.fromArgument(args[0]));
    }

    public static void main(String[] args) {
        try {
            Optional<VerifyRequest> request = requestFrom(args);
            BiomeGraphData catalog = BiomeGraphJson.load(GraphEditorFiles.DATA_FILE);
            String report = measure(request, catalog);
            System.out.println("VERIFY: MEASURED");
            System.out.println(report);
        } catch (IllegalArgumentException refused) {
            System.out.println("VERIFY: REFUSED");
            System.out.println(refused.getMessage());
            System.exit(EXIT_REFUSED);
        } catch (RankingUnavailable missing) {
            System.out.println("VERIFY: NO RANKING");
            System.out.println(missing.getMessage());
            System.exit(EXIT_NO_RANKING);
        } catch (RuntimeException | Error crash) {
            System.out.println("VERIFY: CRASH");
            System.out.println("Verify crashed (graph " + GraphEditorFiles.DATA_FILE.toAbsolutePath() + ", ranking " + GraphEditorFiles.TIGHTNESS_RANKING.toAbsolutePath() + "):");
            crash.printStackTrace(System.out);
            System.exit(EXIT_CRASH);
        }
    }

    private static String measure(Optional<VerifyRequest> request, BiomeGraphData catalog) {
        int threads = Runtime.getRuntime().availableProcessors();
        WorldBiomePlan.ProgressListener progress = message -> System.out.println("  " + message);
        if (request.isEmpty()) {
            return TightnessVerify.verify(GraphEditorFiles.TIGHTNESS_RANKING, catalog, threads, progress);
        }
        return TightnessVerify.verify(GraphEditorFiles.TIGHTNESS_RANKING, catalog, request.get().ranks(), request.get().fingerprint(), threads, progress);
    }
}
