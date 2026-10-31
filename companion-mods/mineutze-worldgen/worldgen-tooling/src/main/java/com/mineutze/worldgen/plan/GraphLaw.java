package com.mineutze.worldgen.plan;

sealed interface GraphLaw permits SizeRangeLaw, IllegalPairLaw, RequiredBorderLaw {
    String describe();
}
