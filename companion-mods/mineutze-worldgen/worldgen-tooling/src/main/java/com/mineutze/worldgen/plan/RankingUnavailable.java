package com.mineutze.worldgen.plan;

/** No usable ranking for this graph (none saved, or saved for another graph): a user step to take, not a crash. */
public final class RankingUnavailable extends RuntimeException {
    RankingUnavailable(String message) {
        super(message);
    }
}
