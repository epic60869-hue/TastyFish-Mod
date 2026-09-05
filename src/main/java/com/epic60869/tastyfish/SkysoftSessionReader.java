package com.epic60869.tastyfish;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SkysoftSessionReader {
    private static final String FARMING = "FARMING";

    private SkysoftSessionReader() {}

    public static Snapshot read() {
        try {
            Class<?> trackerClass = Class.forName("com.skysoft.features.profit.ProfitTracker");
            Field instanceField = trackerClass.getField("INSTANCE");
            Object tracker = instanceField.get(null);

            // SkySoft's sessionStats is private. TastyFish exposes it through
            // SkysoftProfitTrackerMixin instead of relying on a public getter.
            if (!(tracker instanceof SkysoftProfitTrackerAccessor accessor)) {
                System.err.println("[TastyFish] SkySoft ProfitTracker was found, but the TastyFish accessor mixin is not applied.");
                return Snapshot.empty();
            }

            Map<String, ?> statsMap = accessor.tastyfish$getSessionStats();
            if (statsMap == null) return Snapshot.empty();

            Object stats = statsMap.get(FARMING);
            if (stats == null) return Snapshot.empty();

            Map<String, Long> items = longMap(stats, "itemCounts");
            Map<String, Long> pests = longMap(stats, "pestKills");
            long activeMillis = longField(stats, "activeMillis");
            long actions = longField(stats, "actions");
            double coins = doubleField(stats, "coins");

            double itemValue = 0.0;
            for (Map.Entry<String, Long> entry : items.entrySet()) {
                Double value = unitValue(tracker, entry.getKey());
                if (value != null) {
                    itemValue += value * entry.getValue();
                }
            }

            double profit = itemValue + coins;

            return new Snapshot(items, pests, activeMillis, actions, coins, profit);
        } catch (Throwable error) {
            System.err.println("[TastyFish] Failed to read SkySoft farming session: " + rootMessage(error));
            return Snapshot.empty();
        }
    }

    private static Double unitValue(Object tracker, String itemId) {
        try {
            Class<?> targetClass = Class.forName("com.skysoft.features.profit.ProfitTrackerTarget");
            Class<?> presetClass = Class.forName("com.skysoft.features.profit.ProfitTrackerPreset");

            @SuppressWarnings({"rawtypes", "unchecked"})
            Object farmingPreset = Enum.valueOf((Class) presetClass, FARMING);

            Constructor<?> constructor = targetClass.getDeclaredConstructor(presetClass, String.class);
            constructor.setAccessible(true);
            Object target = constructor.newInstance(farmingPreset, null);

            Method method = tracker.getClass().getDeclaredMethod("unitValue", targetClass, String.class);
            method.setAccessible(true);
            Object result = method.invoke(tracker, target, itemId);

            return result instanceof Number number ? number.doubleValue() : null;
        } catch (Throwable error) {
            return null;
        }
    }

    private static Map<String, Long> longMap(Object object, String fieldName) throws ReflectiveOperationException {
        Field field = findField(object.getClass(), fieldName);
        field.setAccessible(true);

        Object raw = field.get(object);
        if (!(raw instanceof Map<?, ?> source)) {
            throw new IllegalStateException("SkySoft field " + fieldName + " is not a Map");
        }

        Map<String, Long> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() instanceof Number value) {
                result.put(key, value.longValue());
            }
        }
        return result;
    }

    private static long longField(Object object, String fieldName) throws ReflectiveOperationException {
        Field field = findField(object.getClass(), fieldName);
        field.setAccessible(true);
        Object value = field.get(object);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("SkySoft field " + fieldName + " is not numeric");
        }
        return number.longValue();
    }

    private static double doubleField(Object object, String fieldName) throws ReflectiveOperationException {
        Field field = findField(object.getClass(), fieldName);
        field.setAccessible(true);
        Object value = field.get(object);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("SkySoft field " + fieldName + " is not numeric");
        }
        return number.doubleValue();
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.toString() : message;
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
