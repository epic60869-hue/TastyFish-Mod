package com.epic60869.tastyfish.mixin;

import com.epic60869.tastyfish.SkysoftProfitTrackerAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;

import java.util.Map;

@Mixin(targets = "com.skysoft.features.profit.ProfitTracker")
public abstract class SkysoftProfitTrackerMixin implements SkysoftProfitTrackerAccessor {
    @Shadow @Final private static Map<String, ?> sessionStats;

    @Override
    public Map<String, ?> tastyfish$getSessionStats() {
        return sessionStats;
    }
}
