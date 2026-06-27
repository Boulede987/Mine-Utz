"""
worldgen/biome_noise.py
========================
Generates synthetic climate parameter maps using bandlimited noise, for use
by both the standalone world-map visualizer and the interactive Streamlit app.

Each climate axis gets a different spatial scale (cutoff wavelength) to mirror
Minecraft's layered noise system: long-wavelength continentalness/temperature
creates large smooth zones; short-wavelength weirdness creates rapid local variation.
"""

import numpy as np

from worldgen.constants import PARAMETER_AXES

SURFACE_DEPTH_VALUE = 0.0

# Larger cutoff_wavelength_pixels = smoother = bigger terrain features.
# Weirdness is intentionally short: it creates peaks/valley variation within biomes.
CLIMATE_NOISE_PARAMS = {
    'temperature':     {'cutoff_wavelength_pixels': 150, 'seed_offset': 0},
    'humidity':        {'cutoff_wavelength_pixels': 130, 'seed_offset': 1},
    'continentalness': {'cutoff_wavelength_pixels': 200, 'seed_offset': 2},
    'erosion':         {'cutoff_wavelength_pixels':  80, 'seed_offset': 3},
    'weirdness':       {'cutoff_wavelength_pixels':  25, 'seed_offset': 4},
}


def low_pass_noise(
    height:                   int,
    width:                    int,
    cutoff_wavelength_pixels: float,
    seed:                     int,
) -> np.ndarray:
    """
    Generates smooth bandlimited noise via a Gaussian low-pass filter in the
    frequency domain (equivalent to Gaussian blur, no scipy required).
    Output is clipped to [-1.0, 1.0] after normalising to ±2 sigma.
    """
    rng         = np.random.default_rng(seed)
    white_noise = rng.standard_normal((height, width))
    freq_domain = np.fft.rfft2(white_noise)

    freq_y         = np.fft.fftfreq(height)[:, np.newaxis]
    freq_x         = np.fft.rfftfreq(width)[np.newaxis, :]
    freq_magnitude = np.sqrt(freq_y ** 2 + freq_x ** 2)

    cutoff_frequency = 1.0 / cutoff_wavelength_pixels
    gaussian_weights = np.exp(-0.5 * (freq_magnitude / cutoff_frequency) ** 2)
    result           = np.real(np.fft.irfft2(freq_domain * gaussian_weights, s=(height, width)))

    noise_std = np.std(result)
    if noise_std > 0:
        result /= noise_std
    # 0.5 factor maps ±2 sigma to ±1.0, clipping ~5% of extreme values
    return np.clip(result * 0.5, -1.0, 1.0)


def generate_climate_noise_map(height: int, width: int, axis: str, master_seed: int) -> np.ndarray:
    params = CLIMATE_NOISE_PARAMS[axis]
    seed   = master_seed * 10 + params['seed_offset']
    return low_pass_noise(height, width, params['cutoff_wavelength_pixels'], seed)


def generate_all_climate_maps(height: int, width: int, master_seed: int) -> dict[str, np.ndarray]:
    climate_maps: dict[str, np.ndarray] = {}
    for axis in PARAMETER_AXES:
        if axis == 'depth':
            climate_maps[axis] = np.full((height, width), SURFACE_DEPTH_VALUE)
        else:
            climate_maps[axis] = generate_climate_noise_map(height, width, axis, master_seed)
    return climate_maps


def climate_maps_to_query_grid(climate_maps: dict[str, np.ndarray]) -> np.ndarray:
    """Stacks per-axis noise maps into a (H, W, 6) query grid."""
    sample_map    = next(iter(climate_maps.values()))
    height, width = sample_map.shape
    grid          = np.zeros((height, width, 6))
    for axis_index, axis in enumerate(PARAMETER_AXES):
        grid[:, :, axis_index] = climate_maps[axis]
    return grid
