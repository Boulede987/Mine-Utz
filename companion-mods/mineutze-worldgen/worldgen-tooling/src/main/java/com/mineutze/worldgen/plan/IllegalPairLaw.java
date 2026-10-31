package com.mineutze.worldgen.plan;

import com.mineutze.worldgen.biomegraph.BiomeId;

/** {@code first} and {@code second} may not touch. Unordered: stored in name order so equal pairs compare equal. */
record IllegalPairLaw(BiomeId first, BiomeId second) implements GraphLaw {

    IllegalPairLaw {
        if (first.equals(second)) {
            throw new IllegalArgumentException("IllegalPairLaw: a biome is never its own neighbour pair, got " + first);
        }
        if (first.toString().compareTo(second.toString()) > 0) {
            BiomeId swap = first;
            first = second;
            second = swap;
        }
    }

    @Override
    public String describe() {
        return first + " and " + second + " are not legal neighbours";
    }
}
