"""
worldgen/structure_classifier.py
=================================
Pure classification logic: maps structures to suppression/override categories.
No file I/O.
"""

from dataclasses import dataclass, field


@dataclass
class StructureClassification:
    exclusive_tag_biomes: dict[str, list[str]]        = field(default_factory=dict)
    shared_active_structs: list[tuple[str, list[str]]] = field(default_factory=list)
    skipped_no_tag: list[str]                         = field(default_factory=list)
    direct_suppressed: list[str]                      = field(default_factory=list)
    suppressed_tags: set[str]                         = field(default_factory=set)
    suppressed_structs: list[str]                     = field(default_factory=list)
    shared_conflicts: list[tuple[str, str]]           = field(default_factory=list)


def _map_active_structures_to_tags(
    selections: dict[str, list[str]],
    struct_tags: dict[str, list[str]],
    overworld_checked_structures: set[str],
) -> tuple[list[str], list[str], dict[str, list[tuple[str, list[str]]]]]:
    """
    For each selected (✓) structure, determine whether it is tag-based or direct-biome.
    Returns (skipped_no_tag, direct_suppressed, tag_to_active).
    """
    skipped_no_tag: list[str] = []
    direct_suppressed: list[str] = []
    tag_to_active: dict[str, list[tuple[str, list[str]]]] = {}

    for structure_id, biome_ids in selections.items():
        if structure_id not in struct_tags:
            if structure_id in overworld_checked_structures:
                skipped_no_tag.append(structure_id)
            else:
                direct_suppressed.append(structure_id)
            continue
        for tag_ref in struct_tags[structure_id]:
            tag_to_active.setdefault(tag_ref, []).append((structure_id, biome_ids))

    return skipped_no_tag, direct_suppressed, tag_to_active


def _split_tags_by_exclusivity(
    tag_to_active: dict[str, list[tuple[str, list[str]]]],
) -> tuple[dict[str, list[str]], list[tuple[str, list[str]]]]:
    """
    Returns (exclusive_tag_biomes, shared_active_structs).
    Tags owned by exactly one active structure get their biomes written directly.
    Tags shared by multiple active structures → each gets a controlled per-structure override.
    """
    exclusive_tag_biomes: dict[str, list[str]] = {}
    shared_active_structs: list[tuple[str, list[str]]] = []

    for tag_ref, entries in tag_to_active.items():
        if len(entries) == 1:
            structure_id, biome_ids = entries[0]
            exclusive_tag_biomes[tag_ref] = biome_ids
        else:
            for structure_id, biome_ids in entries:
                shared_active_structs.append((structure_id, biome_ids))

    return exclusive_tag_biomes, shared_active_structs


def _collect_inactive_suppressions(
    seen_all: set[str],
    selections: dict[str, list[str]],
    struct_tags: dict[str, list[str]],
    tag_to_active: dict[str, list[tuple[str, list[str]]]],
) -> tuple[set[str], list[str], list[str], list[tuple[str, str]]]:
    """
    For structures with no ✓ (all-✗), determine whether suppression is via biome tag
    or structure JSON override (when the tag is shared with an active structure).
    Returns (suppressed_tags, suppressed_structs, additional_direct_suppressed, shared_conflicts).
    """
    suppressed_tags: set[str] = set()
    suppressed_structs: list[str] = []
    additional_direct_suppressed: list[str] = []
    shared_conflicts: list[tuple[str, str]] = []

    for structure_id in seen_all:
        if structure_id in selections:
            continue
        if structure_id not in struct_tags:
            additional_direct_suppressed.append(structure_id)
            continue
        suppressed_structs.append(structure_id)
        for tag_ref in struct_tags[structure_id]:
            if tag_ref in tag_to_active:
                shared_conflicts.append((structure_id, tag_ref))
            else:
                suppressed_tags.add(tag_ref)

    return suppressed_tags, suppressed_structs, additional_direct_suppressed, shared_conflicts


def classify_all_structures(
    struct_tags: dict[str, list[str]],
    selections: dict[str, list[str]],
    seen_all: set[str],
    overworld_checked_structures: set[str],
) -> StructureClassification:
    """Classify every structure in the planner into one suppression/override category."""
    skipped_no_tag, direct_suppressed, tag_to_active = _map_active_structures_to_tags(
        selections, struct_tags, overworld_checked_structures,
    )
    exclusive_tag_biomes, shared_active_structs = _split_tags_by_exclusivity(tag_to_active)
    suppressed_tags, suppressed_structs, additional_direct_suppressed, shared_conflicts = (
        _collect_inactive_suppressions(seen_all, selections, struct_tags, tag_to_active)
    )
    return StructureClassification(
        exclusive_tag_biomes  = exclusive_tag_biomes,
        shared_active_structs = shared_active_structs,
        skipped_no_tag        = skipped_no_tag,
        direct_suppressed     = direct_suppressed + additional_direct_suppressed,
        suppressed_tags       = suppressed_tags,
        suppressed_structs    = suppressed_structs,
        shared_conflicts      = shared_conflicts,
    )
