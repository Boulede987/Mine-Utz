from .climate import (
    span, mc, bop,
    TEMPERATURES, HUMIDITIES, EROSIONS, FULL,
    CONT_MUSHROOM, CONT_DEEP_OCEAN, CONT_OCEAN, CONT_COAST,
    CONT_NEAR_INLAND, CONT_MID_INLAND, CONT_FAR_INLAND, CONT_INLAND,
    OCEANS, MIDDLE, MIDDLE_VARIANT, PLATEAU, PLATEAU_VARIANT,
    EXTREME_HILLS, SWAMP_VANILLA, BEACH_VANILLA,
)
from .arrays import (
    P_MIDDLE, P_MIDDLE_VARIANT, P_PLATEAU, P_PLATEAU_VARIANT,
    P_SLOPE, P_PEAK, P_EXTREME_HILLS, P_SWAMP, P_RIVER,
    P_BEACH, P_STONY, P_ISLAND,
    R_RARE, R_RARE_VARIANT,
    S_MIDDLE, S_MIDDLE_VARIANT, S_PLATEAU, S_PLATEAU_VARIANT,
    S_SWAMP, S_RIVER, S_BEACH, S_STONY,
)


class BOPBuilder:
    def __init__(self, label='primary'):
        self.label                = label
        self.entries              = []
        self.middle_array         = P_MIDDLE
        self.middle_variant_array = P_MIDDLE_VARIANT
        self.plateau_array        = P_PLATEAU
        self.plateau_variant_array= P_PLATEAU_VARIANT
        self.slope_array          = P_SLOPE
        self.peak_array           = P_PEAK
        self.extreme_hills_array  = P_EXTREME_HILLS
        self.swamp_array          = P_SWAMP
        self.river_array          = P_RIVER
        self.beach_array          = P_BEACH
        self.stony_array          = P_STONY
        self.island_array         = P_ISLAND

    @staticmethod
    def _first_non_none(*candidates):
        for candidate in candidates:
            if candidate is not None:
                return candidate
        return None

    # ── pick helpers ──────────────────────────────────────────────────────────

    def pick_mid_vanilla(self, temp_index, humidity_index, weirdness):
        if weirdness[1] < 0:
            return MIDDLE[temp_index][humidity_index]
        return self._first_non_none(MIDDLE_VARIANT[temp_index][humidity_index],
                                    MIDDLE[temp_index][humidity_index])

    def pick_mid_bop(self, temp_index, humidity_index, weirdness):
        base = self._first_non_none(self.middle_array[temp_index][humidity_index],
                                    MIDDLE[temp_index][humidity_index])
        if weirdness[1] < 0:
            return base
        return self._first_non_none(self.middle_variant_array[temp_index][humidity_index], base)

    def pick_mid_or_slope(self, temp_index, humidity_index, weirdness):
        if temp_index == 0:
            return self.pick_slope(temp_index, humidity_index, weirdness)
        return self.pick_mid_bop(temp_index, humidity_index, weirdness)

    def pick_shattered(self, temp_index, humidity_index, weirdness, fallback):
        if temp_index > 1 and humidity_index < 4 and weirdness[1] >= 0:
            return self._first_non_none(bop('crag'), mc('windswept_savanna'))
        return fallback

    def pick_swamp_vanilla(self, temp_index, humidity_index, weirdness):
        vanilla = SWAMP_VANILLA[temp_index][humidity_index]
        return vanilla if vanilla else self.pick_mid_vanilla(temp_index, humidity_index, weirdness)

    def pick_swamp(self, temp_index, humidity_index, weirdness):
        return self._first_non_none(self.swamp_array[temp_index][humidity_index],
                                    self.pick_swamp_vanilla(temp_index, humidity_index, weirdness))

    def pick_river(self, temp_index, humidity_index):
        fallback = mc('frozen_river') if temp_index == 0 else mc('river')
        return self._first_non_none(self.river_array[temp_index][humidity_index], fallback)

    def pick_beach(self, temp_index, humidity_index):
        return self._first_non_none(self.beach_array[temp_index][humidity_index],
                                    BEACH_VANILLA[temp_index][humidity_index])

    def pick_stony(self, temp_index, humidity_index):
        return self._first_non_none(self.stony_array[temp_index][humidity_index],
                                    mc('stony_shore'))

    def pick_island(self, temp_index, humidity_index):
        return self._first_non_none(self.island_array[temp_index][humidity_index],
                                    mc('mushroom_fields'))

    def pick_badlands(self, humidity_index, weirdness):
        if humidity_index < 2:
            return mc('eroded_badlands') if weirdness[1] < 0 else mc('badlands')
        return mc('badlands') if humidity_index < 3 else mc('wooded_badlands')

    def pick_plateau(self, temp_index, humidity_index, weirdness):
        if weirdness[1] < 0:
            return self._first_non_none(self.plateau_array[temp_index][humidity_index],
                                        PLATEAU[temp_index][humidity_index])
        return self._first_non_none(
            self.plateau_variant_array[temp_index][humidity_index],
            self.plateau_array[temp_index][humidity_index],
            PLATEAU_VARIANT[temp_index][humidity_index],
            PLATEAU[temp_index][humidity_index],
        )

    def pick_peak_vanilla(self, temp_index, humidity_index, weirdness):
        if temp_index <= 2:
            return mc('jagged_peaks') if weirdness[1] < 0 else mc('frozen_peaks')
        if temp_index == 3:
            return mc('stony_peaks')
        return self.pick_badlands(humidity_index, weirdness)

    def pick_peak(self, temp_index, humidity_index, weirdness):
        return self._first_non_none(self.peak_array[temp_index][humidity_index],
                                    self.pick_peak_vanilla(temp_index, humidity_index, weirdness))

    def pick_slope(self, temp_index, humidity_index, weirdness):
        return self._first_non_none(self.slope_array[temp_index][humidity_index],
                                    self.pick_plateau(temp_index, humidity_index, weirdness))

    def pick_extreme_vanilla(self, temp_index, humidity_index, weirdness):
        vanilla = EXTREME_HILLS[temp_index][humidity_index]
        return vanilla if vanilla else self.pick_mid_vanilla(temp_index, humidity_index, weirdness)

    def pick_extreme(self, temp_index, humidity_index, weirdness):
        return self._first_non_none(self.extreme_hills_array[temp_index][humidity_index],
                                    self.pick_extreme_vanilla(temp_index, humidity_index, weirdness))

    def pick_shattered_coast(self, temp_index, humidity_index, weirdness):
        if weirdness[1] >= 0:
            fallback = self.pick_mid_vanilla(temp_index, humidity_index, weirdness)
        else:
            fallback = self.pick_beach(temp_index, humidity_index)
        return self.pick_shattered(temp_index, humidity_index, weirdness, fallback)

    # ── emit helpers ──────────────────────────────────────────────────────────

    def add_surface(self, temperature, humidity, continentalness, erosion, weirdness, biome):
        if biome is None:
            return
        for depth in ([0.0, 0.0], [1.0, 1.0]):
            self.entries.append({'biome': biome, 'parameters': {
                'temperature': temperature, 'humidity': humidity,
                'continentalness': continentalness, 'erosion': erosion,
                'depth': depth, 'weirdness': weirdness, 'offset': 0.0}})

    def add_cave(self, temperature, humidity, continentalness, erosion, weirdness, biome):
        if biome is None:
            return
        self.entries.append({'biome': biome, 'parameters': {
            'temperature': temperature, 'humidity': humidity,
            'continentalness': continentalness, 'erosion': erosion,
            'depth': [0.2, 0.9], 'weirdness': weirdness, 'offset': 0.0}})

    def add_deep(self, temperature, humidity, continentalness, erosion, weirdness, biome):
        if biome is None:
            return
        self.entries.append({'biome': biome, 'parameters': {
            'temperature': temperature, 'humidity': humidity,
            'continentalness': continentalness, 'erosion': erosion,
            'depth': [1.1, 1.1], 'weirdness': weirdness, 'offset': 0.0}})

    # ── slice methods ─────────────────────────────────────────────────────────

    def off_coast(self):
        for temp_index in range(5):
            temperature = TEMPERATURES[temp_index]
            for humidity_index in range(5):
                self.add_surface(temperature, HUMIDITIES[humidity_index],
                                 CONT_MUSHROOM, FULL, FULL,
                                 self.pick_island(temp_index, humidity_index))
            self.add_surface(temperature, FULL, CONT_DEEP_OCEAN, FULL, FULL, OCEANS[0][temp_index])
            self.add_surface(temperature, FULL, CONT_OCEAN,      FULL, FULL, OCEANS[1][temp_index])

    def peaks(self, weirdness):
        for temp_index in range(5):
            temperature = TEMPERATURES[temp_index]
            for humidity_index in range(5):
                humidity      = HUMIDITIES[humidity_index]
                mid_biome     = self.pick_mid_bop(temp_index, humidity_index, weirdness)
                mid_or_slope  = self.pick_mid_or_slope(temp_index, humidity_index, weirdness)
                plateau_biome = self.pick_plateau(temp_index, humidity_index, weirdness)
                extreme_van   = self.pick_extreme_vanilla(temp_index, humidity_index, weirdness)
                extreme_biome = self.pick_extreme(temp_index, humidity_index, weirdness)
                shattered     = self.pick_shattered(temp_index, humidity_index, weirdness, extreme_van)
                peak_biome    = self.pick_peak(temp_index, humidity_index, weirdness)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_FAR_INLAND),      EROSIONS[0],                    weirdness, peak_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_NEAR_INLAND),     EROSIONS[1],                    weirdness, mid_or_slope)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), EROSIONS[1],                    weirdness, peak_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_NEAR_INLAND),     span(EROSIONS[2], EROSIONS[3]), weirdness, mid_biome)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), EROSIONS[2],                    weirdness, plateau_biome)
                self.add_surface(temperature, humidity, CONT_MID_INLAND,                        EROSIONS[3],                    weirdness, mid_biome)
                self.add_surface(temperature, humidity, CONT_FAR_INLAND,                        EROSIONS[3],                    weirdness, plateau_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_FAR_INLAND),      EROSIONS[4],                    weirdness, mid_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_NEAR_INLAND),     EROSIONS[5],                    weirdness, shattered)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), EROSIONS[5],                    weirdness, extreme_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_FAR_INLAND),      EROSIONS[6],                    weirdness, mid_biome)

    def high_slice(self, weirdness):
        for temp_index in range(5):
            temperature = TEMPERATURES[temp_index]
            for humidity_index in range(5):
                humidity      = HUMIDITIES[humidity_index]
                mid_vanilla   = self.pick_mid_vanilla(temp_index, humidity_index, weirdness)
                mid_biome     = self.pick_mid_bop(temp_index, humidity_index, weirdness)
                mid_or_slope  = self.pick_mid_or_slope(temp_index, humidity_index, weirdness)
                plateau_biome = self.pick_plateau(temp_index, humidity_index, weirdness)
                extreme_biome = self.pick_extreme(temp_index, humidity_index, weirdness)
                shattered     = self.pick_shattered(temp_index, humidity_index, weirdness, mid_vanilla)
                slope_biome   = self.pick_slope(temp_index, humidity_index, weirdness)
                peak_biome    = self.pick_peak(temp_index, humidity_index, weirdness)
                self.add_surface(temperature, humidity, CONT_COAST,                             span(EROSIONS[0], EROSIONS[1]), weirdness, mid_biome)
                self.add_surface(temperature, humidity, CONT_NEAR_INLAND,                       EROSIONS[0],                    weirdness, slope_biome)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), EROSIONS[0],                    weirdness, peak_biome)
                self.add_surface(temperature, humidity, CONT_NEAR_INLAND,                       EROSIONS[1],                    weirdness, mid_or_slope)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), EROSIONS[1],                    weirdness, slope_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_NEAR_INLAND),     span(EROSIONS[2], EROSIONS[3]), weirdness, mid_biome)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), EROSIONS[2],                    weirdness, plateau_biome)
                self.add_surface(temperature, humidity, CONT_MID_INLAND,                        EROSIONS[3],                    weirdness, mid_biome)
                self.add_surface(temperature, humidity, CONT_FAR_INLAND,                        EROSIONS[3],                    weirdness, plateau_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_FAR_INLAND),      EROSIONS[4],                    weirdness, mid_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_NEAR_INLAND),     EROSIONS[5],                    weirdness, shattered)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), EROSIONS[5],                    weirdness, extreme_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_FAR_INLAND),      EROSIONS[6],                    weirdness, mid_biome)

    def mid_slice(self, weirdness):
        for temp_index in range(5):
            temperature = TEMPERATURES[temp_index]
            for humidity_index in range(5):
                humidity        = HUMIDITIES[humidity_index]
                mid_vanilla     = self.pick_mid_vanilla(temp_index, humidity_index, weirdness)
                mid_biome       = self.pick_mid_bop(temp_index, humidity_index, weirdness)
                mid_or_slope    = self.pick_mid_or_slope(temp_index, humidity_index, weirdness)
                extreme_biome   = self.pick_extreme(temp_index, humidity_index, weirdness)
                plateau_biome   = self.pick_plateau(temp_index, humidity_index, weirdness)
                beach_biome     = self.pick_beach(temp_index, humidity_index)
                stony_biome     = self.pick_stony(temp_index, humidity_index)
                shattered       = self.pick_shattered(temp_index, humidity_index, weirdness, mid_vanilla)
                shattered_coast = self.pick_shattered_coast(temp_index, humidity_index, weirdness)
                slope_biome     = self.pick_slope(temp_index, humidity_index, weirdness)
                swamp_biome     = self.pick_swamp(temp_index, humidity_index, weirdness)
                self.add_surface(temperature, humidity, CONT_COAST,                             span(EROSIONS[0], EROSIONS[2]), weirdness, stony_biome)
                self.add_surface(temperature, humidity, span(CONT_NEAR_INLAND, CONT_FAR_INLAND),EROSIONS[0],                    weirdness, slope_biome)
                self.add_surface(temperature, humidity, span(CONT_NEAR_INLAND, CONT_MID_INLAND),EROSIONS[1],                    weirdness, mid_or_slope)
                self.add_surface(temperature, humidity, CONT_FAR_INLAND,                        EROSIONS[1],
                                 weirdness, slope_biome if temp_index == 0 else plateau_biome)
                self.add_surface(temperature, humidity, CONT_NEAR_INLAND,                       EROSIONS[2],                    weirdness, mid_biome)
                self.add_surface(temperature, humidity, CONT_MID_INLAND,                        EROSIONS[2],                    weirdness, mid_biome)
                self.add_surface(temperature, humidity, CONT_FAR_INLAND,                        EROSIONS[2],                    weirdness, plateau_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_NEAR_INLAND),     EROSIONS[3],                    weirdness, mid_biome)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), EROSIONS[3],                    weirdness, mid_biome)
                if weirdness[1] < 0:
                    self.add_surface(temperature, humidity, CONT_COAST,                             EROSIONS[4], weirdness, beach_biome)
                    self.add_surface(temperature, humidity, span(CONT_NEAR_INLAND, CONT_FAR_INLAND),EROSIONS[4], weirdness, mid_biome)
                else:
                    self.add_surface(temperature, humidity, span(CONT_COAST, CONT_FAR_INLAND),      EROSIONS[4], weirdness, mid_biome)
                self.add_surface(temperature, humidity, CONT_COAST,                             EROSIONS[5],                    weirdness, shattered_coast)
                self.add_surface(temperature, humidity, CONT_NEAR_INLAND,                       EROSIONS[5],                    weirdness, shattered)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), EROSIONS[5],                    weirdness, extreme_biome)
                if weirdness[1] < 0:
                    self.add_surface(temperature, humidity, CONT_COAST, EROSIONS[6], weirdness, beach_biome)
                else:
                    self.add_surface(temperature, humidity, CONT_COAST, EROSIONS[6], weirdness, mid_biome)
                self.add_surface(temperature, humidity, span(CONT_NEAR_INLAND, CONT_FAR_INLAND),EROSIONS[6],                    weirdness, swamp_biome)

    def low_slice(self, weirdness):
        for temp_index in range(5):
            temperature = TEMPERATURES[temp_index]
            for humidity_index in range(5):
                humidity        = HUMIDITIES[humidity_index]
                mid_vanilla     = self.pick_mid_vanilla(temp_index, humidity_index, weirdness)
                mid_biome       = self.pick_mid_bop(temp_index, humidity_index, weirdness)
                mid_or_slope    = self.pick_mid_or_slope(temp_index, humidity_index, weirdness)
                beach_biome     = self.pick_beach(temp_index, humidity_index)
                stony_biome     = self.pick_stony(temp_index, humidity_index)
                shattered       = self.pick_shattered(temp_index, humidity_index, weirdness, mid_vanilla)
                shattered_coast = self.pick_shattered_coast(temp_index, humidity_index, weirdness)
                swamp_biome     = self.pick_swamp(temp_index, humidity_index, weirdness)
                self.add_surface(temperature, humidity, CONT_COAST,                             span(EROSIONS[0], EROSIONS[2]),  weirdness, stony_biome)
                self.add_surface(temperature, humidity, CONT_NEAR_INLAND,                       span(EROSIONS[0], EROSIONS[1]),  weirdness, mid_biome)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), span(EROSIONS[0], EROSIONS[1]),  weirdness, mid_or_slope)
                self.add_surface(temperature, humidity, CONT_NEAR_INLAND,                       span(EROSIONS[2], EROSIONS[3]),  weirdness, mid_biome)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), span(EROSIONS[2], EROSIONS[3]),  weirdness, mid_biome)
                self.add_surface(temperature, humidity, CONT_COAST,                             span(EROSIONS[3], EROSIONS[4]),  weirdness, beach_biome)
                self.add_surface(temperature, humidity, span(CONT_NEAR_INLAND, CONT_FAR_INLAND),EROSIONS[4],                     weirdness, mid_biome)
                self.add_surface(temperature, humidity, CONT_COAST,                             EROSIONS[5],                     weirdness, shattered_coast)
                self.add_surface(temperature, humidity, CONT_NEAR_INLAND,                       EROSIONS[5],                     weirdness, shattered)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), EROSIONS[5],                     weirdness, mid_biome)
                self.add_surface(temperature, humidity, CONT_COAST,                             EROSIONS[6],                     weirdness, beach_biome)
                self.add_surface(temperature, humidity, span(CONT_NEAR_INLAND, CONT_FAR_INLAND),EROSIONS[6],                     weirdness, swamp_biome)

    def valleys(self, weirdness):
        for temp_index in range(5):
            temperature = TEMPERATURES[temp_index]
            for humidity_index in range(5):
                humidity    = HUMIDITIES[humidity_index]
                river_biome = self.pick_river(temp_index, humidity_index)
                stony_biome = self.pick_stony(temp_index, humidity_index)
                self.add_surface(temperature, humidity, CONT_COAST,                        span(EROSIONS[0], EROSIONS[1]),
                                 weirdness, stony_biome if weirdness[1] < 0 else river_biome)
                self.add_surface(temperature, humidity, CONT_NEAR_INLAND,                  span(EROSIONS[0], EROSIONS[1]),  weirdness, river_biome)
                self.add_surface(temperature, humidity, span(CONT_COAST, CONT_FAR_INLAND), span(EROSIONS[2], EROSIONS[5]),  weirdness, river_biome)
                self.add_surface(temperature, humidity, CONT_COAST,                        EROSIONS[6],                     weirdness, river_biome)
        for temp_index in range(5):
            temperature = TEMPERATURES[temp_index]
            for humidity_index in range(5):
                humidity    = HUMIDITIES[humidity_index]
                mid_biome   = self.pick_mid_bop(temp_index, humidity_index, weirdness)
                swamp_biome = self.pick_swamp(temp_index, humidity_index, weirdness)
                self.add_surface(temperature, humidity, span(CONT_MID_INLAND, CONT_FAR_INLAND), span(EROSIONS[0], EROSIONS[1]), weirdness, mid_biome)
                self.add_surface(temperature, humidity, span(CONT_INLAND, CONT_FAR_INLAND),      EROSIONS[6],                   weirdness, swamp_biome)

    def underground(self):
        self.add_cave(FULL, FULL,       [0.8, 1.0], FULL, FULL, bop('spider_nest'))
        self.add_cave(FULL, [0.7, 1.0], FULL,       FULL, FULL, bop('glowing_grotto'))
        self.add_deep(FULL, FULL, FULL, span(EROSIONS[0], EROSIONS[1]), FULL, mc('deep_dark'))

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
        self.underground()
        return self.entries


class BOPRareBuilder(BOPBuilder):
    def __init__(self):
        super().__init__('rare')

    def pick_mid_bop(self, temp_index, humidity_index, weirdness):
        base = self._first_non_none(R_RARE[temp_index][humidity_index],
                                    self.middle_array[temp_index][humidity_index],
                                    MIDDLE[temp_index][humidity_index])
        if weirdness[1] < 0:
            return base
        return self._first_non_none(R_RARE_VARIANT[temp_index][humidity_index],
                                    R_RARE[temp_index][humidity_index],
                                    self.middle_variant_array[temp_index][humidity_index],
                                    base)

    def pick_plateau(self, temp_index, humidity_index, weirdness):
        if weirdness[1] < 0:
            return self._first_non_none(R_RARE[temp_index][humidity_index],
                                        self.plateau_array[temp_index][humidity_index],
                                        PLATEAU[temp_index][humidity_index])
        return self._first_non_none(
            R_RARE_VARIANT[temp_index][humidity_index],
            R_RARE[temp_index][humidity_index],
            self.plateau_variant_array[temp_index][humidity_index],
            self.plateau_array[temp_index][humidity_index],
            PLATEAU_VARIANT[temp_index][humidity_index],
            PLATEAU[temp_index][humidity_index],
        )

    def pick_river(self, temp_index, humidity_index):
        fallback = mc('frozen_river') if temp_index == 0 else mc('river')
        return self._first_non_none(R_RARE[temp_index][humidity_index],
                                    self.river_array[temp_index][humidity_index],
                                    fallback)

    def pick_swamp(self, temp_index, humidity_index, weirdness):
        return self._first_non_none(R_RARE[temp_index][humidity_index],
                                    self.swamp_array[temp_index][humidity_index],
                                    self.pick_swamp_vanilla(temp_index, humidity_index, weirdness))

    def pick_beach(self, temp_index, humidity_index):
        return self._first_non_none(R_RARE[temp_index][humidity_index],
                                    self.beach_array[temp_index][humidity_index],
                                    BEACH_VANILLA[temp_index][humidity_index])

    def pick_stony(self, temp_index, humidity_index):
        return self._first_non_none(R_RARE[temp_index][humidity_index],
                                    self.stony_array[temp_index][humidity_index],
                                    mc('stony_shore'))


class BOPSecondaryBuilder(BOPBuilder):
    def __init__(self):
        super().__init__('secondary')
        self.middle_array          = S_MIDDLE
        self.middle_variant_array  = S_MIDDLE_VARIANT
        self.plateau_array         = S_PLATEAU
        self.plateau_variant_array = S_PLATEAU_VARIANT
        self.swamp_array           = S_SWAMP
        self.river_array           = S_RIVER
        self.beach_array           = S_BEACH
        self.stony_array           = S_STONY
