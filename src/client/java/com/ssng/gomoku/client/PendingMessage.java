package com.ssng.gomoku.client;

import com.ssng.gomoku.protocol.IrcMessage;

public final class PendingMessage {
    private final IrcMessage message;
    private final String command;
    private int retryCount;
    private long lastSendTime;

    public PendingMessage(IrcMessage message, String command) {
        this.message = message;
        this.command = command;
        this.retryCount = 0;
        this.lastSendTime = System.currentTimeMillis();
    }

    public IrcMessage message() {
        return message;
    }

    public String command() {
        return command;
    }

    public int retryCount() {
        return retryCount;
    }

    public long lastSendTime() {
        return lastSendTime;
    }

    public void incrementRetry() {
        this.retryCount++;
        this.lastSendTime = System.currentTimeMillis();
    }
}