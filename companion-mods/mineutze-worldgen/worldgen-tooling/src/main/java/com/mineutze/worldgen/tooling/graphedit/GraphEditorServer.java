package com.mineutze.worldgen.tooling.graphedit;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.GroupData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphJson;
import com.mineutze.worldgen.biomegraph.BorderRequirement;
import com.mineutze.worldgen.biomegraph.MinimalJson;
import com.mineutze.worldgen.plan.RankingChoice;
import com.mineutze.worldgen.plan.RankingUnavailable;
import com.mineutze.worldgen.plan.RankingView;
import com.mineutze.worldgen.plan.TightnessVerify;
import com.mineutze.worldgen.tooling.graphedit.GraphValidation.ValidationResult;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * Graph editor vertical slice (2026-09-14, real user request - see DECISIONS.md same
 * date): local web app, embedded {@link HttpServer} (JDK-bundled, zero new dependency,
 * matches {@code worldgen-core}'s own no-dependency discipline) + a single-file browser
 * UI. Run with {@code ./gradlew :worldgen-tooling:graphEditor}, then open
 * http://localhost:8842 - Ctrl+C to stop.
 *
 * <p><b>POC scope, deliberately narrow</b> (real user decision: "make a vertical slice, a
 * POC" after choosing data-file-backed graph (see {@link BiomeGraphData}'s own doc) + a
 * local web app over a CLI or JavaFX desktop tool): add ONE biome or ONE adjacency at a
 * time, live warnings reusing {@link GraphValidation} (itself reusing this session's own
 * real {@link com.mineutze.worldgen.biomegraph.BiomeGraph} construction checks), a button that
 * launches this repo's own real tests, and a "Solve check" button that builds real worlds
 * from the saved graph. Two view modes (2026-09-14, real user feedback: the full-graph
 * overview was "unreadable" for real editing) - OVERVIEW (every node/edge, bird's-eye
 * only) and FOCUS (pick one biome, hub-and-spoke re-center to ONLY its own real
 * neighbors/edges - the actual working view). Adjacencies can be removed too (2026-09-15,
 * real user need - a "Remove" button per adjacency in FOCUS view's own list), re-validated
 * before saving same as adding one (removing an edge isn't automatically safe - see {@link
 * #handleRemoveEdge}'s own doc).
 *
 * <p><b>This IS production's real data now</b> (2026-09-15, real user request: "wire the
 * tool in the actual mod, to make it modify the graph that will be used in production"):
 * {@link GraphEditorFiles#DATA_FILE} lives under {@code src/main/resources}, the exact
 * file {@link com.mineutze.worldgen.biomegraph.ModBiomeGraph#OVERWORLD} loads via {@link
 * BiomeGraphJson#loadResource} - editing here and rebuilding the mod IS how the shipped
 * catalog changes now, no separate promotion step. Real consequence: since production
 * uses {@link com.mineutze.worldgen.biomegraph.BiomeGraph#forProduction} (which THIS
 * tool's own live warnings deliberately do NOT hard-enforce - see {@link
 * GraphValidation}'s own doc on why under-connection is an error, not a save-blocker), a
 * biome saved here with real errors (e.g. under-connected) will make the MOD ITSELF fail
 * to start until fixed - the user's own explicit, informed choice ("wire it now, let it
 * fail loud") over fixing existing data first.
 *
 * <p>An existing biome's size range can be edited in FOCUS view (2026-09-19, real user
 * request - see {@link #handleUpdateNodeSize}). NOT in this slice: removing a BIOME (node),
 * editing its other properties, editing an existing adjacency's blend width, dragging
 * nodes, a real force-directed layout.
 */
public final class GraphEditorServer {

    private static final int DEFAULT_PORT = 8842;

    public static void main(String[] args) throws IOException {
        if (!java.nio.file.Files.exists(GraphEditorFiles.DATA_FILE)) {
            throw new IllegalStateException(
                "GraphEditorServer: " + GraphEditorFiles.DATA_FILE.toAbsolutePath() + " doesn't exist - it is the shipped catalog, restore it from git"
            );
        }
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", GraphEditorServer::serveIndex);
        server.createContext("/api/graph", GraphEditorServer::serveGraph);
        server.createContext("/api/nodes", GraphEditorServer::handleAddNode);
        server.createContext("/api/nodes/size", GraphEditorServer::handleUpdateNodeSize);
        server.createContext("/api/nodes/remove", GraphEditorServer::handleRemoveNode);
        server.createContext("/api/edges", GraphEditorServer::handleAddEdge);
        server.createContext("/api/edges/remove", GraphEditorServer::handleRemoveEdge);
        server.createContext("/api/validate", GraphEditorServer::handleValidate);
        server.createContext("/api/tests", GraphEditorServer::handleRunTests);
        server.createContext("/api/solvecheck", GraphEditorServer::handleSolveCheck);
        server.createContext("/api/verdictworld", GraphEditorServer::serveVerdictWorld);
        server.createContext("/api/verify", GraphEditorServer::handleVerify);
        server.createContext("/api/ranking", GraphEditorServer::handleRanking);
        server.createContext("/api/run", GraphEditorServer::handleRunStatus);
        server.createContext("/api/run/stop", GraphEditorServer::handleRunStop);
        server.createContext("/api/requirement", GraphEditorServer::handleSetRequirement);
        server.createContext("/api/groups", GraphEditorServer::handleSaveGroup);
        server.createContext("/api/groups/remove", GraphEditorServer::handleRemoveGroup);
        server.start();
        System.out.println("Graph editor running at http://localhost:" + port + " - Ctrl+C to stop.");
    }

    private static void serveIndex(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "text/html; charset=utf-8", PAGE_HTML.getBytes(StandardCharsets.UTF_8));
    }

    /** Picture of the world the last Solve check built; each check deletes it first, so none after a non-FEASIBLE run. */
    private static void serveVerdictWorld(HttpExchange exchange) throws IOException {
        byte[] picture;
        try {
            picture = Files.readAllBytes(GraphEditorFiles.VERDICT_WORLD_PICTURE);
        } catch (NoSuchFileException missing) {
            respondJson(exchange, 404, MinimalJson.writeObject(Map.of("error", "no world picture: the last Solve check did not end FEASIBLE")));
            return;
        }
        respond(exchange, 200, "image/png", picture);
    }

    private static void serveGraph(HttpExchange exchange) throws IOException {
        BiomeGraphData data = BiomeGraphJson.load(GraphEditorFiles.DATA_FILE);
        respondJson(exchange, 200, BiomeGraphJson.toJson(data));
    }

    /** Checks the CURRENTLY SAVED graph on demand - not just newly-added items (add/remove already validate their own proposed state, but a biome that's been under-connected since before this tool existed only surfaces here). */
    private static void handleValidate(HttpExchange exchange) throws IOException {
        BiomeGraphData current = BiomeGraphJson.load(GraphEditorFiles.DATA_FILE);
        ValidationResult result = GraphValidation.validate(current);
        respondJson(exchange, 200, resultJson(result, current));
    }

    @SuppressWarnings("unchecked")
    private static void handleAddNode(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain; charset=utf-8", "POST only".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Map<String, Object> body = (Map<String, Object>) MinimalJson.parse(readBody(exchange));
        NodeData newNode = BiomeGraphJson.nodeFromJson(body);

        BiomeGraphData current = BiomeGraphJson.load(GraphEditorFiles.DATA_FILE);
        List<NodeData> withNewNode = concat(current.nodes(), newNode);
        BiomeGraphData proposed = new BiomeGraphData(withNewNode, current.edges(), current.groups());

        ValidationResult result = GraphValidation.validate(proposed);
        if (result.constructs()) {
            BiomeGraphJson.save(GraphEditorFiles.DATA_FILE, proposed);
        }
        respondJson(exchange, 200, resultJson(result, proposed));
    }

    /**
     * Changes ONE existing biome's size range (2026-09-19, real user request: edit the
     * focused biome's min/max from the UI). Despite the field names, the two numbers are an
     * AREA written as the width of a regular hexagon with that same area - the UI labels say so.
     * Re-validates before saving, same as every other mutation: an inverted or non-positive
     * range is rejected by {@code BiomeSizeRange} during validation and never written.
     */
    @SuppressWarnings("unchecked")
    private static void handleUpdateNodeSize(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain; charset=utf-8", "POST only".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Map<String, Object> body = (Map<String, Object>) MinimalJson.parse(readBody(exchange));
        String namespace = (String) body.get("namespace");
        String path = (String) body.get("path");
        if (!(body.get("minWidthBlocks") instanceof Double minSize) || !(body.get("maxWidthBlocks") instanceof Double maxSize)) {
            respondJson(exchange, 200, MinimalJson.writeObject(Map.of(
                "constructs", false, "constructionError", "Both a minimum and a maximum size are required, as numbers.",
                "errors", List.of()
            )));
            return;
        }

        BiomeGraphData current = BiomeGraphJson.load(GraphEditorFiles.DATA_FILE);
        Optional<BiomeGraphData> proposed = withSize(current, namespace, path, minSize.intValue(), maxSize.intValue());
        if (proposed.isEmpty()) {
            respondJson(exchange, 200, MinimalJson.writeObject(Map.of(
                "constructs", false, "constructionError", "No such biome: " + namespace + ":" + path,
                "errors", List.of()
            )));
            return;
        }

        ValidationResult result = GraphValidation.validate(proposed.get());
        if (result.constructs()) {
            BiomeGraphJson.save(GraphEditorFiles.DATA_FILE, proposed.get());
        }
        respondJson(exchange, 200, resultJson(result, proposed.get()));
    }

    /** {@code current} with {@code namespace:path}'s size range replaced and everything else identical, or empty if no such biome exists. Pure - never touches the data file, so it is what the tests exercise. */
    static Optional<BiomeGraphData> withSize(BiomeGraphData current, String namespace, String path, int minWidthBlocks, int maxWidthBlocks) {
        if (current.nodes().stream().noneMatch(node -> isBiome(node, namespace, path))) {
            return Optional.empty();
        }
        List<NodeData> updatedNodes = current.nodes().stream()
            .map(node -> isBiome(node, namespace, path)
                ? new NodeData(node.namespace(), node.path(), minWidthBlocks, maxWidthBlocks, node.selfAdjacencyLimit(), node.requirement())
                : node)
            .toList();
        return Optional.of(new BiomeGraphData(updatedNodes, current.edges(), current.groups()));
    }

    private static boolean isBiome(NodeData node, String namespace, String path) {
        return node.namespace().equals(namespace) && node.path().equals(path);
    }

    /**
     * Removes ONE biome together with everything that names it: its adjacencies and the required-neighbor
     * entries other biomes had on it (left behind they would be dangling references). Re-validates before
     * saving, same as every other mutation.
     */
    @SuppressWarnings("unchecked")
    private static void handleRemoveNode(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain; charset=utf-8", "POST only".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Map<String, Object> body = (Map<String, Object>) MinimalJson.parse(readBody(exchange));
        String namespace = (String) body.get("namespace");
        String path = (String) body.get("path");

        BiomeGraphData current = BiomeGraphJson.load(GraphEditorFiles.DATA_FILE);
        Optional<BiomeGraphData> proposed = withoutBiome(current, namespace, path);
        if (proposed.isEmpty()) {
            respondJson(exchange, 200, MinimalJson.writeObject(Map.of(
                "constructs", false, "constructionError", "No such biome: " + namespace + ":" + path,
                "errors", List.of()
            )));
            return;
        }

        ValidationResult result = GraphValidation.validate(proposed.get());
        if (result.constructs()) {
            BiomeGraphJson.save(GraphEditorFiles.DATA_FILE, proposed.get());
        }
        respondJson(exchange, 200, resultJson(result, proposed.get()));
    }

    /**
     * {@code current} without {@code namespace:path}, its adjacencies, and its place in every group; empty if no such biome exists. A requirement that names it directly is
     * left as written, so the result no longer validates and the removal is refused until that requirement is edited. Pure - never touches the data file.
     */
    static Optional<BiomeGraphData> withoutBiome(BiomeGraphData current, String namespace, String path) {
        if (current.nodes().stream().noneMatch(node -> isBiome(node, namespace, path))) {
            return Optional.empty();
        }
        String removedKey = namespace + ":" + path;
        List<NodeData> remainingNodes = current.nodes().stream().filter(node -> !isBiome(node, namespace, path)).toList();
        List<EdgeData> remainingEdges = current.edges().stream()
            .filter(edge -> !edge.aKey().equals(removedKey) && !edge.bKey().equals(removedKey))
            .toList();
        List<GroupData> remainingGroups = current.groups().stream()
            .map(group -> new GroupData(group.name(), group.members().stream().filter(member -> !member.equals(removedKey)).toList()))
            .toList();
        return Optional.of(new BiomeGraphData(remainingNodes, remainingEdges, remainingGroups));
    }

    @SuppressWarnings("unchecked")
    private static void handleAddEdge(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain; charset=utf-8", "POST only".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Map<String, Object> body = (Map<String, Object>) MinimalJson.parse(readBody(exchange));
        EdgeData newEdge = BiomeGraphJson.edgeFromJson(body);

        BiomeGraphData current = BiomeGraphJson.load(GraphEditorFiles.DATA_FILE);
        List<EdgeData> withNewEdge = concat(current.edges(), newEdge);
        BiomeGraphData proposed = new BiomeGraphData(current.nodes(), withNewEdge, current.groups());

        ValidationResult result = GraphValidation.validate(proposed);
        if (result.constructs()) {
            BiomeGraphJson.save(GraphEditorFiles.DATA_FILE, proposed);
        }
        respondJson(exchange, 200, resultJson(result, proposed));
    }

    /**
     * Removes ONE edge, matched by its unordered biome pair (namespace/path only -
     * {@code blendWidthBlocks} in the request body is ignored for matching, same reason
     * {@link com.mineutze.worldgen.biomegraph.AdjacencyRule} itself doesn't factor blend
     * width into pair identity). Re-validates the resulting graph before saving - removing
     * an edge is NOT automatically safe (see {@link com.mineutze.worldgen.biomegraph.BiomeGraph}'s
     * own construction-time bridgeability check: this specific edge could have been the
     * only path connecting two biome families within the required hop count) - same
     * discipline as adding one, never a silent unconditional write.
     */
    @SuppressWarnings("unchecked")
    private static void handleRemoveEdge(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain; charset=utf-8", "POST only".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Map<String, Object> body = (Map<String, Object>) MinimalJson.parse(readBody(exchange));
        EdgeData toRemove = BiomeGraphJson.edgeFromJson(body);

        BiomeGraphData current = BiomeGraphJson.load(GraphEditorFiles.DATA_FILE);
        List<EdgeData> withoutEdge = current.edges().stream().filter(edge -> !isSameUnorderedPair(edge, toRemove)).toList();
        if (withoutEdge.size() == current.edges().size()) {
            respondJson(exchange, 200, MinimalJson.writeObject(Map.of(
                "constructs", false, "constructionError", "No such adjacency exists: " + toRemove.aKey() + " <-> " + toRemove.bKey(),
                "errors", List.of()
            )));
            return;
        }
        BiomeGraphData proposed = new BiomeGraphData(current.nodes(), withoutEdge, current.groups());

        ValidationResult result = GraphValidation.validate(proposed);
        if (result.constructs()) {
            BiomeGraphJson.save(GraphEditorFiles.DATA_FILE, proposed);
        }
        respondJson(exchange, 200, resultJson(result, proposed));
    }

    private static boolean isSameUnorderedPair(EdgeData a, EdgeData b) {
        boolean sameOrder = a.aKey().equals(b.aKey()) && a.bKey().equals(b.bKey());
        boolean reversedOrder = a.aKey().equals(b.bKey()) && a.bKey().equals(b.aKey());
        return sameOrder || reversedOrder;
    }

    /** Runs THIS repo's own real tests via the gradle wrapper - {@code ?scope=quick} (just {@code BiomeGraphTest}) or {@code ?scope=full} (whole {@code worldgen-core} suite). */
    private static void handleRunTests(HttpExchange exchange) throws IOException {
        boolean fullSuite = exchange.getRequestURI().getQuery() != null && exchange.getRequestURI().getQuery().contains("scope=full");
        List<String> command = fullSuite
            ? List.of("../gradlew.bat", ":worldgen-core:test")
            : List.of("../gradlew.bat", ":worldgen-core:test", "--tests", "com.mineutze.worldgen.biomegraph.BiomeGraphTest");
        startGradleTaskAndRespond(exchange, fullSuite ? "full worldgen-core suite" : "quick check", command);
    }

    /**
     * Runs {@code graphEditorSolveCheck} - {@link GraphEditorSolveCheck}'s own doc for what it builds
     * and why it is a separate gradle task/process, not an in-process call. Real and slow (minutes):
     * it solves complete worlds from THIS tool's saved data file.
     */
    private static void handleSolveCheck(HttpExchange exchange) throws IOException {
        startGradleTaskAndRespond(exchange, "Solve check", List.of("../gradlew.bat", ":worldgen-tooling:graphEditorSolveCheck"));
    }

    /** POST {ranks, fingerprint}: picks checked here first, so a refused pick never costs a gradle start. */
    private static void handleVerify(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain; charset=utf-8", "POST only".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Optional<BiomeGraphData> catalog = loadGraphOrAnswer500(exchange);
        if (catalog.isEmpty()) {
            return;
        }
        VerifyRequest request;
        try {
            request = VerifyRequest.parse(readBody(exchange));
            TightnessVerify.checkPicks(GraphEditorFiles.TIGHTNESS_RANKING, catalog.get(), request.ranks(), request.fingerprint());
        } catch (IllegalArgumentException | RankingUnavailable refused) {
            respondJson(exchange, 400, MinimalJson.writeObject(Map.of("started", false, "error", refused.getMessage())));
            return;
        }
        startGradleTaskAndRespond(exchange, "Verify", List.of("../gradlew.bat", ":worldgen-tooling:graphEditorVerify", request.gradleArgument()));
    }

    /** Saved ranking as a pick list; {"none": reason} when there is none for this graph. */
    private static void handleRanking(HttpExchange exchange) throws IOException {
        Optional<BiomeGraphData> catalog = loadGraphOrAnswer500(exchange);
        if (catalog.isEmpty()) {
            return;
        }
        RankingView view;
        try {
            view = TightnessVerify.choices(GraphEditorFiles.TIGHTNESS_RANKING, catalog.get());
        } catch (RankingUnavailable missing) {
            respondJson(exchange, 200, MinimalJson.writeObject(Map.of("none", missing.getMessage())));
            return;
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("choices", view.choices().stream().map(GraphEditorServer::choiceJson).toList());
        json.put("failedAttempts", view.failedAttempts());
        json.put("fingerprint", view.fingerprint());
        json.put("measuredOnThisGraph", view.measuredOnThisGraph());
        json.put("removedByEdits", view.removedByEdits());
        json.put("maxPicks", TightnessVerify.MAX_PICKS);
        respondJson(exchange, 200, MinimalJson.writeObject(json));
    }

    /** Graph file unreadable or invalid: the page shows why instead of a dropped connection. */
    private static Optional<BiomeGraphData> loadGraphOrAnswer500(HttpExchange exchange) throws IOException {
        try {
            return Optional.of(BiomeGraphJson.load(GraphEditorFiles.DATA_FILE));
        } catch (RuntimeException loadFailure) {
            respondJson(exchange, 500, MinimalJson.writeObject(Map.of("started", false, "error", "Could not load the graph " + GraphEditorFiles.DATA_FILE.toAbsolutePath() + ": " + loadFailure)));
            return Optional.empty();
        }
    }

    private static Map<String, Object> choiceJson(RankingChoice choice) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("rank", choice.rank());
        json.put("law", choice.law());
        json.put("attemptsBroken", choice.attemptsBroken());
        choice.loosening().ifPresent(loosening -> json.put("loosening", loosening));
        return json;
    }

    /**
     * Sets the border requirement of ONE biome from its text, blank clearing it. A text that cannot be read, or that names a group or biome that does not exist, is refused
     * by validation and not saved; a requirement the generator could never meet is saved and shown as an error. Re-validates before saving, like every mutation here.
     */
    @SuppressWarnings("unchecked")
    private static void handleSetRequirement(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain; charset=utf-8", "POST only".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Map<String, Object> body = (Map<String, Object>) MinimalJson.parse(readBody(exchange));
        String namespace = (String) body.get("namespace");
        String path = (String) body.get("path");

        BiomeGraphData current = BiomeGraphJson.load(GraphEditorFiles.DATA_FILE);
        Optional<BiomeGraphData> proposed = withRequirement(current, namespace, path, (String) body.get("requirement"));
        if (proposed.isEmpty()) {
            respondNoSuchBiome(exchange, namespace, path);
            return;
        }
        validateSaveAndRespond(exchange, proposed.get());
    }

    /** {@code current} with {@code text} as the requirement of {@code namespace:path}, in its canonical form when it can be read (as written when it cannot, so validation can say why); empty if no such biome exists. Pure. */
    static Optional<BiomeGraphData> withRequirement(BiomeGraphData current, String namespace, String path, String text) {
        if (current.nodes().stream().noneMatch(node -> isBiome(node, namespace, path))) {
            return Optional.empty();
        }
        String stored;
        try {
            stored = BorderRequirement.parse(text).map(BorderRequirement::toString).orElse("");
        } catch (IllegalArgumentException unreadable) {
            stored = text.strip();
        }
        String requirement = stored;
        List<NodeData> updatedNodes = current.nodes().stream()
            .map(node -> isBiome(node, namespace, path)
                ? new NodeData(node.namespace(), node.path(), node.minWidthBlocks(), node.maxWidthBlocks(), node.selfAdjacencyLimit(), requirement)
                : node)
            .toList();
        return Optional.of(new BiomeGraphData(updatedNodes, current.edges(), current.groups()));
    }

    /** Adds a group, or replaces the members of the group of the same name. */
    @SuppressWarnings("unchecked")
    private static void handleSaveGroup(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain; charset=utf-8", "POST only".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Map<String, Object> body = (Map<String, Object>) MinimalJson.parse(readBody(exchange));
        List<String> members = new java.util.ArrayList<>();
        for (Object member : (List<Object>) body.get("members")) {
            members.add((String) member);
        }
        BiomeGraphData current = BiomeGraphJson.load(GraphEditorFiles.DATA_FILE);
        validateSaveAndRespond(exchange, withGroup(current, ((String) body.get("name")).strip(), members));
    }

    /** {@code current} with a group called {@code name} holding {@code members}, replacing the group of that name if there is one, in place. Pure. */
    static BiomeGraphData withGroup(BiomeGraphData current, String name, List<String> members) {
        GroupData group = new GroupData(name, List.copyOf(members));
        boolean replaced = current.groups().stream().anyMatch(existing -> existing.name().equals(name));
        List<GroupData> groups = replaced
            ? current.groups().stream().map(existing -> existing.name().equals(name) ? group : existing).toList()
            : concat(current.groups(), group);
        return new BiomeGraphData(current.nodes(), current.edges(), groups);
    }

    /** Removes a group; refused by validation (and not saved) while a requirement still names it. */
    @SuppressWarnings("unchecked")
    private static void handleRemoveGroup(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain; charset=utf-8", "POST only".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Map<String, Object> body = (Map<String, Object>) MinimalJson.parse(readBody(exchange));
        String name = (String) body.get("name");
        BiomeGraphData current = BiomeGraphJson.load(GraphEditorFiles.DATA_FILE);
        Optional<BiomeGraphData> proposed = withoutGroup(current, name);
        if (proposed.isEmpty()) {
            respondJson(exchange, 200, MinimalJson.writeObject(Map.of("constructs", false, "constructionError", "No such group: " + name, "errors", List.of())));
            return;
        }
        validateSaveAndRespond(exchange, proposed.get());
    }

    /** {@code current} without the group called {@code name}; empty if there is none. Pure. */
    static Optional<BiomeGraphData> withoutGroup(BiomeGraphData current, String name) {
        if (current.groups().stream().noneMatch(group -> group.name().equals(name))) {
            return Optional.empty();
        }
        return Optional.of(new BiomeGraphData(current.nodes(), current.edges(), current.groups().stream().filter(group -> !group.name().equals(name)).toList()));
    }

    private static void respondNoSuchBiome(HttpExchange exchange, String namespace, String path) throws IOException {
        respondJson(exchange, 200, MinimalJson.writeObject(Map.of(
            "constructs", false, "constructionError", "No such biome: " + namespace + ":" + path, "errors", List.of()
        )));
    }

    /** Validates {@code proposed}, saves it unless it is malformed, and answers with the validation result: the tail every mutation shares. */
    private static void validateSaveAndRespond(HttpExchange exchange, BiomeGraphData proposed) throws IOException {
        ValidationResult result = GraphValidation.validate(proposed);
        if (result.constructs()) {
            BiomeGraphJson.save(GraphEditorFiles.DATA_FILE, proposed);
        }
        respondJson(exchange, 200, resultJson(result, proposed));
    }

    /** Answers at once; page polls /api/run for output. 409 while another run goes. */
    private static void startGradleTaskAndRespond(HttpExchange exchange, String label, List<String> command) throws IOException {
        Optional<GradleRun> started;
        try {
            started = CURRENT_RUN.start(label, command);
        } catch (UncheckedIOException launchFailure) {
            respondJson(exchange, 200, MinimalJson.writeObject(Map.of("started", false, "output", "Could not launch: " + launchFailure.getMessage() + " (" + launchFailure.getCause() + ")")));
            return;
        }
        if (started.isEmpty()) {
            respondJson(exchange, 409, MinimalJson.writeObject(Map.of("started", false, "output", "Another run is still in progress; each one uses every core - wait for it or press Stop.")));
            return;
        }
        respondJson(exchange, 200, MinimalJson.writeObject(Map.of("started", true, "label", label)));
    }

    /** Latest run's lines, finished or not; {"none": true} before any run; 400 on a bad query (see RunStatusQuery). */
    private static void handleRunStatus(HttpExchange exchange) throws IOException {
        Optional<GradleRun> run = CURRENT_RUN.current();
        if (run.isEmpty()) {
            respondJson(exchange, 200, MinimalJson.writeObject(Map.of("none", true)));
            return;
        }
        RunSnapshot snapshot;
        try {
            RunStatusQuery query = RunStatusQuery.parse(exchange.getRequestURI().getRawQuery());
            snapshot = RunSnapshot.of(run.get(), query.fromIndexIn(run.get()));
        } catch (IllegalArgumentException badQuery) {
            respondJson(exchange, 400, MinimalJson.writeObject(Map.of("error", badQuery.getMessage())));
            return;
        }
        respondJson(exchange, 200, MinimalJson.writeObject(snapshot.toJson()));
    }

    /** POST only: a GET would let any page the user opens stop a run with an image link. */
    private static void handleRunStop(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain; charset=utf-8", "POST only".getBytes(StandardCharsets.UTF_8));
            return;
        }
        boolean stopping = CURRENT_RUN.current().map(GradleRun::stop).orElse(false);
        respondJson(exchange, 200, MinimalJson.writeObject(Map.of("stopping", stopping)));
    }

    private static String resultJson(ValidationResult result, BiomeGraphData proposed) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("constructs", result.constructs());
        json.put("constructionError", result.constructionError());
        json.put("errors", result.errors());
        json.put("saved", result.constructs());
        json.put("graph", Map.of("nodes", proposed.nodes().size(), "edges", proposed.edges().size()));
        return MinimalJson.writeObject(json);
    }

    private static <T> List<T> concat(List<T> existing, T extra) {
        List<T> combined = new java.util.ArrayList<>(existing);
        combined.add(extra);
        return combined;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respondJson(HttpExchange exchange, int status, String json) throws IOException {
        respond(exchange, status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(body);
        }
    }

    // Single-file UI on purpose (POC scope) - no vendored library (nothing to vendor for
    // a graph this small at POC scope). Two view modes (2026-09-14, real user feedback:
    // "select a single biome and see only its adjacencies... otherwise it is unreadable"
    // - the full 48-node/272-edge overview was exactly that): OVERVIEW (every node on a
    // plain circle, every edge - useful only as a bird's-eye "is this roughly connected"
    // check) and FOCUS (pick one biome from the dropdown, graph re-centers to hub-and-
    // spoke - that biome in the middle, ONLY its real neighbors around it, ONLY its own
    // edges drawn - the actual readable, useful view for real editing work). Adding an
    // adjacency no longer depends on clicking two tiny SVG circles (fragile, and doesn't
    // work at all for a biome not currently shown in FOCUS view) - two plain dropdowns,
    // always populated with every biome regardless of view mode.
    private static final String PAGE_HTML = """
        <!doctype html>
        <html>
        <head>
        <meta charset="utf-8">
        <title>Mineutze Worldgen - Biome Graph Editor (POC)</title>
        <style>
          body { font-family: system-ui, sans-serif; margin: 0; display: flex; height: 100vh; }
          #graphPane { flex: 2; border-right: 1px solid #ccc; overflow: auto; display: flex; flex-direction: column; }
          #focusBar { padding: 8px; border-bottom: 1px solid #ccc; }
          #graphSvg { flex: 1; }
          #sidePane { flex: 1; padding: 12px; overflow: auto; }
          svg { width: 100%; height: 100%; }
          circle { fill: #6b8; stroke: #333; cursor: pointer; }
          circle.focused { fill: #e83; r: 9; }
          line { stroke: #999; stroke-width: 1; }
          text { font-size: 10px; pointer-events: none; }
          #rankingPanel table { border-collapse: collapse; font-size: 12px; margin: 6px 0; }
          #rankingPanel td, #rankingPanel th { border: 1px solid #ccc; padding: 2px 4px; text-align: left; }
          #rankingPanel tr.cannotLoosen { color: #888; }
          .warning { background: #fff3cd; border: 1px solid #d4a72c; padding: 6px; margin: 4px 0; font-size: 13px; }
          .error { background: #f8d7da; border: 1px solid #d33; padding: 6px; margin: 4px 0; font-size: 13px; }
          .ok { background: #d4edda; border: 1px solid #3a3; padding: 6px; margin: 4px 0; font-size: 13px; }
          fieldset { margin-bottom: 16px; }
          label { display: block; margin: 4px 0 2px; font-size: 12px; }
          input, select { width: 100%; box-sizing: border-box; }
          pre { white-space: pre-wrap; font-size: 11px; max-height: 300px; overflow: auto; background: #222; color: #ddd; padding: 6px; }
          button { margin-top: 8px; }
          #areaReadout { font-size: 12px; color: #555; margin-top: 4px; }
          #adjacencyList { font-size: 12px; }
          #adjacencyList li { margin: 2px 0; }
        </style>
        </head>
        <body>
        <div id="graphPane">
          <div id="focusBar">
            <label>Focus on one biome (shows only its own adjacencies)</label>
            <select id="focusSelect" onchange="setFocus(this.value)"></select>
          </div>
          <svg id="graphSvg"></svg>
        </div>
        <div id="sidePane">
          <h3 id="adjacencyHeading">All adjacencies</h3>
          <ul id="adjacencyList"></ul>

          <h3 id="editSizeHeading">Edit size (focus a biome first)</h3>
          <fieldset>
            <label>Min size (blocks) - the width of a regular hexagon with the minimum AREA</label><input id="editMin" type="number" oninput="updateAreaReadout()">
            <label>Max size (blocks) - the width of a regular hexagon with the maximum AREA</label><input id="editMax" type="number" oninput="updateAreaReadout()">
            <div id="areaReadout"></div>
            <button id="saveSizeButton" onclick="saveSize()">Save size (to focused biome)</button>
          </fieldset>

          <h3 id="removeBiomeHeading">Remove biome (focus a biome first)</h3>
          <fieldset>
            <button id="removeBiomeButton" onclick="removeBiome()">Remove focused biome</button>
          </fieldset>

          <h3>Add biome</h3>
          <fieldset>
            <label>Biome ID (namespace:path, matches /dump_biomes output)</label><input id="nodeId" placeholder="biomesoplenty:maple_woods">
            <label>Min size (blocks) - the width of a regular hexagon with the minimum AREA</label><input id="nodeMin" type="number" value="500">
            <label>Max size (blocks) - the width of a regular hexagon with the maximum AREA</label><input id="nodeMax" type="number" value="800">
            <label>Self-adjacency limit (blank = uncapped)</label><input id="nodeSelfAdj" type="number">
            <button onclick="addNode()">Add biome</button>
          </fieldset>

          <h3>Add adjacency</h3>
          <fieldset>
            <label>Biome A</label><select id="edgeA"></select>
            <label>Biome B</label><select id="edgeB"></select>
            <label>Blend width blocks</label><input id="edgeBlend" type="number" value="10">
            <button onclick="addEdge()">Add adjacency</button>
          </fieldset>

          <h3 id="requirementHeading">Border requirement (focus a biome first)</h3>
          <fieldset>
            <label>Every cluster of the focused biome must share these borders, in cell edges (one edge is about 150 blocks) with a group or a biome id. Examples:
              <code>oceans &gt;= 2</code> - <code>oceans &gt;= 2 and forests &gt;= 1</code> - <code>minecraft:ocean &gt;= 2 or minecraft:lukewarm_ocean &gt;= 2</code>.
              "and" binds tighter than "or"; use parentheses to group. Leave empty for none.</label>
            <textarea id="requirementText" rows="3"></textarea>
            <div id="groupNamesHint"></div>
            <button id="saveRequirementButton" onclick="saveRequirement()">Save requirement (to focused biome)</button>
          </fieldset>

          <h3>Groups</h3>
          <fieldset>
            <label>Existing groups</label><select id="groupSelect" onchange="loadGroup(this.value)"></select>
            <label>Group name (letters, digits, _ . -)</label><input id="groupName">
            <label>Members (ctrl-click to pick several)</label><select id="groupMembers" multiple size="10"></select>
            <button onclick="saveGroup()">Save group</button>
            <button id="removeGroupButton" onclick="removeGroup()">Remove group</button>
          </fieldset>

          <h3>Warnings</h3>
          <button onclick="validateNow()">Validate current graph</button>
          <div id="warnings"></div>

          <h3>Tests</h3>
          <button class="gradleRun" onclick="runTests(false)">Run quick check (BiomeGraphTest)</button>
          <button class="gradleRun" onclick="runTests(true)">Run full worldgen-core suite</button>
          <button class="gradleRun" onclick="runSolveCheck()">Solve check (feasibility verdict, up to 2 min)</button>
          <button class="gradleRun" onclick="runVerify()">Verify (measures the ticked loosenings below, up to 10 min)</button>
          <button id="stopRun" onclick="stopRun()" disabled>Stop</button>
          <div id="rankingPanel"></div>
          <div id="testStatus"></div>
          <div id="testReportLinks"></div>
          <pre id="testOutput"></pre>
          <img id="verdictWorld" alt="World built by the last Solve check" style="display:none;max-width:100%">
        </div>
        <script>
        let graph = null;
        let focusKey = '';

        /** Ranking reloads with the graph: a pick list must not outlive the graph version it measured. */
        async function loadGraph() {
          const response = await fetch('/api/graph');
          graph = await response.json();
          loadRanking();
          populateSelectors();
          renderGraph();
          renderAdjacencyList();
          renderRequirement();
          renderGroups();
          renderEditSize();
          renderRemoveBiome();
        }

        function keyOf(n) { return n.namespace + ':' + n.path; }

        function populateSelectors() {
          const keys = graph.nodes.map(keyOf).sort();
          fillSelect('focusSelect', keys, true, focusKey);
          fillSelect('edgeA', keys, false, document.getElementById('edgeA') ? document.getElementById('edgeA').value : '');
          fillSelect('edgeB', keys, false, document.getElementById('edgeB') ? document.getElementById('edgeB').value : '');
        }

        function fillSelect(id, keys, withShowAll, keepValue) {
          const select = document.getElementById(id);
          select.innerHTML = '';
          if (withShowAll) {
            const opt = document.createElement('option');
            opt.value = ''; opt.textContent = '— show all (overview) —';
            select.appendChild(opt);
          }
          keys.forEach(k => {
            const opt = document.createElement('option');
            opt.value = k; opt.textContent = k;
            select.appendChild(opt);
          });
          if (keys.includes(keepValue) || keepValue === '') select.value = keepValue;
        }

        function setFocus(key) {
          focusKey = key;
          renderGraph();
          renderAdjacencyList();
          renderRequirement();
          renderEditSize();
          renderRemoveBiome();
        }

        function focusedNode() {
          if (!focusKey) return null;
          return graph.nodes.find(n => keyOf(n) === focusKey) || null;
        }

        // A regular hexagon's area is 3*sqrt(3)/2 times its circumradius squared, and the
        // "size" number is the hexagon's width, i.e. twice the circumradius. The size is an area, entered as that width.
        function areaOfHexagonWidth(widthBlocks) {
          const circumradius = widthBlocks / 2;
          return 3 * Math.sqrt(3) / 2 * circumradius * circumradius;
        }

        function updateAreaReadout() {
          const minSize = parseInt(document.getElementById('editMin').value, 10);
          const maxSize = parseInt(document.getElementById('editMax').value, 10);
          const readout = document.getElementById('areaReadout');
          if (isNaN(minSize) || isNaN(maxSize)) { readout.textContent = ''; return; }
          const format = size => Math.round(areaOfHexagonWidth(size)).toLocaleString();
          readout.textContent = 'Area: ' + format(minSize) + ' to ' + format(maxSize) + ' blocks²';
        }

        function renderEditSize() {
          const node = focusedNode();
          const heading = document.getElementById('editSizeHeading');
          const minInput = document.getElementById('editMin');
          const maxInput = document.getElementById('editMax');
          document.getElementById('saveSizeButton').disabled = !node;
          minInput.disabled = !node;
          maxInput.disabled = !node;
          if (!node) {
            heading.textContent = 'Edit size (focus a biome first)';
            minInput.value = '';
            maxInput.value = '';
          } else {
            heading.textContent = 'Edit size of ' + focusKey;
            minInput.value = node.minWidthBlocks;
            maxInput.value = node.maxWidthBlocks;
          }
          updateAreaReadout();
        }

        async function saveSize() {
          const node = focusedNode();
          if (!node) { alert('Focus a biome first (top dropdown) - the size is saved to the FOCUSED biome.'); return; }
          const minSize = parseInt(document.getElementById('editMin').value, 10);
          const maxSize = parseInt(document.getElementById('editMax').value, 10);
          if (isNaN(minSize) || isNaN(maxSize)) { alert('Enter both a minimum and a maximum size.'); return; }
          const body = { namespace: node.namespace, path: node.path, minWidthBlocks: minSize, maxWidthBlocks: maxSize };
          const response = await fetch('/api/nodes/size', { method: 'POST', body: JSON.stringify(body) });
          const result = await response.json();
          showResult(result);
          if (result.constructs) await loadGraph();
        }

        function renderRemoveBiome() {
          const node = focusedNode();
          document.getElementById('removeBiomeButton').disabled = !node;
          document.getElementById('removeBiomeHeading').textContent = node ? 'Remove biome ' + focusKey : 'Remove biome (focus a biome first)';
        }

        async function removeBiome() {
          const node = focusedNode();
          if (!node) { alert('Focus a biome first (top dropdown) - the FOCUSED biome is the one removed.'); return; }
          const adjacencyCount = neighborsOf(focusKey).length;
          const groupCount = (graph.groups || []).filter(group => group.members.includes(focusKey)).length;
          if (!confirm('Remove biome ' + focusKey + '? Its ' + adjacencyCount + ' adjacencies are removed too, and it leaves ' + groupCount
            + ' group(s). A requirement that names it directly must be edited first.')) return;
          const body = { namespace: node.namespace, path: node.path };
          const response = await fetch('/api/nodes/remove', { method: 'POST', body: JSON.stringify(body) });
          const result = await response.json();
          showResult(result);
          if (result.constructs) {
            focusKey = '';
            await loadGraph();
          }
        }

        function renderRequirement() {
          const node = focusedNode();
          document.getElementById('requirementText').disabled = !node;
          document.getElementById('saveRequirementButton').disabled = !node;
          document.getElementById('requirementHeading').textContent = node ? 'Border requirement of ' + focusKey : 'Border requirement (focus a biome first)';
          document.getElementById('requirementText').value = node ? (node.requirement || '') : '';
          const names = (graph.groups || []).map(group => group.name);
          document.getElementById('groupNamesHint').textContent = names.length ? 'Groups: ' + names.join(', ') : 'No groups yet - name a biome by its id, or make a group below.';
        }

        async function saveRequirement() {
          const node = focusedNode();
          if (!node) { alert('Focus a biome first (top dropdown) - the requirement is saved to the FOCUSED biome.'); return; }
          const body = { namespace: node.namespace, path: node.path, requirement: document.getElementById('requirementText').value };
          const response = await fetch('/api/requirement', { method: 'POST', body: JSON.stringify(body) });
          const result = await response.json();
          showResult(result);
          if (result.constructs) await loadGraph();
        }

        function renderGroups() {
          const groups = graph.groups || [];
          const select = document.getElementById('groupSelect');
          const kept = select.value;
          select.innerHTML = '';
          const blank = document.createElement('option');
          blank.value = ''; blank.textContent = '— new group —';
          select.appendChild(blank);
          groups.forEach(group => {
            const option = document.createElement('option');
            option.value = group.name; option.textContent = group.name + ' (' + group.members.length + ')';
            select.appendChild(option);
          });
          select.value = groups.some(group => group.name === kept) ? kept : '';
          const members = document.getElementById('groupMembers');
          members.innerHTML = '';
          graph.nodes.map(keyOf).sort().forEach(key => {
            const option = document.createElement('option');
            option.value = key; option.textContent = key;
            members.appendChild(option);
          });
          loadGroup(select.value);
        }

        function loadGroup(name) {
          const group = (graph.groups || []).find(candidate => candidate.name === name);
          document.getElementById('groupName').value = group ? group.name : '';
          Array.from(document.getElementById('groupMembers').options).forEach(option => { option.selected = !!group && group.members.includes(option.value); });
          document.getElementById('removeGroupButton').disabled = !group;
        }

        async function saveGroup() {
          const name = document.getElementById('groupName').value.trim();
          if (!name) { alert('Give the group a name.'); return; }
          const members = Array.from(document.getElementById('groupMembers').selectedOptions).map(option => option.value);
          const response = await fetch('/api/groups', { method: 'POST', body: JSON.stringify({ name, members }) });
          const result = await response.json();
          showResult(result);
          if (result.constructs) {
            await loadGraph();
            document.getElementById('groupSelect').value = name;
            loadGroup(name);
          }
        }

        async function removeGroup() {
          const name = document.getElementById('groupSelect').value;
          if (!name || !confirm('Remove the group ' + name + '?')) return;
          const response = await fetch('/api/groups/remove', { method: 'POST', body: JSON.stringify({ name }) });
          const result = await response.json();
          showResult(result);
          if (result.constructs) await loadGraph();
        }

        function neighborsOf(key) {
          const neighbors = [];
          graph.edges.forEach(e => {
            const a = e.aNamespace + ':' + e.aPath, b = e.bNamespace + ':' + e.bPath;
            if (a === key) neighbors.push({ key: b, edge: e });
            else if (b === key) neighbors.push({ key: a, edge: e });
          });
          return neighbors;
        }

        function renderAdjacencyList() {
          const heading = document.getElementById('adjacencyHeading');
          const list = document.getElementById('adjacencyList');
          list.innerHTML = '';
          if (!focusKey) {
            heading.textContent = 'All adjacencies (' + graph.edges.length + ')';
            return;
          }
          const neighbors = neighborsOf(focusKey);
          heading.textContent = focusKey + ' — ' + neighbors.length + ' adjacenc' + (neighbors.length === 1 ? 'y' : 'ies');
          neighbors.sort((x, y) => x.key.localeCompare(y.key)).forEach(n => {
            const li = document.createElement('li');
            const label = document.createElement('span');
            label.textContent = n.key + ' (blend ' + n.edge.blendWidthBlocks + ') ';
            li.appendChild(label);
            const removeBtn = document.createElement('button');
            removeBtn.textContent = 'Remove';
            removeBtn.onclick = () => removeEdge(focusKey, n.key);
            li.appendChild(removeBtn);
            list.appendChild(li);
          });
        }

        async function removeEdge(aKey, bKey) {
          if (!confirm('Remove adjacency ' + aKey + ' <-> ' + bKey + '?')) return;
          const [aNamespace, aPath] = aKey.split(':');
          const [bNamespace, bPath] = bKey.split(':');
          const body = { aNamespace, aPath, bNamespace, bPath, blendWidthBlocks: 0 };
          const response = await fetch('/api/edges/remove', { method: 'POST', body: JSON.stringify(body) });
          const result = await response.json();
          showResult(result);
          if (result.constructs) await loadGraph();
        }

        async function validateNow() {
          const response = await fetch('/api/validate');
          const result = await response.json();
          showResult(result, false);
        }

        function renderGraph() {
          const svg = document.getElementById('graphSvg');
          svg.innerHTML = '';
          const width = svg.clientWidth || 800, height = svg.clientHeight || 600;
          const cx = width / 2, cy = height / 2;

          let nodesToShow, edgesToShow, positions = {};
          if (focusKey) {
            const neighbors = neighborsOf(focusKey);
            nodesToShow = [focusKey, ...neighbors.map(n => n.key)];
            edgesToShow = neighbors.map(n => n.edge);
            positions[focusKey] = { x: cx, y: cy };
            const r = Math.min(cx, cy) - 60;
            neighbors.forEach((n, i) => {
              const angle = (2 * Math.PI * i) / Math.max(neighbors.length, 1);
              positions[n.key] = { x: cx + r * Math.cos(angle), y: cy + r * Math.sin(angle) };
            });
          } else {
            nodesToShow = graph.nodes.map(keyOf);
            edgesToShow = graph.edges;
            const r = Math.min(cx, cy) - 40;
            nodesToShow.forEach((k, i) => {
              const angle = (2 * Math.PI * i) / nodesToShow.length;
              positions[k] = { x: cx + r * Math.cos(angle), y: cy + r * Math.sin(angle) };
            });
          }

          edgesToShow.forEach(e => {
            const a = positions[e.aNamespace + ':' + e.aPath], b = positions[e.bNamespace + ':' + e.bPath];
            if (!a || !b) return;
            const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
            line.setAttribute('x1', a.x); line.setAttribute('y1', a.y);
            line.setAttribute('x2', b.x); line.setAttribute('y2', b.y);
            svg.appendChild(line);
          });
          nodesToShow.forEach(k => {
            const p = positions[k];
            const circle = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
            circle.setAttribute('cx', p.x); circle.setAttribute('cy', p.y); circle.setAttribute('r', 6);
            if (k === focusKey) circle.classList.add('focused');
            circle.onclick = () => setFocus(k);
            svg.appendChild(circle);
            const label = document.createElementNS('http://www.w3.org/2000/svg', 'text');
            label.setAttribute('x', p.x + 10); label.setAttribute('y', p.y + 3);
            label.textContent = k;
            svg.appendChild(label);
          });
        }

        function showResult(result, saved = true) {
          const div = document.getElementById('warnings');
          div.innerHTML = '';
          if (!result.constructs) {
            const el = document.createElement('div');
            el.className = 'error';
            el.textContent = (saved ? 'REJECTED (not saved): ' : 'INVALID: ') + result.constructionError;
            div.appendChild(el);
            return;
          }
          const savedEl = document.createElement('div');
          savedEl.className = 'ok';
          savedEl.textContent = (saved ? 'Saved. ' : 'Structurally valid. ')
            + 'Graph has ' + result.graph.nodes + ' biomes, ' + result.graph.edges + ' adjacencies.';
          div.appendChild(savedEl);
          if (result.errors.length === 0) {
            const el = document.createElement('div');
            el.className = 'ok';
            el.textContent = 'No errors.';
            div.appendChild(el);
          }
          result.errors.forEach(e => {
            const el = document.createElement('div');
            el.className = 'error';
            el.textContent = e;
            div.appendChild(el);
          });
        }

        async function addNode() {
          const rawId = document.getElementById('nodeId').value.trim();
          const colonIndex = rawId.indexOf(':');
          if (colonIndex <= 0 || colonIndex === rawId.length - 1) {
            alert('Biome ID must be namespace:path, e.g. biomesoplenty:maple_woods - got "' + rawId + '"');
            return;
          }
          const namespace = rawId.substring(0, colonIndex);
          const path = rawId.substring(colonIndex + 1);
          const body = {
            namespace: namespace,
            path: path,
            minWidthBlocks: parseInt(document.getElementById('nodeMin').value, 10),
            maxWidthBlocks: parseInt(document.getElementById('nodeMax').value, 10),
            selfAdjacencyLimit: document.getElementById('nodeSelfAdj').value ? parseInt(document.getElementById('nodeSelfAdj').value, 10) : 2147483647
          };
          const response = await fetch('/api/nodes', { method: 'POST', body: JSON.stringify(body) });
          const result = await response.json();
          showResult(result);
          if (result.constructs) { document.getElementById('nodeId').value = ''; await loadGraph(); }
        }

        async function addEdge() {
          const aKey = document.getElementById('edgeA').value, bKey = document.getElementById('edgeB').value;
          if (!aKey || !bKey || aKey === bKey) { alert('Pick two different biomes.'); return; }
          const [aNamespace, aPath] = aKey.split(':');
          const [bNamespace, bPath] = bKey.split(':');
          const body = {
            aNamespace, aPath, bNamespace, bPath,
            blendWidthBlocks: parseInt(document.getElementById('edgeBlend').value, 10)
          };
          const response = await fetch('/api/edges', { method: 'POST', body: JSON.stringify(body) });
          const result = await response.json();
          showResult(result);
          if (result.constructs) await loadGraph();
        }

        async function runTests(full) {
          await startRunAndPoll('/api/tests?scope=' + (full ? 'full' : 'quick'));
        }

        // Ranking pick list: Verify measures the ticked laws. Top loosenable ones start ticked, cap from the server.
        let rankingFingerprint = null;
        let maxPicks = 0;
        let rankingRequest = 0;

        async function runVerify() {
          if (rankingFingerprint === null) {
            document.getElementById('testStatus').textContent = 'No ranking loaded: run a Solve check first (Verify measures laws from its ranking).';
            return;
          }
          const ranks = [...document.querySelectorAll('#rankingPanel input:checked')].map(box => Number(box.value));
          await startRunAndPoll('/api/verify', { method: 'POST', body: JSON.stringify({ ranks: ranks, fingerprint: rankingFingerprint }) });
        }

        // Edits in quick succession overlap: only the latest request may draw the panel.
        async function loadRanking() {
          const panel = document.getElementById('rankingPanel');
          const thisRequest = ++rankingRequest;
          try {
            const ranking = await fetchJson('/api/ranking');
            if (thisRequest !== rankingRequest) return;
            if (ranking.none) {
              rankingFingerprint = null;
              showNoRanking(panel, ranking.none);
              return;
            }
            rankingFingerprint = ranking.fingerprint;
            maxPicks = ranking.maxPicks;
            renderRanking(panel, ranking);
          } catch (failure) {
            if (thisRequest !== rankingRequest) return;
            rankingFingerprint = null;
            panel.textContent = 'Could not load the ranking: ' + failure.message;
          }
        }

        function showNoRanking(panel, reason) {
          panel.textContent = 'No ranking to pick from: ' + reason;
        }

        function editedGraphNote(ranking) {
          if (ranking.measuredOnThisGraph) return '';
          const removed = ranking.removedByEdits === 0 ? '' : '; ' + ranking.removedByEdits + ' no longer stated by the graph, removed';
          return ' - counted at the last Solve check, before later edits' + removed;
        }

        function renderRanking(panel, ranking) {
          panel.innerHTML = '';
          const caption = document.createElement('div');
          caption.textContent = 'Laws the ' + ranking.failedAttempts + ' failed attempts still broke (measured, not cause)' + editedGraphNote(ranking)
            + '. Tick up to ' + maxPicks + ' for Verify (it measures the current graph):';
          panel.appendChild(caption);
          const table = document.createElement('table');
          table.innerHTML = '<tr><th></th><th>#</th><th>Law</th><th>Broke</th><th>Verify would</th></tr>';
          let ticked = 0;
          ranking.choices.forEach(choice => {
            const startTicked = choice.loosening !== undefined && ticked < maxPicks;
            if (startTicked) ticked++;
            table.appendChild(rankingRow(choice, ranking.failedAttempts, startTicked));
          });
          panel.appendChild(table);
        }

        function rankingRow(choice, failedAttempts, startTicked) {
          const row = document.createElement('tr');
          const box = document.createElement('input');
          box.type = 'checkbox';
          box.value = choice.rank;
          box.checked = startTicked;
          box.disabled = choice.loosening === undefined;
          box.onchange = () => refuseOverCap(box);
          if (box.disabled) row.className = 'cannotLoosen';
          const cells = [String(choice.rank), choice.law, choice.attemptsBroken + ' of ' + failedAttempts, choice.loosening === undefined ? "can't be loosened" : choice.loosening];
          const boxCell = document.createElement('td');
          boxCell.appendChild(box);
          row.appendChild(boxCell);
          cells.forEach(text => {
            const cell = document.createElement('td');
            cell.textContent = text;
            row.appendChild(cell);
          });
          return row;
        }

        function refuseOverCap(box) {
          if (box.checked && document.querySelectorAll('#rankingPanel input:checked').length > maxPicks) {
            box.checked = false;
            document.getElementById('testStatus').textContent = 'Verify measures at most ' + maxPicks + ' laws per run (10 min shared): untick one first.';
          }
        }

        async function runSolveCheck() {
          await startRunAndPoll('/api/solvecheck');
        }

        function statusLabelOf(result) {
          const exitLabel = 'Exit code ' + result.exitCode + (result.exitCode === 0 ? ' (PASS)' : ' (FAIL)');
          const statusMatch = result.output.match(/^(VERDICT|VERIFY): (FEASIBLE|INFEASIBLE|UNPROVEN|MEASURED|NO RANKING|REFUSED|CRASH)$/m);
          if (!statusMatch) return exitLabel;
          const status = statusMatch[2];
          const exitAgrees = (status === 'FEASIBLE' || status === 'MEASURED') === (result.exitCode === 0);
          return exitAgrees ? status : status + ' but ' + exitLabel;
        }

        function setGradleButtonsEnabled(enabled) {
          document.querySelectorAll('button.gradleRun').forEach(button => button.disabled = !enabled);
        }

        function showVerdictWorld(built) {
          const picture = document.getElementById('verdictWorld');
          picture.style.display = built ? 'block' : 'none';
          if (built) picture.src = '/api/verdictworld?t=' + Date.now();
        }

        // Live output: poll /api/run each second. Reload resumes same run; another tab's run replaces the view.
        const POLL_MILLIS = 1000;
        let shownRun = null;
        let shownLines = 0;
        let runOutput = '';
        let stopRequested = false;

        async function fetchJson(url, options) {
          const response = await fetch(url, options);
          const body = await response.json();
          if (!response.ok && response.status !== 409) {
            throw new Error(url + ' answered ' + response.status + ': ' + (body.error || JSON.stringify(body)));
          }
          return body;
        }

        async function startRunAndPoll(endpoint, options) {
          setRunning(true);
          try {
            const started = await fetchJson(endpoint, options);
            if (!started.started) {
              document.getElementById('testStatus').textContent = started.output;
              setRunning(false);
              return;
            }
            document.getElementById('testStatus').textContent = 'Starting ' + started.label + '...';
          } catch (failure) {
            document.getElementById('testStatus').textContent = 'Could not start: ' + failure.message;
            setRunning(false);
            return;
          }
          pollRun();
        }

        async function pollRun() {
          try {
            const query = shownRun === null ? 'from=0' : 'run=' + shownRun + '&from=' + shownLines;
            const snapshot = await fetchJson('/api/run?' + query);
            if (snapshot.none) {
              setRunning(false);
              return;
            }
            if (applyRunSnapshot(snapshot)) return;
          } catch (failure) {
            document.getElementById('testStatus').textContent = 'Lost contact with the editor server, retrying every second: ' + failure.message;
          }
          setTimeout(pollRun, POLL_MILLIS);
        }

        /** True once the run is over. */
        function applyRunSnapshot(snapshot) {
          if (snapshot.run !== shownRun) resetRunView(snapshot);
          appendRunLines(snapshot);
          setRunning(!snapshot.finished);
          if (snapshot.finished) {
            showRunResult(snapshot);
          } else if (!stopRequested) {
            document.getElementById('testStatus').textContent = 'Running ' + snapshot.label + '...';
          }
          return snapshot.finished;
        }

        function resetRunView(snapshot) {
          shownRun = snapshot.run;
          shownLines = 0;
          runOutput = '';
          stopRequested = false;
          document.getElementById('testReportLinks').innerHTML = '';
          document.getElementById('testOutput').textContent = '';
          showVerdictWorld(false);
        }

        function appendRunLines(snapshot) {
          shownLines = snapshot.next;
          if (snapshot.lines.length === 0) return;
          runOutput += snapshot.lines.join('\\n') + '\\n';
          const output = document.getElementById('testOutput');
          output.textContent = runOutput;
          output.scrollTop = output.scrollHeight;
        }

        function setRunning(running) {
          setGradleButtonsEnabled(!running);
          document.getElementById('stopRun').disabled = !running || stopRequested;
        }

        function showRunResult(snapshot) {
          const result = { exitCode: snapshot.exitCode, output: runOutput };
          document.getElementById('testStatus').textContent = snapshot.stopped ? 'STOPPED' : statusLabelOf(result);
          if (snapshot.label === 'Solve check') loadRanking();
          showVerdictWorld(!snapshot.stopped && /^WORLD_PICTURE: /m.test(runOutput));
          const linksDiv = document.getElementById('testReportLinks');
          const reportUrls = [...new Set(runOutput.match(/file:\\/\\/\\/\\S+\\.(html|txt)/g) || [])];
          reportUrls.forEach(url => {
            const link = document.createElement('a');
            link.href = url;
            link.textContent = 'Open ' + url.substring(url.lastIndexOf('/') + 1);
            link.target = '_blank';
            link.style.display = 'block';
            linksDiv.appendChild(link);
          });
        }

        async function stopRun() {
          stopRequested = true;
          setRunning(true);
          document.getElementById('testStatus').textContent = 'Stopping...';
          try {
            await fetchJson('/api/run/stop', { method: 'POST' });
          } catch (failure) {
            stopRequested = false;
            setRunning(true);
            document.getElementById('testStatus').textContent = 'Stop failed, the run is still going: ' + failure.message;
          }
        }

        pollRun();
        loadGraph();
        </script>
        </body>
        </html>
        """;

    private static final CurrentRun CURRENT_RUN = new CurrentRun();

    private GraphEditorServer() {
    }
}
