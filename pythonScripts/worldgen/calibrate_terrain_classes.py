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
       spans the range you're trying to calibrate against). /dump_height_samples walk
       automates re-positioning between captures -- see its own command doc.
    2. Copy the "===== DUMP START (height_samples) =====" ... "DUMP END" block(s) out of
       logs/latest.log into a text file (or point this script at logs/latest.log
       directly -- it only reads matching blocks, extra log noise is fine).
    3. python pythonScripts/worldgen/calibrate_terrain_classes.py <log_file> [...]
       Multiple files/captures are fine -- samples from all of them are pooled.

Persisting across sessions (--world):
    logs/latest.log gets rotated away on every game relaunch, so a capture session that
    spans multiple play sessions needs somewhere durable to accumulate into. Pass
    --world <name> (anything -- typically the save folder's name) and every run:
      1. Loads the highest-numbered calibration_data/<world>_NNNN.csv already saved for
         that world, if any (samples from every prior session).
      2. Parses whatever new log file(s) you passed on the command line this time.
      3. Merges the two, deduped by (x, z) -- same seed means the same column always
         yields the same climate/height, so overlapping capture areas from different
         sessions just collapse rather than double-count.
      4. Writes the merged total to calibration_data/<world>_(NNNN+1).csv (source-of-
         truth for next time; previous snapshots are left alone, cheap backups) and runs
         the usual bucket analysis against the FULL accumulated set, not just this run's
         new samples.
    Come back later, run the same command with a fresh logs/latest.log -- it picks up
    exactly where it left off. --world with zero log files is valid too (just re-analyzes
    whatever's already saved). Omit --world entirely for the old one-shot, no-persistence
    behavior.

What "roughness" means here: for each sample, the max height difference to its
immediate grid neighbors (the dump is on a regular grid, spacing = the `step` argument
you ran it with) -- a cheap, local proxy for "how jagged is the terrain right here"
without needing the full noise router. This is exactly the same proxy DESIGN.md Sec 4
already accepts ("a coarser, cheaper proxy than actually walking the density function
stack") for the classifier itself, just derived empirically instead of guessed.

What "depression" means (river/lake calibration specifically): how far BELOW the
average of its immediate neighbors a sample sits -- positive means a local low point
(a carved channel), negative means a local high point (a peak/ridge). Roughness alone
can't distinguish a river dip from a spire (both look "rough" -- roughness is an
undirected |difference|); depression is the directed signal that actually tells them
apart, which is what makes it possible to calibrate TerrainClassifier's
RIVER_WEIRDNESS_ABS_HI the same empirical way the erosion/weirdness thresholds are.

RIVER_WEIRDNESS_ABS_HI ground truth (/dump_river_samples): the depression-proxy approach
above kept failing its own sanity check on this modpack's real data (peak depression not
near weirdness=0) even after sessions spent deliberately flying along visible rivers --
likely because Tectonic redirects weirdness/ridges through its own custom noise, not
vanilla's. /dump_river_samples [radius] [step] bulk-scans a grid the same way
/dump_height_samples does but only logs a column if its real surface block is confirmed
water -- ground truth, not inference. Point this script at a log containing that
command's output (same log file works fine, both commands write to logs/latest.log) and
it reports a direct percentile-based suggestion from confirmed water samples' real
abs(weirdness) values instead, preferred over the depression-proxy number whenever
ground-truth data is present. --world persistence covers this data too, in a separate
calibration_data/<world>_rivers_NNNN.csv series (kept apart from the height-sample
series so one never gets mistaken for the other).
"""

import argparse
import csv
import re
from collections import Counter
from pathlib import Path

DUMP_START_PATTERN = re.compile(r'===== DUMP START \(height_samples\) =====')
DUMP_END_PATTERN   = re.compile(r'===== DUMP END \(height_samples\) =====')
# /dump_river_samples writes the identical row format under a different DUMP marker --
# ground-truth confirmed-water columns, not the full grid. See suggest_river_threshold's
# own docstring for why the depression-proxy approach below needed a ground-truth
# alternative in the first place.
RIVER_DUMP_START_PATTERN = re.compile(r'===== DUMP START \(river_samples\) =====')
RIVER_DUMP_END_PATTERN   = re.compile(r'===== DUMP END \(river_samples\) =====')
SAMPLE_LINE_PATTERN = re.compile(
    r'^(-?\d+),(-?\d+),(-?[\d.eE+-]+),(-?[\d.eE+-]+),(-?[\d.eE+-]+),(-?\d+)\s*$'
)

CALIBRATION_DATA_DIR = Path(__file__).parent / 'calibration_data'
CUMULATIVE_FILE_PATTERN_TEMPLATE = r'^{world}_(\d+)\.csv$'
CUMULATIVE_RIVER_FILE_PATTERN_TEMPLATE = r'^{world}_rivers_(\d+)\.csv$'
CUMULATIVE_CSV_FIELDNAMES = ['x', 'z', 'continentalness', 'erosion', 'weirdness', 'height']

# Suggested RIVER_WEIRDNESS_ABS_HI (ground-truth method) covers this percentile of
# confirmed water samples' abs(weirdness) -- not the max, so a single outlier column
# (a stray puddle, a lake arm at unusual weirdness) doesn't blow the band out wide.
RIVER_WEIRDNESS_PERCENTILE = 90

# Matches TerrainClassifier.java's existing ocean/coast split -- calibration here only
# concerns the land-side flat/hilly/mountainous cutoffs.
OCEAN_CONTINENTALNESS_HI = -0.19
COAST_CONTINENTALNESS_HI =  0.03

EROSION_BUCKET_COUNT   = 10
WEIRDNESS_BUCKET_COUNT = 10
# Rivers are typically a much narrower band of |weirdness| than the broad mountainous/
# hilly zones -- confirmed by a synthetic-data smoke test where 10 buckets (width 0.2
# over the full [-1,1] range) diluted a known 0.08-wide test band into its neighbor and
# under-suggested the threshold. Finer resolution just for the river analysis.
RIVER_WEIRDNESS_BUCKET_COUNT   = 40
HILLY_ROUGHNESS_FRACTION       = 0.25  # buckets above this fraction of max roughness -> hilly candidate
MOUNTAINOUS_ROUGHNESS_FRACTION = 0.55  # buckets above this fraction of max roughness -> mountainous candidate
RIVER_DEPRESSION_FRACTION      = 0.40  # buckets above this fraction of peak depression -> river-band candidate


def parse_generic_dump_blocks(text: str, start_pattern: re.Pattern, end_pattern: re.Pattern) -> list[dict]:
    """Extracts every (x, z, continentalness, erosion, weirdness, height) sample from
    all matching DUMP START/END blocks found in text, wherever they appear in a noisy
    log file. Shared by parse_dump_blocks and parse_river_dump_blocks -- both commands
    write the identical row format, only the marker differs."""
    samples = []
    inside_block = False
    for line in text.splitlines():
        if start_pattern.search(line):
            inside_block = True
            continue
        if end_pattern.search(line):
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


def parse_dump_blocks(text: str) -> list[dict]:
    return parse_generic_dump_blocks(text, DUMP_START_PATTERN, DUMP_END_PATTERN)


def parse_river_dump_blocks(text: str) -> list[dict]:
    """/dump_river_samples output: confirmed-water columns only, ground truth for
    RIVER_WEIRDNESS_ABS_HI instead of the depression-proxy inference below."""
    return parse_generic_dump_blocks(text, RIVER_DUMP_START_PATTERN, RIVER_DUMP_END_PATTERN)


def percentile(sorted_values: list[float], pct: float) -> float:
    """Nearest-rank percentile (no interpolation) -- simple and good enough for a
    threshold suggestion, keeps this script dependency-free (no numpy)."""
    if not sorted_values:
        return 0.0
    index = max(0, min(len(sorted_values) - 1, round(pct / 100 * (len(sorted_values) - 1))))
    return sorted_values[index]


def suggest_river_threshold_from_ground_truth(river_samples: list[dict]) -> float | None:
    """Direct ground truth from /dump_river_samples' confirmed water columns, not the
    depression-proxy inference suggest_river_threshold below relies on -- built because
    that approach kept failing its own sanity check (peak depression not near
    weirdness=0) even after a session spent deliberately flying along real rivers.
    Filters to land-side water (continentalness >= COAST_CONTINENTALNESS_HI) the same
    way land_samples does, so open ocean doesn't dilute the river-specific signal.
    Returns None if there's no land-side water data to suggest from at all."""
    land_river_samples = [s for s in river_samples if s['continentalness'] >= COAST_CONTINENTALNESS_HI]
    if not land_river_samples:
        return None
    abs_weirdness_values = sorted(abs(s['weirdness']) for s in land_river_samples)
    return percentile(abs_weirdness_values, RIVER_WEIRDNESS_PERCENTILE)


def detect_grid_step(samples: list[dict]) -> int:
    """Grid spacing the dump command was run with -- inferred from the MOST COMMON
    positive gap between distinct x coordinates, not the smallest one. /dump_height_samples
    walk pools multiple hops into one capture, each centered at a different, generally
    non-step-aligned position (a rounded trig offset, not a clean multiple of step) -- so
    the union of several independently-8-spaced grids interleaves and produces a handful
    of small stray gaps (confirmed via real data: gap histogram [(8, 560), (7, 16), (1, 8)]
    for a true step-8 capture) alongside the real dominant gap. min() picks up that noise
    every time real multi-hop data is used; the mode is what actually recovers the step
    the command was run with. A real, confirmed bug -- this is why roughness read as ~0
    everywhere on real multi-hop captures: _neighbor_heights looked for a neighbor at the
    wrong (noise) offset, almost never finding one, silently defaulting to 0 roughness."""
    xs = sorted({sample['x'] for sample in samples})
    gaps = [b - a for a, b in zip(xs, xs[1:]) if b > a]
    if not gaps:
        return 1
    gap_counts = Counter(gaps)
    return gap_counts.most_common(1)[0][0]


def _neighbor_heights(x: int, z: int, step: int, height_by_position: dict) -> list[int]:
    return [
        height_by_position[(x + dx, z + dz)]
        for dx, dz in ((step, 0), (-step, 0), (0, step), (0, -step))
        if (x + dx, z + dz) in height_by_position
    ]


def compute_local_height_features(samples: list[dict], step: int) -> None:
    """Adds 'roughness' and 'depression' keys to each sample (in-place), both derived
    from the same immediate-grid-neighbor heights. Samples missing a neighbor (dump area
    edge) use whatever neighbors are present -- never skipped outright, just a smaller
    comparison set.

    roughness: max height difference to any neighbor -- undirected, "how jagged is it
    right here" (mountains AND river banks both score high here).

    depression: average(neighbor heights) - this sample's height -- directed, positive
    means this sample sits in a local dip (a carved channel), negative means a local
    high point. This is what separates a river reading from a cliff/spire reading, which
    roughness alone can't do -- see the module docstring.
    """
    height_by_position = {(sample['x'], sample['z']): sample['height'] for sample in samples}
    for sample in samples:
        neighbor_heights = _neighbor_heights(sample['x'], sample['z'], step, height_by_position)
        sample['roughness'] = max(
            (abs(sample['height'] - neighbor_height) for neighbor_height in neighbor_heights),
            default=0,
        )
        sample['depression'] = (
            sum(neighbor_heights) / len(neighbor_heights) - sample['height'] if neighbor_heights else 0.0
        )


def bucket_average(samples: list[dict], bucket_key: str, value_key: str, bucket_count: int) -> list[tuple[float, float, int]]:
    """Returns [(bucket_lo, avg_value, sample_count), ...] sorted by bucket_lo, bucketing
    samples by bucket_key and averaging value_key within each bucket."""
    bucket_values = [sample[bucket_key] for sample in samples]
    lo, hi = min(bucket_values), max(bucket_values)
    if hi <= lo:
        return [(lo, sum(sample[value_key] for sample in samples) / len(samples), len(samples))]

    bucket_width = (hi - lo) / bucket_count
    buckets: dict[int, list[float]] = {}
    for sample in samples:
        bucket_index = min(bucket_count - 1, int((sample[bucket_key] - lo) / bucket_width))
        buckets.setdefault(bucket_index, []).append(sample[value_key])

    return [
        (lo + bucket_index * bucket_width, sum(values) / len(values), len(values))
        for bucket_index, values in sorted(buckets.items())
    ]


def print_bucket_table(title: str, buckets: list[tuple[float, float, int]], value_label: str = 'avg roughness') -> None:
    print(f'\n{title}')
    print(f'{"bucket lo":>12}  {value_label:>14}  {"samples":>8}')
    for bucket_lo, avg_value, sample_count in buckets:
        print(f'{bucket_lo:>12.3f}  {avg_value:>14.2f}  {sample_count:>8}')


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


def suggest_river_threshold(buckets: list[tuple[float, float, int]]) -> float:
    """Returns river_weirdness_abs_hi -- buckets are by |weirdness| ascending, so this
    scans outward from zero and returns the largest |weirdness| still inside the
    contiguous high-depression band starting at zero. Stops at the first drop-off
    (unlike the mountainous scan, which keeps scanning the whole range) because a real
    river band should be one contiguous region around weirdness=0 -- a high-depression
    bucket found far from zero after the band has already ended is more likely a cliff
    base (depression is undirected-confoundable, see compute_local_height_features) than
    a second river band, and shouldn't extend the suggested threshold outward."""
    max_depression = max(avg_depression for _, avg_depression, _ in buckets)
    if max_depression <= 0:
        return buckets[0][0]  # no real depression signal near zero -- don't suggest a band at all
    threshold = buckets[0][0]
    for bucket_lo, avg_depression, _ in buckets:
        if avg_depression < max_depression * RIVER_DEPRESSION_FRACTION:
            break
        threshold = bucket_lo
    return threshold


def find_latest_cumulative_file(world_name: str, river: bool = False) -> Path | None:
    """Highest-sequence calibration_data/<world_name>[_rivers]_NNNN.csv already saved
    for this world, or None if this world has never been saved before (in that
    category). Sequence compared numerically, not lexically, so _10 correctly sorts
    after _9. river=True targets the separate river-samples file series -- same world
    name, different content, kept apart so one doesn't get mistaken for the other."""
    if not CALIBRATION_DATA_DIR.is_dir():
        return None
    template = CUMULATIVE_RIVER_FILE_PATTERN_TEMPLATE if river else CUMULATIVE_FILE_PATTERN_TEMPLATE
    pattern = re.compile(template.format(world=re.escape(world_name)))
    candidates = [
        (int(match.group(1)), path)
        for path in CALIBRATION_DATA_DIR.iterdir()
        if (match := pattern.match(path.name))
    ]
    return max(candidates, key=lambda pair: pair[0])[1] if candidates else None


def cumulative_file_sequence(path: Path, world_name: str, river: bool = False) -> int:
    template = CUMULATIVE_RIVER_FILE_PATTERN_TEMPLATE if river else CUMULATIVE_FILE_PATTERN_TEMPLATE
    match = re.match(template.format(world=re.escape(world_name)), path.name)
    return int(match.group(1))


def load_cumulative_samples(path: Path) -> list[dict]:
    with path.open(newline='', encoding='utf-8') as csv_file:
        return [
            {
                'x': int(row['x']), 'z': int(row['z']),
                'continentalness': float(row['continentalness']),
                'erosion': float(row['erosion']),
                'weirdness': float(row['weirdness']),
                'height': int(row['height']),
            }
            for row in csv.DictReader(csv_file)
        ]


def write_cumulative_samples(path: Path, samples: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('w', newline='', encoding='utf-8') as csv_file:
        writer = csv.DictWriter(csv_file, fieldnames=CUMULATIVE_CSV_FIELDNAMES)
        writer.writeheader()
        for sample in samples:
            writer.writerow({field: sample[field] for field in CUMULATIVE_CSV_FIELDNAMES})


def merge_samples_deduped(existing: list[dict], new: list[dict]) -> list[dict]:
    """Dedupes by (x, z) -- same seed means the same column always yields the same
    climate/height, so a recaptured column from an earlier session is a duplicate, not
    new information. New samples win on conflict (should be identical anyway, but a
    newer capture is the more trustworthy one if something ever doesn't match)."""
    by_position = {(sample['x'], sample['z']): sample for sample in existing}
    for sample in new:
        by_position[(sample['x'], sample['z'])] = sample
    return list(by_position.values())


def main() -> None:
    parser = argparse.ArgumentParser(
        description='Suggest TerrainClassifier.java thresholds from /dump_height_samples captures.'
    )
    parser.add_argument('log_files', nargs='*', help='Log file(s) containing /dump_height_samples DUMP blocks.')
    parser.add_argument('--world', help=(
        'Persist cumulative samples for this world across sessions -- see this module\'s '
        'docstring ("Persisting across sessions") for the exact behavior.'
    ))
    args = parser.parse_args()

    all_samples: list[dict] = []
    persisted_path = find_latest_cumulative_file(args.world) if args.world else None
    if args.world:
        if persisted_path:
            persisted_samples = load_cumulative_samples(persisted_path)
            print(f'{persisted_path}: {len(persisted_samples)} samples from previous sessions')
            all_samples.extend(persisted_samples)
        else:
            print(f'No previous calibration_data found for world "{args.world}" -- starting fresh.')

    new_samples: list[dict] = []
    new_river_samples: list[dict] = []
    for log_path in args.log_files:
        text = Path(log_path).read_text(encoding='utf-8', errors='ignore')
        found = parse_dump_blocks(text)
        found_river = parse_river_dump_blocks(text)
        print(f'{log_path}: {len(found)} height samples, {len(found_river)} river samples')
        new_samples.extend(found)
        new_river_samples.extend(found_river)

    all_river_samples: list[dict] = []
    river_persisted_path = find_latest_cumulative_file(args.world, river=True) if args.world else None
    if args.world and river_persisted_path:
        persisted_river_samples = load_cumulative_samples(river_persisted_path)
        print(f'{river_persisted_path}: {len(persisted_river_samples)} river samples from previous sessions')
        all_river_samples.extend(persisted_river_samples)

    if args.world:
        all_samples = merge_samples_deduped(all_samples, new_samples)
        next_sequence = cumulative_file_sequence(persisted_path, args.world) + 1 if persisted_path else 1
        output_path = CALIBRATION_DATA_DIR / f'{args.world}_{next_sequence:04d}.csv'
        write_cumulative_samples(output_path, all_samples)
        print(f'Wrote {len(all_samples)} cumulative samples to {output_path}')

        all_river_samples = merge_samples_deduped(all_river_samples, new_river_samples)
        if all_river_samples:
            river_next_sequence = cumulative_file_sequence(river_persisted_path, args.world, river=True) + 1 if river_persisted_path else 1
            river_output_path = CALIBRATION_DATA_DIR / f'{args.world}_rivers_{river_next_sequence:04d}.csv'
            write_cumulative_samples(river_output_path, all_river_samples)
            print(f'Wrote {len(all_river_samples)} cumulative river samples to {river_output_path}')
    else:
        all_samples.extend(new_samples)
        all_river_samples.extend(new_river_samples)

    if not all_samples:
        print('No samples to analyze -- pass at least one log file, or --world with previously saved data.')
        raise SystemExit(1)

    step = detect_grid_step(all_samples)
    print(f'\nTotal samples: {len(all_samples)}  (detected grid step: {step} blocks)')
    compute_local_height_features(all_samples, step)

    land_samples = [s for s in all_samples if s['continentalness'] >= COAST_CONTINENTALNESS_HI]
    print(f'Land samples (continentalness >= {COAST_CONTINENTALNESS_HI}): {len(land_samples)}')
    if not land_samples:
        print('No land samples -- capture more terrain (fly inland) and re-run.')
        raise SystemExit(1)

    for sample in land_samples:
        sample['abs_weirdness'] = abs(sample['weirdness'])

    erosion_buckets = bucket_average(land_samples, 'erosion', 'roughness', EROSION_BUCKET_COUNT)
    print_bucket_table('Roughness by erosion bucket (low erosion = more mountainous in current scheme):', erosion_buckets)
    mountainous_erosion_hi, hilly_erosion_hi = suggest_erosion_thresholds(erosion_buckets)

    weirdness_buckets = bucket_average(land_samples, 'abs_weirdness', 'roughness', WEIRDNESS_BUCKET_COUNT)
    print_bucket_table('Roughness by |weirdness| bucket:', weirdness_buckets)
    mountainous_weirdness_abs_lo = suggest_weirdness_threshold(weirdness_buckets)

    raw_weirdness_depression_buckets = bucket_average(land_samples, 'weirdness', 'depression', RIVER_WEIRDNESS_BUCKET_COUNT)
    print_bucket_table(
        'Depression by RAW weirdness bucket (sanity check: should peak near 0 if the river band is actually centered there):',
        raw_weirdness_depression_buckets, value_label='avg depression'
    )
    abs_weirdness_depression_buckets = bucket_average(land_samples, 'abs_weirdness', 'depression', RIVER_WEIRDNESS_BUCKET_COUNT)
    print_bucket_table('Depression by |weirdness| bucket:', abs_weirdness_depression_buckets, value_label='avg depression')
    river_weirdness_abs_hi = suggest_river_threshold(abs_weirdness_depression_buckets)

    ground_truth_river_threshold = suggest_river_threshold_from_ground_truth(all_river_samples)
    if all_river_samples:
        land_river_samples = [s for s in all_river_samples if s['continentalness'] >= COAST_CONTINENTALNESS_HI]
        print(f'\nGround-truth river/water samples (/dump_river_samples): {len(all_river_samples)} total, '
              f'{len(land_river_samples)} on land (continentalness >= {COAST_CONTINENTALNESS_HI})')
        if land_river_samples:
            abs_weirdness_values = sorted(abs(s['weirdness']) for s in land_river_samples)
            print(f'  abs(weirdness): min={abs_weirdness_values[0]:.3f} '
                  f'median={percentile(abs_weirdness_values, 50):.3f} max={abs_weirdness_values[-1]:.3f}')

    print('\nSuggested TerrainClassifier thresholds (sanity-check against the tables above, don\'t paste blindly):')
    print(f'  MOUNTAINOUS_EROSION_HI       = {mountainous_erosion_hi:.3f}f')
    print(f'  HILLY_EROSION_HI             = {hilly_erosion_hi:.3f}f')
    print(f'  MOUNTAINOUS_WEIRDNESS_ABS_LO = {mountainous_weirdness_abs_lo:.3f}f')
    if ground_truth_river_threshold is not None:
        print(f'  RIVER_WEIRDNESS_ABS_HI       = {ground_truth_river_threshold:.3f}f  '
              f'(ground truth, {RIVER_WEIRDNESS_PERCENTILE}th percentile of confirmed water -- use this one)')
        print(f'    depression-proxy alternative was {river_weirdness_abs_hi:.3f}f -- prefer the ground-truth')
        print('    value above; the depression proxy has repeatedly failed its own sanity check on this')
        print('    modpack\'s real data (see the RAW weirdness depression table -- peak not near 0).')
    else:
        print(f'  RIVER_WEIRDNESS_ABS_HI       = {river_weirdness_abs_hi:.3f}f  (depression-proxy, UNTRUSTED --')
        print('    run /dump_river_samples for a ground-truth suggestion instead; check the RAW weirdness')
        print('    depression table above first -- if the peak isn\'t near 0, this number is not trustworthy)')
    print('\nUpdate TerrainClassifier.java (companion-mods/mineutze-worldgen) with these values.')


if __name__ == '__main__':
    main()
