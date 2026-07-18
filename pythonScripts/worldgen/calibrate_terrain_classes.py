"""
worldgen/calibrate_terrain_classes.py
=======================================
Suggests real TerrainClassifier.java thresholds (erosion/weirdness cutoffs for
flat_land/hilly/mountainous) from a /dump_height_samples log capture, instead of the
placeholder guesses currently hardcoded there. See DESIGN.md Sec 11 / the implementation
plan's rollout Milestone 3b -- this modpack's real noise router (BoP/BWG layered on top
of vanilla) is not guaranteed to match vanilla's own breakpoints, so the placeholder
thresholds are genuine guesses until this has been run against real generated terrain.

Standalone script, no dependency on the rest of pythonScripts/worldgen/ -- the Python
patch-growth algorithm port it used to sit alongside was dropped (see DESIGN.md) once it
became clear a plain-Java self-test (companion-mods/mineutze-worldgen's
PatchGrowthEngineSelfTest, `./gradlew selfTest`) covers the same ground faster and
without hand-porting every algorithm change to two languages. This script doesn't need
any of that -- it's pure log parsing and bucket math.

Workflow:
    1. In-game: /dump_height_samples [radius] [step] (fly over varied terrain first --
       flat, hilly, and mountainous areas -- run it in each so the sample set actually
       spans the range you're trying to calibrate against).
    2. Copy the "===== DUMP START (height_samples) =====" ... "DUMP END" block(s) out of
       logs/latest.log into a text file (or point this script at logs/latest.log
       directly -- it only reads matching blocks, extra log noise is fine).
    3. python pythonScripts/worldgen/calibrate_terrain_classes.py <log_file> [...]
       Multiple files/captures are fine -- samples from all of them are pooled.

What "roughness" means here: for each sample, the max height difference to its
immediate grid neighbors (the dump is on a regular grid, spacing = the `step` argument
you ran it with) -- a cheap, local proxy for "how jagged is the terrain right here"
without needing the full noise router. This is exactly the same proxy DESIGN.md Sec 4
already accepts ("a coarser, cheaper proxy than actually walking the density function
stack") for the classifier itself, just derived empirically instead of guessed.
"""

import re
import sys
from pathlib import Path

DUMP_START_PATTERN = re.compile(r'===== DUMP START \(height_samples\) =====')
DUMP_END_PATTERN   = re.compile(r'===== DUMP END \(height_samples\) =====')
SAMPLE_LINE_PATTERN = re.compile(
    r'^(-?\d+),(-?\d+),(-?[\d.eE+-]+),(-?[\d.eE+-]+),(-?[\d.eE+-]+),(-?\d+)\s*$'
)

# Matches TerrainClassifier.java's existing ocean/coast split -- calibration here only
# concerns the land-side flat/hilly/mountainous cutoffs.
OCEAN_CONTINENTALNESS_HI = -0.19
COAST_CONTINENTALNESS_HI =  0.03

EROSION_BUCKET_COUNT   = 10
WEIRDNESS_BUCKET_COUNT = 10
HILLY_ROUGHNESS_FRACTION       = 0.25  # buckets above this fraction of max roughness -> hilly candidate
MOUNTAINOUS_ROUGHNESS_FRACTION = 0.55  # buckets above this fraction of max roughness -> mountainous candidate


def parse_dump_blocks(text: str) -> list[dict]:
    """Extracts every (x, z, continentalness, erosion, weirdness, height) sample from
    all DUMP START/END blocks found in text, wherever they appear in a noisy log file."""
    samples = []
    lines = text.splitlines()
    inside_block = False
    for line in lines:
        if DUMP_START_PATTERN.search(line):
            inside_block = True
            continue
        if DUMP_END_PATTERN.search(line):
            inside_block = False
            continue
        if not inside_block:
            continue
        match = SAMPLE_LINE_PATTERN.match(line.strip())
        if not match:
            continue  # header row or a stray log-prefix line, skip
        x, z, continentalness, erosion, weirdness, height = match.groups()
        samples.append({
            'x': int(x), 'z': int(z),
            'continentalness': float(continentalness),
            'erosion': float(erosion),
            'weirdness': float(weirdness),
            'height': int(height),
        })
    return samples


def detect_grid_step(samples: list[dict]) -> int:
    """Grid spacing the dump command was run with -- inferred from the smallest positive
    gap between distinct x coordinates, since the command always samples on a regular grid."""
    xs = sorted({sample['x'] for sample in samples})
    gaps = [b - a for a, b in zip(xs, xs[1:]) if b > a]
    return min(gaps) if gaps else 1


def compute_roughness(samples: list[dict], step: int) -> None:
    """Adds a 'roughness' key to each sample: max height difference to its immediate
    grid neighbors (in-place). Samples missing a neighbor (dump area edge) use whatever
    neighbors are present -- never skipped outright, just a smaller comparison set."""
    height_by_position = {(sample['x'], sample['z']): sample['height'] for sample in samples}
    for sample in samples:
        x, z = sample['x'], sample['z']
        neighbor_heights = [
            height_by_position[(x + dx, z + dz)]
            for dx, dz in ((step, 0), (-step, 0), (0, step), (0, -step))
            if (x + dx, z + dz) in height_by_position
        ]
        sample['roughness'] = max(
            (abs(sample['height'] - neighbor_height) for neighbor_height in neighbor_heights),
            default=0,
        )


def bucket_average_roughness(samples: list[dict], key: str, bucket_count: int) -> list[tuple[float, float, int]]:
    """Returns [(bucket_lo, avg_roughness, sample_count), ...] sorted by bucket_lo."""
    values = [sample[key] for sample in samples]
    lo, hi = min(values), max(values)
    if hi <= lo:
        return [(lo, sum(sample['roughness'] for sample in samples) / len(samples), len(samples))]

    bucket_width = (hi - lo) / bucket_count
    buckets: dict[int, list[float]] = {}
    for sample in samples:
        bucket_index = min(bucket_count - 1, int((sample[key] - lo) / bucket_width))
        buckets.setdefault(bucket_index, []).append(sample['roughness'])

    return [
        (lo + bucket_index * bucket_width, sum(roughness_values) / len(roughness_values), len(roughness_values))
        for bucket_index, roughness_values in sorted(buckets.items())
    ]


def print_bucket_table(title: str, buckets: list[tuple[float, float, int]]) -> None:
    print(f'\n{title}')
    print(f'{"bucket lo":>12}  {"avg roughness":>14}  {"samples":>8}')
    for bucket_lo, avg_roughness, sample_count in buckets:
        print(f'{bucket_lo:>12.3f}  {avg_roughness:>14.2f}  {sample_count:>8}')


def suggest_erosion_thresholds(buckets: list[tuple[float, float, int]]) -> tuple[float, float]:
    """Returns (mountainous_erosion_hi, hilly_erosion_hi) -- erosion is a 'how bumpy'
    axis where LOW values are mountainous-eligible in the current placeholder scheme, so
    thresholds are picked scanning low-to-high erosion for where avg roughness first
    drops below each fraction of the observed max."""
    max_roughness = max(avg_roughness for _, avg_roughness, _ in buckets) or 1.0
    mountainous_hi = buckets[0][0]
    hilly_hi = buckets[0][0]
    for bucket_lo, avg_roughness, _ in buckets:
        if avg_roughness >= max_roughness * MOUNTAINOUS_ROUGHNESS_FRACTION:
            mountainous_hi = bucket_lo
        if avg_roughness >= max_roughness * HILLY_ROUGHNESS_FRACTION:
            hilly_hi = bucket_lo
    return mountainous_hi, hilly_hi


def suggest_weirdness_threshold(buckets: list[tuple[float, float, int]]) -> float:
    """Returns mountainous_weirdness_abs_lo -- weirdness bucketed by |weirdness| here, so
    thresholds are picked scanning high-to-low for where avg roughness first drops below
    the mountainous fraction."""
    max_roughness = max(avg_roughness for _, avg_roughness, _ in buckets) or 1.0
    threshold = buckets[-1][0]
    for bucket_lo, avg_roughness, _ in reversed(buckets):
        if avg_roughness >= max_roughness * MOUNTAINOUS_ROUGHNESS_FRACTION:
            threshold = bucket_lo
    return threshold


def main() -> None:
    if len(sys.argv) < 2:
        print('Usage: python calibrate_terrain_classes.py <log_file> [log_file...]')
        raise SystemExit(1)

    all_samples: list[dict] = []
    for log_path in sys.argv[1:]:
        text = Path(log_path).read_text(encoding='utf-8', errors='ignore')
        found = parse_dump_blocks(text)
        print(f'{log_path}: {len(found)} samples')
        all_samples.extend(found)

    if not all_samples:
        print('No /dump_height_samples blocks found in the given file(s).')
        raise SystemExit(1)

    step = detect_grid_step(all_samples)
    print(f'\nTotal samples: {len(all_samples)}  (detected grid step: {step} blocks)')
    compute_roughness(all_samples, step)

    land_samples = [s for s in all_samples if s['continentalness'] >= COAST_CONTINENTALNESS_HI]
    print(f'Land samples (continentalness >= {COAST_CONTINENTALNESS_HI}): {len(land_samples)}')
    if not land_samples:
        print('No land samples -- capture more terrain (fly inland) and re-run.')
        raise SystemExit(1)

    for sample in land_samples:
        sample['abs_weirdness'] = abs(sample['weirdness'])

    erosion_buckets = bucket_average_roughness(land_samples, 'erosion', EROSION_BUCKET_COUNT)
    print_bucket_table('Roughness by erosion bucket (low erosion = more mountainous in current scheme):', erosion_buckets)
    mountainous_erosion_hi, hilly_erosion_hi = suggest_erosion_thresholds(erosion_buckets)

    weirdness_buckets = bucket_average_roughness(land_samples, 'abs_weirdness', WEIRDNESS_BUCKET_COUNT)
    print_bucket_table('Roughness by |weirdness| bucket:', weirdness_buckets)
    mountainous_weirdness_abs_lo = suggest_weirdness_threshold(weirdness_buckets)

    print('\nSuggested TerrainClassifier thresholds (sanity-check against the tables above, don\'t paste blindly):')
    print(f'  MOUNTAINOUS_EROSION_HI       = {mountainous_erosion_hi:.3f}f')
    print(f'  HILLY_EROSION_HI             = {hilly_erosion_hi:.3f}f')
    print(f'  MOUNTAINOUS_WEIRDNESS_ABS_LO = {mountainous_weirdness_abs_lo:.3f}f')
    print('\nUpdate TerrainClassifier.java (companion-mods/mineutze-worldgen) with these values.')


if __name__ == '__main__':
    main()
