package com.ssng.gomoku.game;

public enum Stone {
    EMPTY,
    BLACK,
    WHITE;

    public Stone opposite() {
        return switch (this) {
            case BLACK -> WHITE;
            case WHITE -> BLACK;
            case EMPTY -> EMPTY;
        };
    }

    public String wireName() {
        return name().toLowerCase();
    }

    public static Stone fromWireName(String value) {
        return switch (value == null ? "" : value.toLowerCase()) {
            case "black" -> BLACK;
            case "white" -> WHITE;
            default -> EMPTY;
        };
    }
}
