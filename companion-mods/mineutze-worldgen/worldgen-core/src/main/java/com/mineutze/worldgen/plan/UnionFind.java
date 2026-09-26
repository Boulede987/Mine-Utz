package com.mineutze.worldgen.plan;

/** Union-find (disjoint-set) over a fixed number of elements, path-compressing on every {@link #find}. Groups same-biome samples into connected regions. */
final class UnionFind {

    private final int[] parent;

    UnionFind(int size) {
        parent = new int[size];
        for (int item = 0; item < size; item++) {
            parent[item] = item;
        }
    }

    int find(int item) {
        int root = item;
        while (parent[root] != root) {
            parent[root] = parent[parent[root]];
            root = parent[root];
        }
        return root;
    }

    void union(int first, int second) {
        int firstRoot = find(first);
        int secondRoot = find(second);
        if (firstRoot != secondRoot) {
            parent[Math.max(firstRoot, secondRoot)] = Math.min(firstRoot, secondRoot);
        }
    }
}
