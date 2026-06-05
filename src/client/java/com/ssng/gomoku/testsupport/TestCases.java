package com.ssng.gomoku.testsupport;

import com.ssng.gomoku.game.GomokuBoard;
import com.ssng.gomoku.game.Stone;
import com.ssng.gomoku.protocol.IrcMessage;
import com.ssng.gomoku.protocol.IrcProtocol;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TestCases {
    private TestCases() {
    }

    public static void main(String[] args) {
        testProtocolRoundTrip();
        testChatScan();
        testIrcTellChatScan();
        testWrappedAndFormattedIrcTellChatScan();
        testColorAssignmentIsDeterministic();
        testGomokuWins();
        System.out.println("SSNG self-tests passed");
    }

    private static void testProtocolRoundTrip() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("x", 3);
        payload.put("y", 4);
        payload.put("color", "black");
        IrcMessage message = new IrcMessage(1, "move", "gid", "alice", "bob", 7, 123L, payload);
        String encoded = IrcProtocol.encode(message);
        check(encoded.startsWith("SSNG1|type=move") || encoded.contains("|type=move|"), "plain protocol");
        IrcMessage decoded = IrcProtocol.decodePayload(encoded).orElseThrow();
        check(decoded.type().equals("move"), "type round-trip");
        check(decoded.seq() == 7, "seq round-trip");
        check(String.valueOf(decoded.payload().get("x")).equals("3"), "payload round-trip");
    }

    private static void testChatScan() {
        IrcMessage message = new IrcMessage(1, "invite", "gid", "alice", "bob", 1, 123L, Map.of("nonceA", "n"));
        String chat = "[alice] whispers: " + IrcProtocol.encode(message);
        IrcProtocol.InboundIrcMessage inbound = IrcProtocol.scanChat(chat, "\\[([^\\]]+)\\]").orElseThrow();
        check(inbound.sender().equals("alice"), "sender regex");
        check(inbound.message().type().equals("invite"), "scan payload");
    }

    private static void testIrcTellChatScan() {
        IrcMessage message = new IrcMessage(1, "invite", "gid", "ryorin", "local", 1, 123L, Map.of("nonceA", "n"));
        String chat = "[S] [IRC] ryorin -> You: " + IrcProtocol.encode(message);
        IrcProtocol.InboundIrcMessage inbound = IrcProtocol.scanChat(chat, "\\[([^\\]]+)\\]").orElseThrow();
        check(inbound.sender().equals("ryorin"), "irc tell sender");
        check(inbound.message().from().equals("ryorin"), "irc tell payload");
    }

    private static void testWrappedAndFormattedIrcTellChatScan() {
        IrcMessage message = new IrcMessage(1, "invite", "gid", "ryorin", "local", 1, 123L, Map.of("nonceA", "n"));
        String encoded = IrcProtocol.encode(message);
        String wrapped = encoded.substring(0, 20) + "\n" + encoded.substring(20, 45) + " \n" + encoded.substring(45);
        String chat = "§a[S] §6[IRC] §fryorin -> You: §e" + wrapped;
        IrcProtocol.InboundIrcMessage inbound = IrcProtocol.scanChat(chat, "\\[([^\\]]+)\\]").orElseThrow();
        check(inbound.sender().equals("ryorin"), "wrapped irc tell sender");
        check(inbound.message().type().equals("invite"), "wrapped irc tell payload");
    }

    private static void testColorAssignmentIsDeterministic() {
        IrcProtocol.ColorAssignment one = IrcProtocol.assignColors("gid", "a", "b", "alice", "bob");
        IrcProtocol.ColorAssignment two = IrcProtocol.assignColors("gid", "a", "b", "alice", "bob");
        check(one.equals(two), "color deterministic");
        check((one.black().equals("alice") && one.white().equals("bob")) || (one.black().equals("bob") && one.white().equals("alice")), "color valid");
    }

    private static void testGomokuWins() {
        GomokuBoard board = new GomokuBoard();
        for (int i = 0; i < 4; i++) {
            check(!board.place(i, 7, Stone.BLACK).win(), "no early horizontal win");
        }
        check(board.place(4, 7, Stone.BLACK).win(), "horizontal win");
        check(board.winner() == Stone.BLACK, "winner black");

        GomokuBoard longLine = new GomokuBoard();
        for (int i = 0; i < 5; i++) {
            longLine.place(i, i, Stone.WHITE);
        }
        check(longLine.winner() == Stone.WHITE, "diagonal win");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
