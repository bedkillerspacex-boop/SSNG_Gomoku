package com.ssng.gomoku.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IrcProtocol {
    public static final String PREFIX = "SSNG1";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]+");
    private static final Pattern IRC_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_\\-]+");
    private static final Pattern IRC_TELL_PATTERN = Pattern.compile("\\b([A-Za-z0-9_\\-]+)\\s*->\\s*You\\s*:", Pattern.CASE_INSENSITIVE);

    private IrcProtocol() {
    }

    public static String encode(IrcMessage message) {
        validateOutboundMessage(message);
        StringBuilder builder = new StringBuilder(PREFIX);
        appendField(builder, "v", message.v());
        appendField(builder, "type", message.type());
        appendField(builder, "gameId", message.gameId());
        appendField(builder, "from", message.from());
        appendField(builder, "to", message.to());
        appendField(builder, "seq", message.seq());
        appendField(builder, "ts", message.ts());
        for (Map.Entry<String, Object> entry : message.payload().entrySet()) {
            appendField(builder, entry.getKey(), entry.getValue());
        }
        return builder.toString();
    }

    public static Optional<IrcMessage> decodePayload(String encoded) {
        try {
            Map<String, String> fields = parseFields(encoded);
            if (fields.isEmpty()) {
                return Optional.empty();
            }
            if (!hasValidHeader(fields)) {
                return Optional.empty();
            }
            Map<String, Object> payloadMap = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                if (!isHeaderField(entry.getKey())) {
                    payloadMap.put(entry.getKey(), entry.getValue());
                }
            }
            return Optional.of(new IrcMessage(
                intValue(fields.get("v")),
                stringValue(fields.get("type")),
                stringValue(fields.get("gameId")),
                stringValue(fields.get("from")),
                stringValue(fields.get("to")),
                intValue(fields.get("seq")),
                longValue(fields.get("ts")),
                payloadMap
            ));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public static Optional<InboundIrcMessage> scanChat(String chatText, String senderRegex) {
        if (chatText == null) {
            return Optional.empty();
        }
        String cleanText = stripFormattingCodes(chatText);
        int prefixIndex = cleanText.indexOf("SSNG1");
        if (prefixIndex < 0) {
            return Optional.empty();
        }
        Optional<String> encodedPayload = extractPayload(cleanText, prefixIndex);
        if (encodedPayload.isEmpty()) {
            return Optional.empty();
        }
        String encoded = encodedPayload.get();
        Optional<IrcMessage> decoded = decodePayload(encoded);
        if (decoded.isEmpty()) {
            return Optional.empty();
        }
        String sender = extractBuiltInSender(cleanText)
            .or(() -> extractSender(cleanText, senderRegex))
            .orElse(decoded.get().from());
        if (!sender.equalsIgnoreCase(decoded.get().from())) {
            return Optional.empty();
        }
        return Optional.of(new InboundIrcMessage(sender, decoded.get()));
    }

    public static String tellCommand(String peerIrcName, IrcMessage message) {
        if (!isIrcName(peerIrcName) || !peerIrcName.equalsIgnoreCase(message.to())) {
            throw new IllegalArgumentException("unsafe IRC target");
        }
        return ".irc chat $tell " + peerIrcName + " " + encode(message);
    }

    public static String newGameId() {
        return UUID.randomUUID().toString();
    }

    public static String nonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static ColorAssignment assignColors(String gameId, String nonceA, String nonceB, String inviter, String accepter) {
        byte[] hash = sha256((gameId + ":" + nonceA + ":" + nonceB).getBytes(StandardCharsets.UTF_8));
        boolean inviterBlack = (hash[0] & 1) == 0;
        return inviterBlack ? new ColorAssignment(inviter, accepter) : new ColorAssignment(accepter, inviter);
    }

    public static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static Optional<String> extractSender(String chatText, String senderRegex) {
        if (senderRegex == null || senderRegex.isBlank()) {
            return Optional.empty();
        }
        try {
            Matcher matcher = Pattern.compile(senderRegex).matcher(chatText);
            if (!matcher.find()) {
                return Optional.empty();
            }
            if (matcher.groupCount() >= 1) {
                return Optional.ofNullable(matcher.group(1));
            }
        } catch (RuntimeException ignored) {
        }
        return Optional.empty();
    }

    private static Optional<String> extractBuiltInSender(String chatText) {
        Matcher tellMatcher = IRC_TELL_PATTERN.matcher(chatText);
        if (tellMatcher.find()) {
            return Optional.ofNullable(tellMatcher.group(1));
        }
        return Optional.empty();
    }

    private static Optional<String> extractPayload(String text, int start) {
        StringBuilder payload = new StringBuilder();
        boolean started = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isProtocolChar(c)) {
                payload.append(c);
                started = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (started) {
                break;
            }
        }
        return payload.isEmpty() ? Optional.empty() : Optional.of(payload.toString());
    }

    private static boolean isProtocolChar(char c) {
        return (c >= 'A' && c <= 'Z')
            || (c >= 'a' && c <= 'z')
            || (c >= '0' && c <= '9')
            || c == '_'
            || c == '-'
            || c == '|'
            || c == '='
            || c == ':'
            || c == '.';
    }

    private static String stripFormattingCodes(String text) {
        StringBuilder clean = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00A7' && i + 1 < text.length()) {
                i++;
                continue;
            }
            clean.append(c);
        }
        return clean.toString();
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static void appendField(StringBuilder builder, String key, Object value) {
        if (!isSafeKey(key)) {
            throw new IllegalArgumentException("unsafe protocol key: " + key);
        }
        builder.append('|').append(cleanField(key)).append('=').append(cleanField(value));
    }

    private static String cleanField(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value)
            .replace("|", "_")
            .replace("=", "_")
            .replaceAll("\\s+", "_");
    }

    private static Map<String, String> parseFields(String encoded) {
        String text = encoded == null ? "" : encoded.trim();
        if (!text.startsWith(PREFIX)) {
            throw new IllegalArgumentException("missing protocol prefix");
        }
        text = text.substring(PREFIX.length());
        if (text.startsWith("|")) {
            text = text.substring(1);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        if (text.isBlank()) {
            return fields;
        }
        for (String part : text.split("\\|", -1)) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("empty protocol field");
            }
            int equals = part.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("malformed protocol field");
            }
            String key = part.substring(0, equals);
            String value = part.substring(equals + 1);
            if (!isSafeKey(key) || fields.containsKey(key)) {
                throw new IllegalArgumentException("invalid protocol key");
            }
            fields.put(key, value);
        }
        return fields;
    }

    private static void validateOutboundMessage(IrcMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("message is required");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("v", String.valueOf(message.v()));
        fields.put("type", stringValue(message.type()));
        fields.put("gameId", stringValue(message.gameId()));
        fields.put("from", stringValue(message.from()));
        fields.put("to", stringValue(message.to()));
        fields.put("seq", String.valueOf(message.seq()));
        fields.put("ts", String.valueOf(message.ts()));
        if (!hasValidHeader(fields)) {
            throw new IllegalArgumentException("invalid protocol header");
        }
        for (String key : message.payload().keySet()) {
            if (!isSafePayloadKey(key)) {
                throw new IllegalArgumentException("unsafe payload key: " + key);
            }
        }
    }

    private static boolean hasValidHeader(Map<String, String> fields) {
        try {
            int version = intValue(fields.get("v"));
            String type = stringValue(fields.get("type"));
            String game = stringValue(fields.get("gameId"));
            String from = stringValue(fields.get("from"));
            String to = stringValue(fields.get("to"));
            int seq = intValue(fields.get("seq"));
            long ts = longValue(fields.get("ts"));
            boolean ack = "ack".equals(type);
            return version == 1
                && isToken(type)
                && (ack || isToken(game))
                && isIrcName(from)
                && isIrcName(to)
                && seq >= 0
                && ts >= 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isSafePayloadKey(String key) {
        return isSafeKey(key) && !isHeaderField(key);
    }

    private static boolean isSafeKey(String key) {
        return key != null && TOKEN_PATTERN.matcher(key).matches();
    }

    private static boolean isToken(String value) {
        return value != null && TOKEN_PATTERN.matcher(value).matches();
    }

    private static boolean isIrcName(String value) {
        return value != null && IRC_NAME_PATTERN.matcher(value).matches();
    }

    private static boolean isHeaderField(String key) {
        return switch (key) {
            case "v", "type", "gameId", "from", "to", "seq", "ts" -> true;
            default -> false;
        };
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(stringValue(value));
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(stringValue(value));
    }

    public record InboundIrcMessage(String sender, IrcMessage message) {
    }

    public record ColorAssignment(String black, String white) {
    }
}
