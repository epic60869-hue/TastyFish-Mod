package com.epic60869.tastyfish;

import java.util.Map;

/** Snapshot source for the standalone TastyFish tracker. */
public final class FarmingSessionReader {
    private FarmingSessionReader() {}

    public static Snapshot read() {
        FarmingProfitTracker tracker = FarmingProfitTracker.get();
        return new Snapshot(
            tracker.itemCounts(),
            tracker.pestKills(),
            tracker.activeMillis(),
            tracker.actions(),
            tracker.coins(),
            tracker.profit(),
            true
        );
    }

    public record Snapshot(
        Map<String, Long> items,
        Map<String, Long> pests,
        long activeMillis,
        long actions,
        double coins,
        double profit,
        boolean valid
    ) {}
}
