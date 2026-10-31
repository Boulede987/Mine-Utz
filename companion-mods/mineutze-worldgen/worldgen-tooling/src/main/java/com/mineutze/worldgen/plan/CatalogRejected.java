package com.mineutze.worldgen.plan;

/** Graph itself rejected by the generator - as opposed to a bug, which must propagate. */
final class CatalogRejected extends RuntimeException {
    CatalogRejected(String message) {
        super(message);
    }

    CatalogRejected(IllegalArgumentException cause) {
        super(cause.getMessage() != null ? cause.getMessage() : cause.toString(), cause);
    }
}
