package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeGraphData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.EdgeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.NodeData;
import com.mineutze.worldgen.biomegraph.BiomeGraphData.GroupData;
import com.mineutze.worldgen.biomegraph.BiomeId;
import com.mineutze.worldgen.biomegraph.BorderRequirement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads {@link BiomeGraphData} as plain rule data - the only point in this whole package
 * that touches anything from outside it (see the package's own doc for why: never a real
 * {@code BiomeGraph}, never the live solver/mesh). Everything downstream of this class
 * works only against these lookup tables.
 */
public final class RawRuleTable {

    private static final String LEGAL_GROUP_NAME = "[A-Za-z0-9_.-]+";

    private final List<BiomeId> allBiomeIds;
    private final Map<BiomeId, SizeRange> sizeRangeById;
    private final Set<UnorderedPair> legalPairs;
    private final Map<BiomeId, ResolvedBorderRequirement> requirementById;

    private RawRuleTable(
        List<BiomeId> allBiomeIds, Map<BiomeId, SizeRange> sizeRangeById, Set<UnorderedPair> legalPairs, Map<BiomeId, ResolvedBorderRequirement> requirementById
    ) {
        this.allBiomeIds = allBiomeIds;
        this.sizeRangeById = sizeRangeById;
        this.legalPairs = legalPairs;
        this.requirementById = requirementById;
    }

    /** Throws {@link IllegalArgumentException} for a group or requirement that names something that does not exist or cannot be read; nothing is guessed. */
    public static RawRuleTable from(BiomeGraphData data) {
        List<BiomeId> allBiomeIds = new ArrayList<>();
        Map<BiomeId, SizeRange> sizeRangeById = new LinkedHashMap<>();
        for (NodeData node : data.nodes()) {
            BiomeId id = new BiomeId(node.namespace(), node.path());
            allBiomeIds.add(id);
            sizeRangeById.put(id, new SizeRange(node.minWidthBlocks(), node.maxWidthBlocks()));
        }
        Set<UnorderedPair> legalPairs = new LinkedHashSet<>();
        for (EdgeData edge : data.edges()) {
            BiomeId a = new BiomeId(edge.aNamespace(), edge.aPath());
            BiomeId b = new BiomeId(edge.bNamespace(), edge.bPath());
            legalPairs.add(new UnorderedPair(a, b));
        }

        Set<BiomeId> known = Set.copyOf(allBiomeIds);
        Map<String, List<BiomeId>> groups = groupsOf(data, known);
        Map<BiomeId, ResolvedBorderRequirement> requirementById = new LinkedHashMap<>();
        for (NodeData node : data.nodes()) {
            BiomeId id = new BiomeId(node.namespace(), node.path());
            resolveRequirement(id, node.requirement(), groups, known).ifPresent(requirement -> requirementById.put(id, requirement));
        }
        return new RawRuleTable(List.copyOf(allBiomeIds), Map.copyOf(sizeRangeById), Set.copyOf(legalPairs), Map.copyOf(requirementById));
    }

    private static Map<String, List<BiomeId>> groupsOf(BiomeGraphData data, Set<BiomeId> known) {
        Map<String, List<BiomeId>> groups = new LinkedHashMap<>();
        for (GroupData group : data.groups()) {
            String lower = group.name().toLowerCase(Locale.ROOT);
            if (!group.name().matches(LEGAL_GROUP_NAME) || lower.equals("and") || lower.equals("or")) {
                throw new IllegalArgumentException(
                    "RawRuleTable: group name '" + group.name() + "' is not allowed - use letters, digits, '_', '.' or '-' (no ':', which marks a biome id), and not the words 'and' / 'or'"
                );
            }
            List<BiomeId> members = new ArrayList<>();
            for (String memberKey : group.members()) {
                BiomeId member = biomeOfKey(memberKey);
                if (!known.contains(member)) {
                    throw new IllegalArgumentException("RawRuleTable: group '" + group.name() + "' holds " + memberKey + ", which is not in the catalog");
                }
                members.add(member);
            }
            if (groups.put(group.name(), List.copyOf(members)) != null) {
                throw new IllegalArgumentException("RawRuleTable: there are two groups named '" + group.name() + "'");
            }
        }
        return groups;
    }

    private static BiomeId biomeOfKey(String key) {
        int colon = key.indexOf(':');
        if (colon <= 0 || colon == key.length() - 1) {
            throw new IllegalArgumentException("RawRuleTable: '" + key + "' is not a biome id (namespace:path)");
        }
        return new BiomeId(key.substring(0, colon), key.substring(colon + 1));
    }

    private static Optional<ResolvedBorderRequirement> resolveRequirement(BiomeId biome, String text, Map<String, List<BiomeId>> groups, Set<BiomeId> known) {
        Optional<BorderRequirement> written;
        try {
            written = BorderRequirement.parse(text);
        } catch (IllegalArgumentException unreadable) {
            throw new IllegalArgumentException("RawRuleTable: the requirement of " + biome + " cannot be read: " + unreadable.getMessage(), unreadable);
        }
        return written.map(requirement -> resolve(biome, requirement, groups, known));
    }

    private static ResolvedBorderRequirement resolve(BiomeId biome, BorderRequirement requirement, Map<String, List<BiomeId>> groups, Set<BiomeId> known) {
        return switch (requirement) {
            case BorderRequirement.Share share -> new ResolvedBorderRequirement.Share(share.subject(), biomesOfSubject(biome, share.subject(), groups, known), share.minSharedEdges());
            case BorderRequirement.AllOf all -> new ResolvedBorderRequirement.AllOf(all.parts().stream().map(part -> resolve(biome, part, groups, known)).toList());
            case BorderRequirement.AnyOf any -> new ResolvedBorderRequirement.AnyOf(any.parts().stream().map(part -> resolve(biome, part, groups, known)).toList());
        };
    }

    /** The biomes a subject stands for, the requiring biome itself left out: a biome cannot border itself. */
    private static List<BiomeId> biomesOfSubject(BiomeId biome, String subject, Map<String, List<BiomeId>> groups, Set<BiomeId> known) {
        List<BiomeId> named;
        if (subject.contains(":")) {
            BiomeId single = biomeOfKey(subject);
            if (!known.contains(single)) {
                throw new IllegalArgumentException("RawRuleTable: the requirement of " + biome + " names " + subject + ", which is not in the catalog");
            }
            named = List.of(single);
        } else {
            named = groups.get(subject);
            if (named == null) {
                throw new IllegalArgumentException("RawRuleTable: the requirement of " + biome + " names the group '" + subject + "', which does not exist");
            }
        }
        List<BiomeId> others = named.stream().filter(member -> !member.equals(biome)).toList();
        if (others.isEmpty() && !named.isEmpty()) {
            throw new IllegalArgumentException("RawRuleTable: the requirement of " + biome + ": '" + subject + "' holds only " + biome + " itself, which cannot border itself");
        }
        return others;
    }

    public List<BiomeId> allBiomeIds() {
        return allBiomeIds;
    }

    public SizeRange sizeRangeOf(BiomeId id) {
        SizeRange range = sizeRangeById.get(id);
        if (range == null) {
            throw new IllegalArgumentException("RawRuleTable: unknown biome " + id);
        }
        return range;
    }

    /** Unconditional: a biome is never legally adjacent to itself, no exception - not just "usually false in the rule table." */
    public boolean isLegal(BiomeId a, BiomeId b) {
        if (a.equals(b)) {
            return false;
        }
        return legalPairs.contains(new UnorderedPair(a, b));
    }

    /** The borders every cluster of {@code id} must share, if it has any requirement. */
    Optional<ResolvedBorderRequirement> requirementOf(BiomeId id) {
        return Optional.ofNullable(requirementById.get(id));
    }

    public record SizeRange(int minWidthBlocks, int maxWidthBlocks) {
        public SizeRange {
            if (minWidthBlocks <= 0 || maxWidthBlocks < minWidthBlocks) {
                throw new IllegalArgumentException("RawRuleTable: invalid size range [" + minWidthBlocks + "," + maxWidthBlocks + "]");
            }
        }
    }

    /** Canonicalized so {a,b} and {b,a} are the same key - order never matters for legality. */
    record UnorderedPair(BiomeId a, BiomeId b) {
        UnorderedPair {
            if (a.compareTo(b) > 0) {
                BiomeId swap = a;
                a = b;
                b = swap;
            }
        }
    }
}
