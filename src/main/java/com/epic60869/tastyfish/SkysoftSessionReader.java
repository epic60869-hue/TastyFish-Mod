package com.epic60869.tastyfish;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SkysoftSessionReader {
    private static final String FARMING = "FARMING";
    private static final String PROFIT_TRACKER_CLASS = "com.skysoft.features.profit.ProfitTracker";
    private static final String TARGET_CLASS = "com.skysoft.features.profit.ProfitTrackerTarget";
    private static final String PRESET_CLASS = "com.skysoft.features.profit.ProfitTrackerPreset";

    private static boolean methodDebugPrinted = false;

    private SkysoftSessionReader() {}

    public static Snapshot read() {
        try {
            Class<?> trackerClass = Class.forName(PROFIT_TRACKER_CLASS);
            Object tracker = getKotlinObjectInstance(trackerClass);

            Field sessionStatsField = findField(trackerClass, "sessionStats");
            sessionStatsField.setAccessible(true);
            Object rawStatsMap = sessionStatsField.get(tracker);

            if (!(rawStatsMap instanceof Map<?, ?> source)) {
                System.err.println("[TastyFish] Skysoft ProfitTracker sessionStats is not a Map.");
                return Snapshot.invalid();
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
            long pricedItemTypes = 0L;
            boolean hadItems = !items.isEmpty();

            for (Map.Entry<String, Long> entry : items.entrySet()) {
                try {
                    Double unitValue = skysoftUnitValue(trackerClass, tracker, target, entry.getKey());
                    if (unitValue != null && Double.isFinite(unitValue)) {
                        itemValue += unitValue * entry.getValue();
                        valuedItems += entry.getValue();
                        pricedItemTypes++;
                    }
                } catch (Throwable itemError) {
                    // One unknown/unpriced item must not destroy the entire tracker.
                    // Skysoft itself also ignores items for which no usable price exists.
                }
            }

            // If Skysoft has tracked items but none could be valued, do not upload a
            // fabricated zero. The caller will keep the last server value intact and
            // retry on the next interval.
            if (hadItems && pricedItemTypes == 0L) {
                return Snapshot.invalid();
            }

            double coinCosts = costs.getOrDefault("Coins", 0L).doubleValue();
            double profit = itemValue + coins - coinCosts;

            if (!Double.isFinite(profit)) return Snapshot.invalid();

            return new Snapshot(
                items,
                pests,
                activeMillis,
                actions,
                coins,
                profit,
                valuedItems,
                true
            );
        } catch (Throwable error) {
            System.err.println("[TastyFish] Failed to read Skysoft farming session: " + rootMessage(error));
            return Snapshot.invalid();
        }
    }

    private static Object getKotlinObjectInstance(Class<?> type) throws ReflectiveOperationException {
        try {
            Field instance = type.getField("INSTANCE");
            instance.setAccessible(true);
            return instance.get(null);
        } catch (NoSuchFieldException ignored) {
            Field instance = findField(type, "INSTANCE");
            instance.setAccessible(true);
            return instance.get(null);
        }
    }

    private static Object createFarmingTarget() throws ReflectiveOperationException {
        Class<?> targetClass = Class.forName(TARGET_CLASS);
        Class<?> presetClass = Class.forName(PRESET_CLASS);
        @SuppressWarnings("unchecked")
        Object farmingPreset = Enum.valueOf(
            (Class<? extends Enum>) presetClass.asSubclass(Enum.class),
            FARMING
        );

        // Normal Kotlin data-object companion path.
        try {
            Field companionField = findField(targetClass, "Companion");
            companionField.setAccessible(true);
            Object companion = companionField.get(null);
            for (Method method : allMethods(companion.getClass())) {
                if (!method.getName().equals("preset") || method.getParameterCount() != 1) continue;
                if (!method.getParameterTypes()[0].isAssignableFrom(presetClass)) continue;
                method.setAccessible(true);
                return method.invoke(companion, farmingPreset);
            }
        } catch (Throwable ignored) {
            // Fall through to constructor-based Kotlin fallback.
        }

        // Fallback for a compiler/version where the companion helper is not exposed.
        for (Constructor<?> constructor : targetClass.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 2 && parameters[0].isAssignableFrom(presetClass)
                && parameters[1] == String.class) {
                constructor.setAccessible(true);
                return constructor.newInstance(farmingPreset, null);
            }
            if (parameters.length == 1 && parameters[0].isAssignableFrom(presetClass)) {
                constructor.setAccessible(true);
                return constructor.newInstance(farmingPreset);
            }
        }

        throw new NoSuchMethodException("Could not construct Skysoft ProfitTrackerTarget.FARMING");
    }

    private static Double skysoftUnitValue(
        Class<?> trackerClass,
        Object tracker,
        Object target,
        String itemId
    ) throws ReflectiveOperationException {
        List<Method> candidates = new ArrayList<>();

        for (Method method : allMethods(trackerClass)) {
            String name = method.getName();
            if (!(name.equals("unitValue") || name.startsWith("unitValue") || name.contains("unitValue"))) continue;
            if (method.getParameterCount() != 2) continue;

            Class<?>[] parameters = method.getParameterTypes();
            boolean targetCompatible = parameters[0].isAssignableFrom(target.getClass())
                || target.getClass().isAssignableFrom(parameters[0]);
            boolean stringCompatible = parameters[1].isAssignableFrom(String.class)
                || parameters[1] == Object.class;

            if (targetCompatible && stringCompatible) candidates.add(method);
        }

        // Also inspect inherited/public methods in case Skysoft exposes a bridge method.
        for (Method method : trackerClass.getMethods()) {
            if (!containsSameMethod(candidates, method)) {
                String name = method.getName();
                if (!(name.equals("unitValue") || name.startsWith("unitValue") || name.contains("unitValue"))) continue;
                if (method.getParameterCount() != 2) continue;
                Class<?>[] parameters = method.getParameterTypes();
                boolean targetCompatible = parameters[0].isAssignableFrom(target.getClass())
                    || target.getClass().isAssignableFrom(parameters[0]);
                boolean stringCompatible = parameters[1].isAssignableFrom(String.class)
                    || parameters[1] == Object.class;
                if (targetCompatible && stringCompatible) candidates.add(method);
            }
        }

        if (!methodDebugPrinted) {
            methodDebugPrinted = true;
            List<String> discovered = new ArrayList<>();
            for (Method method : allMethods(trackerClass)) {
                if (method.getName().toLowerCase().contains("unitvalue")) {
                    discovered.add(method.toGenericString());
                }
            }
            System.out.println("[TastyFish] Skysoft unitValue methods: " + discovered);
        }

        Throwable lastFailure = null;
        for (Method method : candidates) {
            try {
                method.setAccessible(true);
                Object receiver = Modifier.isStatic(method.getModifiers()) ? null : tracker;
                Object result = method.invoke(receiver, target, itemId);
                if (result == null) return null;
                if (result instanceof Number number) return number.doubleValue();
            } catch (InvocationTargetException invocation) {
                lastFailure = invocation.getCause() == null ? invocation : invocation.getCause();
            } catch (Throwable failure) {
                lastFailure = failure;
            }
        }

        if (lastFailure != null) {
            throw new ReflectiveOperationException("Skysoft unitValue invocation failed", lastFailure);
        }
        throw new NoSuchMethodException("No compatible ProfitTracker.unitValue method found");
    }

    private static List<Method> allMethods(Class<?> type) {
        List<Method> result = new ArrayList<>();
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!containsSameMethod(result, method)) result.add(method);
            }
            for (Class<?> iface : current.getInterfaces()) {
                for (Method method : iface.getMethods()) {
                    if (!containsSameMethod(result, method)) result.add(method);
                }
            }
            current = current.getSuperclass();
        }
        return result;
    }

    private static boolean containsSameMethod(List<Method> methods, Method candidate) {
        for (Method method : methods) {
            if (method.getName().equals(candidate.getName())
                && java.util.Arrays.equals(method.getParameterTypes(), candidate.getParameterTypes())) {
                return true;
            }
        }
        return false;
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
        long valuedItems,
        boolean valid
    ) {
        public static Snapshot empty() {
            return new Snapshot(Map.of(), Map.of(), 0L, 0L, 0.0, 0.0, 0L, true);
        }

        public static Snapshot invalid() {
            return new Snapshot(Map.of(), Map.of(), 0L, 0L, 0.0, 0.0, 0L, false);
        }
    }
}
