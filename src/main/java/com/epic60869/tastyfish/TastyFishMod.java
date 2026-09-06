package com.epic60869.tastyfish;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.UUID;

public final class TastyFishMod implements ClientModInitializer {
    private TastyFishConfig config;
    private final FarmingUploader uploader = new FarmingUploader();
    private String sessionId = FarmingUploader.newSessionId();
    private long lastUploadMillis = 0L;
    private long lastActiveMillis = -1L;
    private boolean wasConnected = false;

    @Override
    public void onInitializeClient() {
        Minecraft minecraft = Minecraft.getInstance();
        Path configPath = minecraft.gameDirectory.toPath().resolve("config").resolve("tastyfish-mod.json");
        config = TastyFishConfig.load(configPath);
        FarmingRngTracker.get().register();
        TastyFishRngHud.register(config);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        registerCommands();
        System.out.println("[TastyFish] SkySoft integration and farming RNG overlay loaded.");
    }

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("tf").executes(context -> openMenu()));
            dispatcher.register(ClientCommands.literal("tastyfish").executes(context -> openMenu()));
        });
    }

    private int openMenu() {
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().gui.setScreen(new TastyFishScreen(config)));
        return 1;
    }

    private void tick(Minecraft minecraft) {
        boolean connected = minecraft.player != null;
        if (!connected) { wasConnected = false; return; }
        if (!wasConnected) {
            lastUploadMillis = 0L;
            wasConnected = true;
            TastyFishVersionChecker.check(minecraft);
        }

        long now = System.currentTimeMillis();
        if (now - lastUploadMillis < config.uploadIntervalSeconds * 1000L) return;
        lastUploadMillis = now;
        SkysoftSessionReader.Snapshot snapshot = SkysoftSessionReader.read();
        if (!snapshot.valid()) return;

        if (lastActiveMillis >= 0L && snapshot.activeMillis() < lastActiveMillis) sessionId = FarmingUploader.newSessionId();
        lastActiveMillis = snapshot.activeMillis();

        String username = minecraft.getUser().getName();
        UUID uuid = minecraft.getUser().getProfileId();
        uploader.upload(config, username, uuid == null ? "" : uuid.toString(), currentSkysoftProfile(), sessionId, snapshot);
    }

    private String currentSkysoftProfile() {
        try {
            Class<?> api = Class.forName("com.skysoft.data.hypixel.SkyBlockProfileApi");
            Field field = api.getDeclaredField("currentProfileKey");
            field.setAccessible(true);
            Object value = field.get(null);
            return value == null ? "" : value.toString();
        } catch (Throwable ignored) { return ""; }
    }
}
