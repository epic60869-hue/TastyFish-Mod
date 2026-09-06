package com.epic60869.tastyfish;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.UUID;

public final class TastyFishMod implements ClientModInitializer {
    private TastyFishConfig config;
    private final FarmingUploader uploader = new FarmingUploader();
    private String sessionId = FarmingUploader.newSessionId();
    private long lastUploadMillis = 0L;
    private boolean wasConnected = false;

    @Override
    public void onInitializeClient() {
        Path configPath = Minecraft.getInstance().gameDirectory.toPath()
            .resolve("config")
            .resolve("tastyfish-mod.json");
        config = TastyFishConfig.load(configPath);

        FarmingProfitTracker.get().register();
        FarmingProfitHud.register(config);
        TastyFishRngHud.register(config);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        registerCommands();
        System.out.println("[TastyFish] Standalone farming tracker loaded. SkySoft is optional and not required.");
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
        FarmingProfitTracker.get().tick(minecraft);

        boolean connected = minecraft.player != null;
        if (!connected) {
            wasConnected = false;
            return;
        }

        if (!wasConnected) {
            lastUploadMillis = 0L;
            wasConnected = true;
            sessionId = FarmingUploader.newSessionId();
            TastyFishVersionChecker.check(minecraft);
        }

        long now = System.currentTimeMillis();
        if (now - lastUploadMillis < config.uploadIntervalSeconds * 1000L) return;
        lastUploadMillis = now;

        FarmingSessionReader.Snapshot snapshot = FarmingSessionReader.read();
        if (!snapshot.valid()) return;

        String username = minecraft.getUser().getName();
        UUID uuid = minecraft.getUser().getProfileId();
        uploader.upload(config, username, uuid == null ? "" : uuid.toString(), "", sessionId, snapshot);
    }
}
