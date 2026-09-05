package com.epic60869.tastyfish;

import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SkysoftSessionReader {
    private SkysoftSessionReader() {}

    public static Snapshot read() {
        try {
            Class<?> trackerClass = Class.forName("com.skysoft.features.profit.ProfitTracker");
            Field instanceField = trackerClass.getField("INSTANCE");
            Object tracker = instanceField.get(null);

            Method sessionStatsMethod = tracker.getClass().getMethod("getSessionStats");
            Object allStats = sessionStatsMethod.invoke(tracker);
            if (!(allStats instanceof Map<?, ?> statsMap)) {
                return Snapshot.empty();
            }

            Object stats = statsMap.get("FARMING");
            if (stats == null) return Snapshot.empty();

            Map<String, Long> items = longMap(stats, "itemCounts");
            Map<String, Long> pests = longMap(stats, "pestKills");
            long activeMillis = longField(stats, "activeMillis");
            long actions = longField(stats, "actions");
            double coins = doubleField(stats, "coins");

            double itemValue = 0.0;
            for (Map.Entry<String, Long> entry : items.entrySet()) {
                Double unitValue = unitValue(tracker, entry.getKey());
                if (unitValue != null) {
                    itemValue += unitValue * entry.getValue();
                }
            }

            return new Snapshot(items, pests, activeMillis, actions, coins, itemValue + coins);
        } catch (Throwable ignored) {
            return Snapshot.empty();
        }
    }

    private static Double unitValue(Object tracker, String itemId) {
        try {
            Class<?> targetClass = Class.forName("com.skysoft.features.profit.ProfitTrackerTarget");
            Class<?> presetClass = Class.forName("com.skysoft.features.profit.ProfitTrackerPreset");
            Object farmingPreset = Enum.valueOf((Class) presetClass, "FARMING");
            var constructor = targetClass.getDeclaredConstructor(presetClass, String.class);
            constructor.setAccessible(true);
            Object target = constructor.newInstance(farmingPreset, null);

            Method method = tracker.getClass().getDeclaredMethod("unitValue", targetClass, String.class);
            method.setAccessible(true);
            Object result = method.invoke(tracker, target, itemId);
            return result instanceof Number number ? number.doubleValue() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Map<String, Long> longMap(Object object, String fieldName) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        Map<?, ?> source = (Map<?, ?>) field.get(object);
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() instanceof Number value) {
                result.put(key, value.longValue());
            }
        }
        return result;
    }

    private static long longField(Object object, String fieldName) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return ((Number) field.get(object)).longValue();
    }

    private static double doubleField(Object object, String fieldName) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return ((Number) field.get(object)).doubleValue();
    }

    public record Snapshot(
        Map<String, Long> items,
        Map<String, Long> pests,
        long activeMillis,
        long actions,
        double coins,
        double profit
    ) {
        public static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), 0L, 0L, 0.0, 0.0);
        }
    }
}
