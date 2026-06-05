package com.ssng.gomoku.config;

import com.ssng.gomoku.protocol.JsonCodec;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SsngConfig {
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("ssng-gomoku.json");
    private static final String DEFAULT_REGEX = "(?:from|<|\\[)([A-Za-z0-9_\\-]+)(?:>|\\])?";

    private String localIrcName = "";
    private String peerIrcName = "";
    // senderRegex硬编码，不再暴露给用户
    private static final String SENDER_REGEX = "(?:from|<|\\[)([A-Za-z0-9_\\-]+)(?:>|\\])?";
    private boolean autoDetected = false;

    public static SsngConfig load() {
        SsngConfig config = new SsngConfig();
        if (!Files.exists(PATH)) {
            return config;
        }
        try {
            Object parsed = JsonCodec.parse(Files.readString(PATH, StandardCharsets.UTF_8));
            if (parsed instanceof Map<?, ?> map) {
                config.localIrcName = string(map.get("localIrcName"), config.localIrcName);
                config.peerIrcName = string(map.get("peerIrcName"), config.peerIrcName);
                // senderRegex不再从配置文件读取，使用硬编码值
                config.autoDetected = bool(map.get("autoDetected"), config.autoDetected);
            }
        } catch (RuntimeException | IOException ignored) {
        }
        return config;
    }

    public void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("localIrcName", localIrcName);
        root.put("peerIrcName", peerIrcName);
        // senderRegex不再保存到配置文件，使用硬编码值
        root.put("autoDetected", autoDetected);
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, JsonCodec.stringify(root), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public String localIrcName() {
        return localIrcName;
    }

    public void localIrcName(String localIrcName) {
        this.localIrcName = localIrcName == null ? "" : localIrcName.trim();
    }

    public String peerIrcName() {
        return peerIrcName;
    }

    public void peerIrcName(String peerIrcName) {
        this.peerIrcName = peerIrcName == null ? "" : peerIrcName.trim();
    }

    public String senderRegex() {
        return SENDER_REGEX; // 返回硬编码值
    }

    public boolean autoDetected() {
        return autoDetected;
    }

    public void autoDetected(boolean autoDetected) {
        this.autoDetected = autoDetected;
    }

    private static String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        return fallback;
    }
}
