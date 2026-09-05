package com.epic60869.tastyfish;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SkysoftSessionReader {
    private static final String FARMING = "FARMING";
    private static final String PROFIT_TRACKER_CLASS = "com.skysoft.features.profit.ProfitTracker";
    private static final String TARGET_CLASS = "com.skysoft.features.profit.ProfitTrackerTarget";
    private static final String PRESET_CLASS = "com.skysoft.features.profit.ProfitTrackerPreset";

    private SkysoftSessionReader() {}

    public static Snapshot read() {
        try {
            Class<?> trackerClass = Class.forName(PROFIT_TRACKER_CLASS);
            Object tracker = trackerClass.getField("INSTANCE").get(null);

            Field sessionStatsField = findField(trackerClass, "sessionStats");
            sessionStatsField.setAccessible(true);
            Object rawStatsMap = sessionStatsField.get(tracker);

            if (!(rawStatsMap instanceof Map<?, ?> source)) {
                System.err.println("[TastyFish] Skysoft ProfitTracker sessionStats is not a Map.");
                return Snapshot.empty();
            }

            Object stats = source.get(FARMING);
            if (stats == null) return Snapshot.empty();

            Map<String, Long> items = longMap(stats, "itemCounts");
            Map<String, Long> pests = longMap(stats, "pestKills");
            Map<String, Long> costs = longMap(stats, "costs");
            long activeMillis = longField(stats, "activeMillis");
            long actions = longField(stats, "actions");
            double coins = doubleField(stats, "coins");

            Object target = createFarmingTarget();
            double itemValue = 0.0;
            long valuedItems = 0L;

            for (Map.Entry<String, Long> entry : items.entrySet()) {
                Double unitValue = skysoftUnitValue(trackerClass, tracker, target, entry.getKey());
                if (unitValue != null && Double.isFinite(unitValue)) {
                    itemValue += unitValue * entry.getValue();
                    valuedItems += entry.getValue();
                }
            }

            double coinCosts = costs.getOrDefault("Coins", 0L).doubleValue();
            double profit = itemValue + coins - coinCosts;

            return new Snapshot(items, pests, activeMillis, actions, coins, profit, valuedItems);
        } catch (Throwable error) {
            System.err.println("[TastyFish] Failed to read Skysoft farming session: " + rootMessage(error));
            return Snapshot.empty();
        }
    }

    private static Object createFarmingTarget() throws ReflectiveOperationException {
        Class<?> targetClass = Class.forName(TARGET_CLASS);
        Class<?> presetClass = Class.forName(PRESET_CLASS);
        @SuppressWarnings("unchecked")
        Object farmingPreset = Enum.valueOf((Class<? extends Enum>) presetClass.asSubclass(Enum.class), FARMING);
        Field companionField = targetClass.getField("Companion");
        Object companion = companionField.get(null);
        Method presetMethod = companion.getClass().getMethod("preset", presetClass);
        return presetMethod.invoke(companion, farmingPreset);
    }

    private static Double skysoftUnitValue(Class<?> trackerClass, Object tracker, Object target, String itemId)
        throws ReflectiveOperationException {
        // Skysoft has changed the exact JVM signature of this Kotlin-internal
        // helper between releases. Find the real method instead of assuming
        // getDeclaredMethod() can resolve it from the source signature.
        Class<?> current = trackerClass;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals("unitValue") || method.getParameterCount() != 2) continue;
                Class<?>[] parameters = method.getParameterTypes();
                if (!parameters[1].equals(String.class) || !parameters[0].isInstance(target)) continue;
                method.setAccessible(true);
                Object result = method.invoke(tracker, target, itemId);
                return result instanceof Number number ? number.doubleValue() : null;
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException("No compatible ProfitTracker.unitValue method found");
    }

    private static Map<String, Long> longMap(Object object, String fieldName) throws ReflectiveOperationException {
        Field field = findField(object.getClass(), fieldName);
        field.setAccessible(true);
        Object raw = field.get(object);
        if (!(raw instanceof Map<?, ?> source)) {
            throw new IllegalStateException("Skysoft field " + fieldName + " is not a Map");
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
            throw new IllegalStateException("Skysoft field " + fieldName + " is not numeric");
        }
        return number.longValue();
    }

    private static double doubleField(Object object, String fieldName) throws ReflectiveOperationException {
        Field field = findField(object.getClass(), fieldName);
        field.setAccessible(true);
        Object value = field.get(object);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Skysoft field " + fieldName + " is not numeric");
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
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null ? current.toString() : message;
    }

    public record Snapshot(
        Map<String, Long> items,
        Map<String, Long> pests,
        long activeMillis,
        long actions,
        double coins,
        double profit,
        long valuedItems
    ) {
        public static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), 0L, 0L, 0.0, 0.0, 0L);
        }
    }
}
