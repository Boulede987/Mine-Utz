from .climate import (
    span, mc,
    TEMPERATURES, HUMIDITIES, EROSIONS, FULL,
    CONT_DEEP_OCEAN, CONT_OCEAN, CONT_COAST,
    CONT_NEAR_INLAND, CONT_MID_INLAND, CONT_FAR_INLAND, CONT_INLAND,
    SWAMP_VANILLA, BEACH_VANILLA, EXTREME_HILLS_VANILLA,
)


class BWGBuilder:
    def __init__(self, label, oceans, middle, middle_variant, plateau, plateau_variant,
                 shattered, beach, peak, peak_variant, slope, slope_variant, swapper):
        self.label                 = label
        self.entries               = []
        self.oceans_array          = oceans
        self.middle_array          = middle
        self.middle_variant_array  = middle_variant
        self.plateau_array         = plateau
        self.plateau_variant_array = plateau_variant
        self.shattered_array       = shattered
        self.beach_array           = beach
        self.peak_array            = peak
        self.peak_variant_array    = peak_variant
        self.slope_array           = slope
        self.slope_variant_array   = slope_variant
        self.swapper               = swapper

    # ── pick helpers ──────────────────────────────────────────────────────────

    def pick_middle(self, temp_index, humidity_index, weirdness):
        base = self.middle_array[temp_index][humidity_index]
        if weirdness[1] < 0:
            return base
        variant = self.middle_variant_array[temp_index][humidity_index]
        return variant if variant is not None else base

    def pick_plateau(self, temp_index, humidity_index, weirdness):
        base = self.plateau_array[temp_index][humidity_index]
        if weirdness[1] < 0:
            return base
        variant = self.plateau_variant_array[temp_index][humidity_index]
        return variant if variant is not None else base

    def pick_peak(self, temp_index, humidity_index, weirdness):
        base = self.peak_array[temp_index][humidity_index]
        if weirdness[1] < 0 or base is None:
            return base
        variant = self.peak_variant_array[temp_index][humidity_index]
        return variant if variant is not None else base

    def pick_slope(self, temp_index, humidity_index, weirdness):
        base = self.slope_array[temp_index][humidity_index]
        if weirdness[1] < 0 or base is None:
            return base
        variant = self.slope_variant_array[temp_index][humidity_index]
        return variant if variant is not None else base

    def pick_beach(self, temp_index, humidity_index):
        beach = self.beach_array[temp_index][humidity_index]
        return beach if beach is not None else BEACH_VANILLA[temp_index][humidity_index]

    def pick_extreme(self, temp_index, humidity_index, weirdness):
        shattered = self.shattered_array[temp_index][humidity_index]
        if shattered is not None:
            return shattered
        extreme = EXTREME_HILLS_VANILLA[temp_index][humidity_index]
        return extreme if extreme is not None else self.pick_middle(temp_index, humidity_index, weirdness)

    def pick_shattered(self, temp_index, humidity_index, weirdness, fallback):
        # vanilla returns WINDSWEPT_SAVANNA for warm+humid<4+positive weirdness
        if temp_index > 1 and humidity_index < 4 and weirdness[1] >= 0:
            return mc('windswept_savanna')
        return fallback

    def pick_swamp(self, temp_index, humidity_index, weirdness):
        vanilla = SWAMP_VANILLA[temp_index][humidity_index]
        return self.swapper.get(vanilla, vanilla)

    def pick_shattered_coast(self, temp_index, humidity_index, weirdness):
        fallback = (self.pick_middle(temp_index, humidity_index, weirdness)
                    if weirdness[1] >= 0
                    else self.pick_beach(temp_index, humidity_index))
        return self.pick_shattered(temp_index, humidity_index, weirdness, fallback)

    def pick_river(self, temp_index, humidity_index):
        return mc('frozen_river') if temp_index == 0 else mc('river')

    # ── emit helper ───────────────────────────────────────────────────────────

    def add_surface(self, temperature, humidity, continentalness, erosion, weirdness, biome):
        if biome is None:
            return
        for depth in ([0.0, 0.0], [1.0, 1.0]):
            self.entries.append({'biome': biome, 'parameters': {
                'temperature': temperature, 'humidity': humidity,
                'continentalness': continentalness, 'erosion': erosion,
                'depth': depth, 'weirdness': weirdness, 'offset': 0.0}})

    # ── slice methods (vanilla OverworldBiomeBuilder logic) ───────────────────

    def off_coast(self):
        for temp_index in range(5):
            temperature = TEMPERATURES[temp_index]
            self.add_surface(temperature, FULL, CONT_DEEP_OCEAN, FULL, FULL, self.oceans_array[0][temp_index])
            self.add_surface(temperature, FULL, CONT_OCEAN,      FULL, FULL, self.oceans_array[1][temp_index])

    def peaks(self, weirdness):
        for temp_index in range(5):
            temperature = TEMPERATURES[temp_index]
            for humidity_index in range(5):
                humidity        = HUMIDITIES[humidity_index]
                middle_biome    = self.pick_middle(temp_index, humidity_index, weirdness)
                plateau_biome   = self.pick_plateau(temp_index, humidity_index, weirdness)
                extreme_biome   = self.pick_extreme(temp_index, humidity_index, weirdness)
                peak_biome      = self.pick_peak(temp_index, humidity_index, weirdness)
                shattered_biome = self.pick_shattered(temp_index, humidity_index, weirdness, extreme_biome)
                mid_or_slope    = self.pick_slope(temp_index, humidity_index, weirdness) if temp_index == 0 else middle_biome
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_FAR_INLAND),      EROSIONS[0],                    weirdness, peak_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_NEAR_INLAND),     EROSIONS[1],                    weirdness, mid_or_slope)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), EROSIONS[1],                    weirdness, peak_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_NEAR_INLAND),     span(EROSIONS[2], EROSIONS[3]), weirdness, middle_biome)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), EROSIONS[2],                    weirdness, plateau_biome)
                self.add_surface(temperature, humidity, CONT_MID_INLAND,                        EROSIONS[3],                    weirdness, middle_biome)
                self.add_surface(temperature, humidity, CONT_FAR_INLAND,                        EROSIONS[3],                    weirdness, plateau_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_FAR_INLAND),      EROSIONS[4],                    weirdness, middle_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_NEAR_INLAND),     EROSIONS[5],                    weirdness, shattered_biome)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), EROSIONS[5],                    weirdness, extreme_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_FAR_INLAND),      EROSIONS[6],                    weirdness, middle_biome)

    def high_slice(self, weirdness):
        for temp_index in range(5):
            temperature = TEMPERATURES[temp_index]
            for humidity_index in range(5):
                humidity        = HUMIDITIES[humidity_index]
                middle_biome    = self.pick_middle(temp_index, humidity_index, weirdness)
                plateau_biome   = self.pick_plateau(temp_index, humidity_index, weirdness)
                extreme_biome   = self.pick_extreme(temp_index, humidity_index, weirdness)
                slope_biome     = self.pick_slope(temp_index, humidity_index, weirdness)
                peak_biome      = self.pick_peak(temp_index, humidity_index, weirdness)
                shattered_biome = self.pick_shattered(temp_index, humidity_index, weirdness, middle_biome)
                mid_or_slope    = slope_biome if temp_index == 0 else middle_biome
                self.add_surface(temperature, humidity, CONT_COAST,                             span(EROSIONS[0], EROSIONS[1]), weirdness, middle_biome)
                self.add_surface(temperature, humidity, CONT_NEAR_INLAND,                       EROSIONS[0],                    weirdness, slope_biome)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), EROSIONS[0],                    weirdness, peak_biome)
                self.add_surface(temperature, humidity, CONT_NEAR_INLAND,                       EROSIONS[1],                    weirdness, mid_or_slope)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), EROSIONS[1],                    weirdness, slope_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_NEAR_INLAND),     span(EROSIONS[2], EROSIONS[3]), weirdness, middle_biome)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), EROSIONS[2],                    weirdness, plateau_biome)
                self.add_surface(temperature, humidity, CONT_MID_INLAND,                        EROSIONS[3],                    weirdness, middle_biome)
                self.add_surface(temperature, humidity, CONT_FAR_INLAND,                        EROSIONS[3],                    weirdness, plateau_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_FAR_INLAND),      EROSIONS[4],                    weirdness, middle_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_NEAR_INLAND),     EROSIONS[5],                    weirdness, shattered_biome)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), EROSIONS[5],                    weirdness, extreme_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_FAR_INLAND),      EROSIONS[6],                    weirdness, middle_biome)

    def mid_slice(self, weirdness):
        for temp_index in range(5):
            temperature = TEMPERATURES[temp_index]
            for humidity_index in range(5):
                humidity             = HUMIDITIES[humidity_index]
                middle_biome         = self.pick_middle(temp_index, humidity_index, weirdness)
                plateau_biome        = self.pick_plateau(temp_index, humidity_index, weirdness)
                extreme_biome        = self.pick_extreme(temp_index, humidity_index, weirdness)
                slope_biome          = self.pick_slope(temp_index, humidity_index, weirdness)
                beach_biome          = self.pick_beach(temp_index, humidity_index)
                swamp_biome          = self.pick_swamp(temp_index, humidity_index, weirdness)
                shattered_biome      = self.pick_shattered(temp_index, humidity_index, weirdness, middle_biome)
                shattered_coast_biome= self.pick_shattered_coast(temp_index, humidity_index, weirdness)
                mid_or_slope         = slope_biome if temp_index == 0 else middle_biome
                self.add_surface(temperature, humidity, CONT_COAST,                              span(EROSIONS[0], EROSIONS[2]), weirdness, mc('stony_shore'))
                self.add_surface(temperature, humidity, span(CONT_NEAR_INLAND, CONT_FAR_INLAND), EROSIONS[0],                    weirdness, slope_biome)
                self.add_surface(temperature, humidity, span(CONT_NEAR_INLAND, CONT_MID_INLAND), EROSIONS[1],                    weirdness, mid_or_slope)
                self.add_surface(temperature, humidity, CONT_FAR_INLAND,                         EROSIONS[1],
                                 weirdness, slope_biome if temp_index == 0 else plateau_biome)
                self.add_surface(temperature, humidity, CONT_NEAR_INLAND,                        EROSIONS[2],                    weirdness, middle_biome)
                self.add_surface(temperature, humidity, CONT_MID_INLAND,                         EROSIONS[2],                    weirdness, middle_biome)
                self.add_surface(temperature, humidity, CONT_FAR_INLAND,                         EROSIONS[2],                    weirdness, plateau_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_NEAR_INLAND),      EROSIONS[3],                    weirdness, middle_biome)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND),  EROSIONS[3],                    weirdness, middle_biome)
                if weirdness[1] < 0:
                    self.add_surface(temperature, humidity, CONT_COAST,                              EROSIONS[4], weirdness, beach_biome)
                    self.add_surface(temperature, humidity, span(CONT_NEAR_INLAND, CONT_FAR_INLAND), EROSIONS[4], weirdness, middle_biome)
                else:
                    self.add_surface(temperature, humidity, span(CONT_COAST, CONT_FAR_INLAND),       EROSIONS[4], weirdness, middle_biome)
                self.add_surface(temperature, humidity, CONT_COAST,                              EROSIONS[5],                    weirdness, shattered_coast_biome)
                self.add_surface(temperature, humidity, CONT_NEAR_INLAND,                        EROSIONS[5],                    weirdness, shattered_biome)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND),  EROSIONS[5],                    weirdness, extreme_biome)
                if weirdness[1] < 0:
                    self.add_surface(temperature, humidity, CONT_COAST, EROSIONS[6], weirdness, beach_biome)
                else:
                    self.add_surface(temperature, humidity, CONT_COAST, EROSIONS[6], weirdness, middle_biome)
                self.add_surface(temperature, humidity, span(CONT_NEAR_INLAND, CONT_FAR_INLAND), EROSIONS[6],                    weirdness, swamp_biome)

    def low_slice(self, weirdness):
        for temp_index in range(5):
            temperature = TEMPERATURES[temp_index]
            for humidity_index in range(5):
                humidity             = HUMIDITIES[humidity_index]
                middle_biome         = self.pick_middle(temp_index, humidity_index, weirdness)
                extreme_biome        = self.pick_extreme(temp_index, humidity_index, weirdness)
                beach_biome          = self.pick_beach(temp_index, humidity_index)
                swamp_biome          = self.pick_swamp(temp_index, humidity_index, weirdness)
                shattered_biome      = self.pick_shattered(temp_index, humidity_index, weirdness, middle_biome)
                shattered_coast_biome= self.pick_shattered_coast(temp_index, humidity_index, weirdness)
                mid_or_slope         = self.pick_slope(temp_index, humidity_index, weirdness) if temp_index == 0 else middle_biome
                self.add_surface(temperature, humidity, CONT_COAST,                              span(EROSIONS[0], EROSIONS[2]), weirdness, mc('stony_shore'))
                self.add_surface(temperature, humidity, CONT_NEAR_INLAND,                        span(EROSIONS[0], EROSIONS[1]), weirdness, middle_biome)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND),  span(EROSIONS[0], EROSIONS[1]), weirdness, mid_or_slope)
                self.add_surface(temperature, humidity, CONT_NEAR_INLAND,                        span(EROSIONS[2], EROSIONS[3]), weirdness, middle_biome)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND),  span(EROSIONS[2], EROSIONS[3]), weirdness, middle_biome)
                self.add_surface(temperature, humidity, CONT_COAST,                              span(EROSIONS[3], EROSIONS[4]), weirdness, beach_biome)
                self.add_surface(temperature, humidity, span(CONT_NEAR_INLAND, CONT_FAR_INLAND), EROSIONS[4],                    weirdness, middle_biome)
                self.add_surface(temperature, humidity, CONT_COAST,                              EROSIONS[5],                    weirdness, shattered_coast_biome)
                self.add_surface(temperature, humidity, CONT_NEAR_INLAND,                        EROSIONS[5],                    weirdness, shattered_biome)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND),  EROSIONS[5],                    weirdness, middle_biome)
                self.add_surface(temperature, humidity, CONT_COAST,                              EROSIONS[6],                    weirdness, beach_biome)
                self.add_surface(temperature, humidity, span(CONT_NEAR_INLAND, CONT_FAR_INLAND), EROSIONS[6],                    weirdness, swamp_biome)

    def valleys(self, weirdness):
        for temp_index in range(5):
            temperature = TEMPERATURES[temp_index]
            for humidity_index in range(5):
                humidity    = HUMIDITIES[humidity_index]
                river_biome = self.pick_river(temp_index, humidity_index)
                self.add_surface(temperature, humidity, CONT_COAST,                        span(EROSIONS[0], EROSIONS[1]), weirdness, river_biome)
                self.add_surface(temperature, humidity, CONT_NEAR_INLAND,                  span(EROSIONS[0], EROSIONS[1]), weirdness, river_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_FAR_INLAND), span(EROSIONS[2], EROSIONS[5]), weirdness, river_biome)
                self.add_surface(temperature, humidity, CONT_COAST,                        EROSIONS[6],                    weirdness, river_biome)
        for temp_index in range(5):
            temperature = TEMPERATURES[temp_index]
            for humidity_index in range(5):
                humidity    = HUMIDITIES[humidity_index]
                middle_biome = self.pick_middle(temp_index, humidity_index, weirdness)
                swamp_biome  = self.pick_swamp(temp_index, humidity_index, weirdness)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), span(EROSIONS[0], EROSIONS[1]), weirdness, middle_biome)
                self.add_surface(temperature, humidity, span(CONT_INLAND, CONT_FAR_INLAND),      EROSIONS[6],                   weirdness, swamp_biome)

    def run(self):
        inland_slices = [
            (self.mid_slice,  [-1.0,        -0.93333334]),
            (self.high_slice, [-0.93333334, -0.7666667]),
            (self.peaks,      [-0.7666667,  -0.56666666]),
            (self.high_slice, [-0.56666666, -0.4]),
            (self.mid_slice,  [-0.4,        -0.26666668]),
            (self.low_slice,  [-0.26666668, -0.05]),
            (self.valleys,    [-0.05,         0.05]),
            (self.low_slice,  [ 0.05,         0.26666668]),
            (self.mid_slice,  [ 0.26666668,   0.4]),
            (self.high_slice, [ 0.4,          0.56666666]),
            (self.peaks,      [ 0.56666666,   0.7666667]),
            (self.high_slice, [ 0.7666667,    0.93333334]),
            (self.mid_slice,  [ 0.93333334,   1.0]),
        ]
        self.off_coast()
        for method, weirdness in inland_slices:
            method(weirdness)
        return self.entries
