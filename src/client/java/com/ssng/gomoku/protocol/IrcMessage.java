package com.ssng.gomoku.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

public record IrcMessage(
    int v,
    String type,
    String gameId,
    String from,
    String to,
    int seq,
    long ts,
    Map<String, Object> payload
) {
    public IrcMessage {
        payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
    }
}
