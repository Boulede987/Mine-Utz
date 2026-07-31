# Why this companion mod?

## To place constraints on biome adjency

For example, I want to be able to say : 
- deserts are only ever next to savannas and badlands
- underground_deserts are only ever under deserts
- deserts are never next to oceans of any kind
- deserts are never next to beaches

any biome can have adjency constraints that I will configure

## To dictate biome size

deserts -> between 700 and 1000 blocks wide
groves -> between 400 and 500 blocks wide

## To dictate world shape from biome placement

The shape of the terrain is dictated by the fact there is a desert.
Before computing any 3d shape, we compute the distribution. In x,y,z is a warm ocean, then in x1,y1,z1 there is a badland, then in x2,y2,z2 there is a desert. Therefore, x,y,z -> deep, watre ; x1,y1,z1 -> uneaven, rocky ; x2,y2,z2 -> flat, even, large

## To dictate biome parameters

deserts have no rivers, no lakes
forests have rivers