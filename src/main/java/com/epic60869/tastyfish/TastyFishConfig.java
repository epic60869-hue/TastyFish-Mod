package com.epic60869.tastyfish;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TastyFishConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String endpoint = "https://shadowisabot.com/api/farming/update";
    public String apiKey = "PUT_YOUR_FARMING_API_KEY_HERE";
    public int uploadIntervalSeconds = 30;
    public boolean enabled = true;

    public static TastyFishConfig load(Path path) {
        try {
            if (Files.notExists(path)) {
                TastyFishConfig config = new TastyFishConfig();
                config.save(path);
                return config;
            }
            String json = Files.readString(path, StandardCharsets.UTF_8);
            TastyFishConfig config = GSON.fromJson(json, TastyFishConfig.class);
            if (config == null) config = new TastyFishConfig();
            if (config.uploadIntervalSeconds < 10) config.uploadIntervalSeconds = 10;
            return config;
        } catch (Exception e) {
            System.err.println("[TastyFish] Failed to load config: " + e.getMessage());
            return new TastyFishConfig();
        }
    }

    public void save(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[TastyFish] Failed to save config: " + e.getMessage());
        }
    }
}
