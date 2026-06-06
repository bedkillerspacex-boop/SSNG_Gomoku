package com.ssng.gomoku.game;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class GomokuBoard {
    public static final int SIZE = 15;

    private final Stone[][] board = new Stone[SIZE][SIZE];
    private int moveNo;
    private Stone winner = Stone.EMPTY;

    public GomokuBoard() {
        clear();
    }

    public void clear() {
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                board[y][x] = Stone.EMPTY;
            }
        }
        moveNo = 0;
        winner = Stone.EMPTY;
    }

    public boolean isInside(int x, int y) {
        return x >= 0 && x < SIZE && y >= 0 && y < SIZE;
    }

    public Stone get(int x, int y) {
        if (!isInside(x, y)) {
            return Stone.EMPTY;
        }
        return board[y][x];
    }

    public boolean canPlace(int x, int y) {
        return winner == Stone.EMPTY && isInside(x, y) && board[y][x] == Stone.EMPTY;
    }

    public MoveResult place(int x, int y, Stone stone) {
        if (stone != Stone.BLACK && stone != Stone.WHITE) {
            return MoveResult.invalid("invalid stone");
        }
        if (!canPlace(x, y)) {
            return MoveResult.invalid("position is unavailable");
        }
        board[y][x] = stone;
        moveNo++;
        if (hasFiveFrom(x, y, stone)) {
            winner = stone;
            return MoveResult.win(moveNo, stone);
        }
        return MoveResult.ok(moveNo);
    }

    public int moveNo() {
        return moveNo;
    }

    public Stone winner() {
        return winner;
    }

    public String hash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder state = new StringBuilder(SIZE * SIZE + 8);
            state.append(moveNo).append(':');
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    state.append(switch (board[y][x]) {
                        case EMPTY -> '.';
                        case BLACK -> 'B';
                        case WHITE -> 'W';
                    });
                }
            }
            return HexFormat.of().formatHex(digest.digest(state.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public String shortHash() {
        String fullHash = hash();
        return fullHash.length() >= 6 ? fullHash.substring(0, 6) : fullHash;
    }

    private boolean hasFiveFrom(int x, int y, Stone stone) {
        return countLine(x, y, 1, 0, stone) >= 5
            || countLine(x, y, 0, 1, stone) >= 5
            || countLine(x, y, 1, 1, stone) >= 5
            || countLine(x, y, 1, -1, stone) >= 5;
    }

    private int countLine(int x, int y, int dx, int dy, Stone stone) {
        return 1 + countDirection(x, y, dx, dy, stone) + countDirection(x, y, -dx, -dy, stone);
    }

    private int countDirection(int x, int y, int dx, int dy, Stone stone) {
        int count = 0;
        int cx = x + dx;
        int cy = y + dy;
        while (isInside(cx, cy) && board[cy][cx] == stone) {
            count++;
            cx += dx;
            cy += dy;
        }
        return count;
    }

    public record MoveResult(boolean valid, boolean win, int moveNo, Stone winner, String message) {
        public static MoveResult ok(int moveNo) {
            return new MoveResult(true, false, moveNo, Stone.EMPTY, "");
        }

        public static MoveResult win(int moveNo, Stone winner) {
            return new MoveResult(true, true, moveNo, winner, "");
        }

        public static MoveResult invalid(String message) {
            return new MoveResult(false, false, -1, Stone.EMPTY, message);
        }
    }
}
