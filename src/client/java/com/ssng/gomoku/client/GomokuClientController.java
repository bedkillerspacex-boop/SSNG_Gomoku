
package com.ssng.gomoku.client;

import com.ssng.gomoku.config.SsngConfig;
import com.ssng.gomoku.game.GomokuBoard;
import com.ssng.gomoku.game.Stone;
import com.ssng.gomoku.protocol.IrcMessage;
import com.ssng.gomoku.protocol.IrcProtocol;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.time.Instant;
import java.util.*;

public final class GomokuClientController {
    private static final long INVITE_TIMEOUT_MS = 30_000L;
    private static final long ACK_TIMEOUT_MS = 3_000L; // 3秒超时重传
    private static final int MAX_RETRIES = 3; // 最多重传3次

    private final MinecraftClient client;
    private final SsngConfig config;
    private final GomokuBoard board = new GomokuBoard();

    private MatchStatus status = MatchStatus.IDLE;
    private String statusMessage = "空闲";
    private String gameId = "";
    private String nonceA = "";
    private String nonceB = "";
    private String inviter = "";
    private String peer = "";
    private Stone localStone = Stone.EMPTY;
    private Stone currentTurn = Stone.BLACK;
    private String gameResult = ""; // "win", "lose", "draw", "" 表示游戏结果
    private int outgoingSeq;
    private int lastInboundSeq;
    private long stateDeadlineMs;
    private String lastHandledChatText = "";
    private long lastHandledChatAt;
    private String lastPacketDebug = "未收到 SSNG1 包";
    private String lastSentDebug = "未发送";
    private String lastSentCommand = "";
    private String lastIrcDebug = "未收到IRC消息";
    private int awaitingMoveAck = -1;

    // 可靠传输机制
    private final Map<Long, PendingMessage> pendingAcks = new HashMap<>();
    private final Set<Long> receivedTimestamps = new HashSet<>();

    // IRC自动检测机制
    private enum IrcDetectState {
        NOT_STARTED,    // 未开始检测
        WAIT_WELCOME,   // 等待IRC欢迎消息
        WAIT_ECHO,      // 等待回显消息
        COMPLETED,      // 检测完成
        FALLBACK        // 回退到手动输入
    }
    private IrcDetectState detectState = IrcDetectState.NOT_STARTED;
    private long detectSentTimestamp = 0;
    private long detectStartTime = 0;
    private static final long IRC_WELCOME_TIMEOUT = 5_000L;  // 5秒等待欢迎消息
    private static final long IRC_ECHO_TIMEOUT = 5_000L;     // 5秒等待回显

    // 在线用户列表
    private final Set<String> onlineUsers = new HashSet<>();
    private long lastUserListCheck = 0;
    private static final long USER_LIST_CACHE_MS = 30_000L;    // 30秒缓存
    private boolean hasRequestedInitialUserList = false; // 是否已请求初始用户列表

    public GomokuClientController(MinecraftClient client, SsngConfig config) {
        this.client = client;
        this.config = config;

        // 如果已经自动检测过，就不需要再检测了
        if (config.autoDetected() && !config.localIrcName().isBlank()) {
            detectState = IrcDetectState.COMPLETED;
        }
    }

    public void tick() {
        long now = System.currentTimeMillis();

        // 进入世界后自动请求用户列表（仅一次）
        if (!hasRequestedInitialUserList && client.player != null && client.world != null) {
            if (client.player.networkHandler != null) {
                requestOnlineUsers();
                hasRequestedInitialUserList = true;
            }
        }

        // IRC自动检测逻辑
        handleIrcAutoDetect(now);

        if ((status == MatchStatus.INVITING || status == MatchStatus.WAITING_START) && stateDeadlineMs > 0 && now > stateDeadlineMs) {
            MatchStatus timedOutStatus = status;
            status = MatchStatus.IDLE;
            pendingAcks.clear(); // 清空重传队列 - 超时回到空闲状态
            statusMessage = timedOutStatus == MatchStatus.INVITING ? "邀请超时" : "开局确认超时";
            stateDeadlineMs = 0;
        }

        // 检查需要重传的消息
        List<Long> toRetry = new ArrayList<>();
        List<Long> toRemove = new ArrayList<>();
        for (Map.Entry<Long, PendingMessage> entry : pendingAcks.entrySet()) {
            PendingMessage pending = entry.getValue();
            if (now - pending.lastSendTime() > ACK_TIMEOUT_MS) {
                if (pending.retryCount() < MAX_RETRIES) {
                    toRetry.add(entry.getKey());
                } else {
                    toRemove.add(entry.getKey());
                    // 不显示技术性错误信息
                }
            }
        }

        // 重传消息
        for (Long ts : toRetry) {
            PendingMessage pending = pendingAcks.get(ts);
            if (pending != null) {
                retransmit(pending);
            }
        }

        // 移除失败的消息
        for (Long ts : toRemove) {
            pendingAcks.remove(ts);
        }

        // 清理过期的已接收时间戳（保留最近10分钟的）
        receivedTimestamps.removeIf(ts -> now - ts > 600_000L);
    }

    public void invite(String localIrcName, String peerIrcName) {
        updateNames(localIrcName, peerIrcName);
        if (config.localIrcName().isBlank() || config.peerIrcName().isBlank()) {
            statusMessage = "请正确的填写irc name";
            return;
        }

        // 检查对方是否在线（如果缓存有效）
        long now = System.currentTimeMillis();
        if (now - lastUserListCheck < USER_LIST_CACHE_MS && !onlineUsers.isEmpty()) {
            if (!onlineUsers.contains(config.peerIrcName())) {
                statusMessage = "警告：" + config.peerIrcName() + " 可能不在线";
                // 继续发送邀请，但给出警告
            }
        } else {
            // 缓存过期或为空，请求更新用户列表
            requestOnlineUsers();
        }

        resetMatch();
        gameId = IrcProtocol.newGameId();
        nonceA = IrcProtocol.nonce();
        inviter = config.localIrcName();
        peer = config.peerIrcName();
        outgoingSeq = 1;
        status = MatchStatus.INVITING;
        stateDeadlineMs = System.currentTimeMillis() + INVITE_TIMEOUT_MS;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nonceA", nonceA);
        payload.put("boardSize", GomokuBoard.SIZE);
        payload.put("rules", "freestyle");
        payload.put("colorMode", "random");
        send("invite", payload);
        statusMessage = "正在邀请 " + peer + "...";
    }

    public void acceptInvite() {
        if (status != MatchStatus.INVITED) {
            return;
        }
        pendingAcks.clear(); // 清空之前的队列
        nonceB = IrcProtocol.nonce();
        outgoingSeq = 1;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nonceA", nonceA);
        payload.put("nonceB", nonceB);
        payload.put("boardSize", GomokuBoard.SIZE);
        payload.put("rules", "freestyle");
        send("accept", payload);
        status = MatchStatus.WAITING_START;
        stateDeadlineMs = System.currentTimeMillis() + INVITE_TIMEOUT_MS;
        statusMessage = "已接受，等待开局确认...";
    }

    public void declineInvite() {
        if (status != MatchStatus.INVITED) {
            return;
        }
        send("decline", Map.of("nonceA", nonceA));
        resetMatch();
        statusMessage = "已拒绝邀请";
    }

    public void resign() {
        if (status != MatchStatus.PLAYING) {
            return;
        }
        send("resign", Map.of("boardHash", board.hash()));
        status = MatchStatus.ENDED;
        gameResult = "lose"; // 认输就是输
        statusMessage = "你已认输";
        // 等待resign消息的ACK，收到后再清空队列（在onAck中处理）
    }

    public void clearBoard() {
        // 清空棋盘、游戏结果和状态
        board.clear();
        gameResult = "";
        status = MatchStatus.IDLE;
        pendingAcks.clear(); // 清空重传队列
        statusMessage = "已清空棋盘";
    }

    public void requestSync() {
        if (gameId.isBlank() || peer.isBlank()) {
            statusMessage = "没有可同步的对局";
            return;
        }
        send("sync_request", Map.of("boardHash", board.hash(), "moveNo", board.moveNo()));
        statusMessage = "已请求重新同步";
    }

    public void refreshOnlineUsers() {
        requestOnlineUsers();
        // 不设置"正在刷新"状态，等待解析成功后直接显示结果
    }

    public void placeLocalStone(int x, int y) {
        if (status != MatchStatus.PLAYING) {
            statusMessage = "当前没有进行中的对局";
            return;
        }
        if (localStone != currentTurn) {
            statusMessage = "还没轮到你";
            return;
        }
        GomokuBoard.MoveResult result = board.place(x, y, localStone);
        if (!result.valid()) {
            statusMessage = "无法落子：" + result.message();
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("x", x);
        payload.put("y", y);
        payload.put("color", localStone.wireName());
        payload.put("moveNo", result.moveNo());
        payload.put("boardHash", board.hash());
        send("move", payload);
        awaitingMoveAck = result.moveNo();
        if (result.win()) {
            status = MatchStatus.ENDED;
            gameResult = "win"; // 你赢了
            statusMessage = "你赢了！";
            // 等待move消息的ACK后清空队列
        } else {
            currentTurn = currentTurn.opposite();
            statusMessage = "已落子";
        }
    }

    public void handleChat(String text) {
        if (text == null) {
            return;
        }

        // 去除Minecraft格式化代码
        String cleanText = stripFormattingCodes(text);

        // Debug: 记录原始消息和清理后的消息
        if (cleanText.contains("[S]") || cleanText.contains("[IRC]")) {
            lastIrcDebug = "原始: " + abbreviate(text, 80) + " | 清理后: " + abbreviate(cleanText, 80);
        }

        // 检测欢迎消息 - 用于IRC用户名自动检测
        if (detectState == IrcDetectState.WAIT_WELCOME) {
            if (cleanText.contains("[S]") && cleanText.contains("Welcome")) {
                lastIrcDebug = "检测到欢迎消息，准备发送测试消息";
                detectState = IrcDetectState.WAIT_ECHO;
                detectStartTime = System.currentTimeMillis();
                detectSentTimestamp = System.currentTimeMillis();

                // 发送测试消息（因为IRC里不止一个人，需要确认哪个是自己）
                if (client.player != null && client.player.networkHandler != null) {
                    client.player.networkHandler.sendChatMessage(".irc chat DETECT_" + detectSentTimestamp);
                    statusMessage = "检测到IRC连接，正在获取用户名...";
                    lastIrcDebug += " 已发送DETECT_" + detectSentTimestamp;
                } else {
                    lastIrcDebug += " 发送失败: player或networkHandler为null";
                }
                return;
            }
        }

        // 过滤其他欢迎消息（不在自动检测状态时）
        if (cleanText.contains("[S]") && cleanText.contains("Welcome")) {
            lastIrcDebug = "检测到欢迎消息（已跳过）";
            return;
        }

        // 解析在线用户列表 - 格式: [S] [IRC] online: user1, user2, user3, ...
        if (cleanText.contains("[S]") && cleanText.contains("[IRC]") && cleanText.contains("online")) {
            lastIrcDebug = "尝试解析在线用户列表...";
            // 匹配 "online:" 后的内容
            int onlineIndex = cleanText.indexOf("online");
            int colonIndex = cleanText.indexOf(":", onlineIndex);
            if (colonIndex > 0) {
                String afterColon = cleanText.substring(colonIndex + 1);
                lastIrcDebug += " 冒号后: [" + abbreviate(afterColon, 40) + "]";
                // 去除所有空格
                String userList = afterColon.replaceAll("\\s+", "");
                // 去除末尾的逗号
                if (userList.endsWith(",")) {
                    userList = userList.substring(0, userList.length() - 1);
                }

                onlineUsers.clear();
                if (!userList.isEmpty()) {
                    for (String user : userList.split(",")) {
                        if (!user.isEmpty() && !user.equalsIgnoreCase(config.localIrcName())) {
                            // 过滤掉自己
                            onlineUsers.add(user);
                        }
                    }
                }
                lastUserListCheck = System.currentTimeMillis();
                statusMessage = "✓ 在线用户列表刷新成功，当前在线 " + onlineUsers.size() + " 人";
                lastIrcDebug += " 解析成功: " + onlineUsers.size() + "人 [已更新statusMessage]";
                return;
            } else {
                lastIrcDebug += " 未找到冒号";
            }
        }

        // 自动检测IRC用户名 - 检测回显消息
        // 格式: [S] [IRC] username: DETECT_timestamp

        // 检测回显消息
        if (detectState == IrcDetectState.WAIT_ECHO && detectSentTimestamp > 0) {
            String detectMarker = "DETECT_" + detectSentTimestamp;
            if (cleanText.contains(detectMarker)) {
                lastIrcDebug = "检测到回显消息，尝试提取用户名...";
                // 解析用户名：在 [IRC] 和 : 之间
                int ircIndex = cleanText.indexOf("[IRC]");
                int colonIndex = cleanText.indexOf(":", ircIndex);
                if (ircIndex > 0 && colonIndex > ircIndex) {
                    // 提取 [IRC] 和 : 之间的文本
                    String between = cleanText.substring(ircIndex + 5, colonIndex).trim();
                    lastIrcDebug += " [IRC]和:之间: [" + between + "]";
                    // 可能的格式：
                    // "username" 或 "   username" 或其他前缀 + username
                    // 取最后一个连续的字母数字串
                    String[] parts = between.split("\\s+");
                    String detectedName = parts[parts.length - 1];
                    lastIrcDebug += " 提取用户名: [" + detectedName + "]";

                    if (!detectedName.isEmpty() && detectedName.matches("[A-Za-z0-9_\\-]+")) {
                        config.localIrcName(detectedName);
                        config.autoDetected(true);
                        config.save();
                        detectState = IrcDetectState.COMPLETED;
                        statusMessage = "自动检测到IRC用户名: " + detectedName;
                        lastIrcDebug += " 检测成功!";
                        return;
                    } else {
                        lastIrcDebug += " 用户名格式无效";
                    }
                } else {
                    lastIrcDebug += " 未找到[IRC]或冒号 ircIdx=" + ircIndex + " colonIdx=" + colonIndex;
                }
            }
        }

        // 检测用户不在线的消息 - 格式: [IRC] User 'xxx' is not online
        if (cleanText.contains("[IRC]") && cleanText.contains("is not online")) {
            lastIrcDebug = "检测到离线消息: " + abbreviate(cleanText, 60);
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("User '([^']+)' is not online");
            java.util.regex.Matcher m = p.matcher(cleanText);
            if (m.find()) {
                String offlineUser = m.group(1);
                lastIrcDebug += " 用户: " + offlineUser;

                // 清除该用户的所有待重传消息
                pendingAcks.entrySet().removeIf(entry ->
                    entry.getValue().message().to().equalsIgnoreCase(offlineUser)
                );

                // 如果是对局中的对手不在线，判定为平局
                if (status == MatchStatus.PLAYING && peer.equalsIgnoreCase(offlineUser)) {
                    status = MatchStatus.ENDED;
                    gameResult = "draw"; // 对手离线，平局
                    statusMessage = "对手 " + offlineUser + " 已离线，对局结束（平局）";
                    // 立即清空所有待重传消息
                    pendingAcks.clear();
                } else if (peer.equalsIgnoreCase(offlineUser)) {
                    // 其他状态（邀请中、等待中）
                    statusMessage = "用户 " + offlineUser + " 不在线，已停止重传";
                } else {
                    statusMessage = "用户 " + offlineUser + " 不在线";
                }
                return;
            } else {
                lastIrcDebug += " 正则匹配失败";
            }
        }

        // SSNG1协议包检测：只在包含 -> 的私聊消息中检测
        // 因为SSNG1包是通过 .irc chat $tell 命令发送的，格式为: [IRC] sender -> receiver: SSNG1...
        if (!cleanText.contains("->")) {
            // 不是私聊消息，不检测SSNG1
            return;
        }

        if (!cleanText.contains("SSNG1")) {
            // 是私聊消息但不包含SSNG1，忽略
            return;
        }

        lastPacketDebug = "看到 SSNG1: " + abbreviate(cleanText, 60);
        long now = System.currentTimeMillis();
        if (cleanText.equals(lastHandledChatText) && now - lastHandledChatAt < 500L) {
            return;
        }
        lastHandledChatText = cleanText;
        lastHandledChatAt = now;
        var inbound = IrcProtocol.scanChat(cleanText, config.senderRegex());
        if (inbound.isEmpty()) {
            lastPacketDebug = "看到 SSNG1 但解析失败";
            return;
        }
        lastPacketDebug = "解析到 " + inbound.get().message().type() + " from " + inbound.get().message().from() + " to " + inbound.get().message().to();
        handleMessage(inbound.get().sender(), inbound.get().message());
    }

    private void handleMessage(String sender, IrcMessage message) {
        if (message.v() != 1) {
            return;
        }
        if (!message.to().isBlank() && !message.to().equalsIgnoreCase(config.localIrcName())) {
            // 收到包但不是发给当前用户，静默忽略
            lastPacketDebug = "收到包但不是发给当前 IRC ID";
            return;
        }
        if (!sender.isBlank() && !message.from().equalsIgnoreCase(sender) && !message.from().isBlank()) {
            // Keep parsing tolerant: IRC display names can be decorated, payload identity remains canonical.
        }

        // 检查是否是重复消息（基于时间戳去重）
        if (message.ts() > 0 && receivedTimestamps.contains(message.ts())) {
            lastPacketDebug = "忽略重复消息 ts=" + message.ts();
            return;
        }

        // 处理ACK消息
        if (message.type().equals("ack")) {
            onAck(message);
            return;
        }

        // 记录已接收的时间戳
        if (message.ts() > 0) {
            receivedTimestamps.add(message.ts());
        }

        // 发送ACK确认（除了ack消息本身）
        if (message.ts() > 0 && !message.from().isBlank()) {
            sendAck(message.from(), message.ts());
        }

        switch (message.type()) {
            case "invite" -> onInvite(message);
            case "accept" -> onAccept(message);
            case "decline" -> onDecline(message);
            case "start" -> onStart(message);
            case "move" -> onMove(message);
            case "move_ack" -> onMoveAck(message);
            case "resign" -> onResign(message);
            case "sync_request" -> onSyncRequest(message);
            case "sync" -> onSync(message);
            default -> {
            }
        }
    }

    private void onInvite(IrcMessage message) {
        if (status == MatchStatus.PLAYING) {
            // 在对局中收到邀请，自动拒绝
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("nonceA", String.valueOf(message.payload().getOrDefault("nonceA", "")));
            payload.put("reason", "in_game");
            IrcMessage declineMsg = new IrcMessage(1, "decline", message.gameId(), config.localIrcName(), message.from(), 1, Instant.now().toEpochMilli(), payload);
            sendMessage(declineMsg);
            statusMessage = "收到 " + message.from() + " 的邀请，但当前正在对局，已自动拒绝";
            return;
        }
        Object boardSize = message.payload().get("boardSize");
        Object rules = message.payload().get("rules");
        if (!Objects.equals(String.valueOf(boardSize), String.valueOf(GomokuBoard.SIZE)) || !"freestyle".equals(String.valueOf(rules))) {
            statusMessage = "收到不支持的邀请";
            return;
        }
        resetMatch();
        status = MatchStatus.INVITED;
        gameId = message.gameId();
        inviter = message.from();
        peer = message.from();
        config.peerIrcName(peer);
        nonceA = String.valueOf(message.payload().getOrDefault("nonceA", ""));
        lastInboundSeq = message.seq();
        statusMessage = "收到 " + peer + " 的邀请";

        // 在玩家聊天栏显示显眼的邀请提示
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§e§l[五子棋] §r§a收到来自玩家 \"§b" + peer + "§a\" 的五子棋邀请！"), false);
        }
    }

    private void onAccept(IrcMessage message) {
        if (status != MatchStatus.INVITING || !message.gameId().equals(gameId) || !message.from().equalsIgnoreCase(peer)) {
            return;
        }
        if (message.seq() <= lastInboundSeq || !Objects.equals(message.payload().get("nonceA"), nonceA)) {
            // 静默忽略无效accept
            return;
        }
        nonceB = String.valueOf(message.payload().getOrDefault("nonceB", ""));
        lastInboundSeq = message.seq();
        IrcProtocol.ColorAssignment colors = IrcProtocol.assignColors(gameId, nonceA, nonceB, inviter, message.from());
        localStone = colors.black().equalsIgnoreCase(config.localIrcName()) ? Stone.BLACK : Stone.WHITE;
        startGame(colors.black(), colors.white());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nonceB", nonceB);
        payload.put("black", colors.black());
        payload.put("white", colors.white());
        payload.put("firstTurn", "black");
        payload.put("boardHash", board.hash());
        send("start", payload);
    }

    private void onDecline(IrcMessage message) {
        if (status == MatchStatus.INVITING && message.gameId().equals(gameId)) {
            resetMatch();
            pendingAcks.clear(); // 清空重传队列 - 邀请被拒绝，停止重传
            statusMessage = message.from() + " 拒绝了邀请";
        }
    }

    private void onStart(IrcMessage message) {
        if (status != MatchStatus.WAITING_START || !message.gameId().equals(gameId) || !message.from().equalsIgnoreCase(peer)) {
            return;
        }
        if (!Objects.equals(message.payload().get("nonceB"), nonceB)) {
            // 静默忽略无效start
            return;
        }
        String black = String.valueOf(message.payload().getOrDefault("black", ""));
        String white = String.valueOf(message.payload().getOrDefault("white", ""));
        localStone = black.equalsIgnoreCase(config.localIrcName()) ? Stone.BLACK : Stone.WHITE;
        startGame(black, white);
    }

    private void onMove(IrcMessage message) {
        if (status != MatchStatus.PLAYING || !message.gameId().equals(gameId) || !message.from().equalsIgnoreCase(peer)) {
            return;
        }
        if (message.seq() <= lastInboundSeq) {
            // 静默忽略重复落子
            return;
        }
        Stone stone = Stone.fromWireName(String.valueOf(message.payload().get("color")));
        if (stone == Stone.EMPTY || stone == localStone || stone != currentTurn) {
            // 静默忽略非法落子
            return;
        }
        int x = intValue(message.payload().get("x"));
        int y = intValue(message.payload().get("y"));
        GomokuBoard.MoveResult result = board.place(x, y, stone);
        if (!result.valid()) {
            // 静默忽略非法落子
            return;
        }
        lastInboundSeq = message.seq();
        if (result.win()) {
            status = MatchStatus.ENDED;
            gameResult = "lose"; // 对方赢了，你输了
            statusMessage = "你输了";
            // 立即清空所有待重传消息 - 游戏已结束
            pendingAcks.clear();
        } else {
            currentTurn = currentTurn.opposite();
            statusMessage = "轮到你落子";
        }
        send("move_ack", Map.of("moveNo", result.moveNo(), "boardHash", board.hash()));
    }

    private void onMoveAck(IrcMessage message) {
        if (!message.gameId().equals(gameId) || !message.from().equalsIgnoreCase(peer)) {
            return;
        }
        int moveNo = intValue(message.payload().get("moveNo"));
        if (moveNo == awaitingMoveAck) {
            awaitingMoveAck = -1;
            // 静默确认，不显示技术信息
        }
    }

    private void onResign(IrcMessage message) {
        if (status == MatchStatus.PLAYING && message.gameId().equals(gameId)) {
            status = MatchStatus.ENDED;
            gameResult = "win"; // 对方认输，你赢了
            statusMessage = "对方认输，你赢了";
            // 立即清空所有待重传消息 - 游戏已结束
            pendingAcks.clear();
        }
    }

    private void onSyncRequest(IrcMessage message) {
        if (!message.gameId().equals(gameId) || !message.from().equalsIgnoreCase(peer)) {
            return;
        }
        // 发送完整的棋盘状态
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("boardHash", board.hash());
        payload.put("moveNo", board.moveNo());
        payload.put("boardState", serializeBoardState());
        send("sync", payload);
        statusMessage = "已发送同步数据";
    }

    private void onSync(IrcMessage message) {
        if (!message.gameId().equals(gameId) || !message.from().equalsIgnoreCase(peer)) {
            return;
        }
        String receivedHash = String.valueOf(message.payload().getOrDefault("boardHash", ""));
        int receivedMoveNo = intValue(message.payload().get("moveNo"));
        String boardState = String.valueOf(message.payload().getOrDefault("boardState", ""));

        // 如果哈希匹配，说明已经同步
        if (receivedHash.equals(board.hash()) && receivedMoveNo == board.moveNo()) {
            statusMessage = "棋盘已同步";
            return;
        }

        // 恢复棋盘状态
        if (deserializeBoardState(boardState)) {
            statusMessage = "已同步棋盘";
        } else {
            statusMessage = "同步失败";
        }
    }

    private String serializeBoardState() {
        StringBuilder sb = new StringBuilder();
        sb.append(board.moveNo()).append(":");
        for (int y = 0; y < GomokuBoard.SIZE; y++) {
            for (int x = 0; x < GomokuBoard.SIZE; x++) {
                Stone stone = board.get(x, y);
                sb.append(switch (stone) {
                    case EMPTY -> '.';
                    case BLACK -> 'B';
                    case WHITE -> 'W';
                });
            }
        }
        return sb.toString();
    }

    private boolean deserializeBoardState(String state) {
        if (state == null || state.isBlank()) {
            return false;
        }
        try {
            String[] parts = state.split(":", 2);
            if (parts.length != 2) {
                return false;
            }
            int moveNo = Integer.parseInt(parts[0]);
            String boardData = parts[1];
            if (boardData.length() != GomokuBoard.SIZE * GomokuBoard.SIZE) {
                return false;
            }

            board.clear();
            int index = 0;
            for (int y = 0; y < GomokuBoard.SIZE; y++) {
                for (int x = 0; x < GomokuBoard.SIZE; x++) {
                    char c = boardData.charAt(index++);
                    Stone stone = switch (c) {
                        case '.' -> Stone.EMPTY;
                        case 'B' -> Stone.BLACK;
                        case 'W' -> Stone.WHITE;
                        default -> throw new IllegalArgumentException("Invalid stone: " + c);
                    };
                    if (stone != Stone.EMPTY) {
                        board.place(x, y, stone);
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void startGame(String black, String white) {
        board.clear();
        status = MatchStatus.PLAYING;
        currentTurn = Stone.BLACK;
        gameResult = ""; // 重置游戏结果
        stateDeadlineMs = 0;
        String colorText = localStone == Stone.BLACK ? "黑棋" : "白棋";
        String firstText = localStone == Stone.BLACK ? "你先手" : "对方先手";
        statusMessage = "对局开始，你是" + colorText + "，" + firstText;
    }

    private void send(String type, Map<String, Object> payload) {
        IrcMessage message = new IrcMessage(1, type, gameId, config.localIrcName(), peer, outgoingSeq++, Instant.now().toEpochMilli(), payload);
        sendMessage(message);
    }

    private void sendMessage(IrcMessage message) {
        String command = IrcProtocol.tellCommand(message.to(), message);
        lastSentCommand = command;
        lastSentDebug = "发送 " + message.type() + " -> " + message.to() + ": " + abbreviate(command, 90);

        if (client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendChatMessage(command);

            // 将消息加入待确认队列（除了ack消息本身）
            if (!message.type().equals("ack") && message.ts() > 0) {
                pendingAcks.put(message.ts(), new PendingMessage(message, command));
            }
        } else {
            statusMessage = "发送失败：不在服务器内或 networkHandler 为空";
            lastSentDebug = "发送失败：" + message.type();
        }
    }

    private void retransmit(PendingMessage pending) {
        if (client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendChatMessage(pending.command());
            pending.incrementRetry();
            lastSentDebug = "重传 " + pending.message().type() + " (第" + pending.retryCount() + "次)";
        }
    }

    private void onAck(IrcMessage message) {
        long ackTs = longValue(message.payload().getOrDefault("ts", 0L));
        if (ackTs > 0 && pendingAcks.containsKey(ackTs)) {
            PendingMessage pending = pendingAcks.remove(ackTs);
            lastPacketDebug = "收到ACK确认: " + pending.message().type() + " ts=" + ackTs;

            // 如果游戏已结束且这是最后一条待确认的消息，清空所有队列
            if (status == MatchStatus.ENDED && !gameResult.isEmpty()) {
                pendingAcks.clear();
                lastPacketDebug += " 游戏已结束，清空重传队列";
            }
        }
    }

    private void sendAck(String to, long ts) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ts", ts);
        IrcMessage ackMessage = new IrcMessage(1, "ack", gameId, config.localIrcName(), to, 0, 0, payload);

        String command = IrcProtocol.tellCommand(to, ackMessage);
        if (client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendChatMessage(command);
        }
    }

    private void handleIrcAutoDetect(long now) {
        // 已经完成检测或回退到手动模式，不需要处理
        if (detectState == IrcDetectState.COMPLETED || detectState == IrcDetectState.FALLBACK) {
            return;
        }

        // 检测到玩家进入世界，开始等待IRC欢迎消息
        if (detectState == IrcDetectState.NOT_STARTED && client.player != null && client.world != null) {
            // 如果配置文件已有localIrcName，不需要自动检测
            if (!config.localIrcName().isBlank()) {
                detectState = IrcDetectState.COMPLETED;
                return;
            }
            detectState = IrcDetectState.WAIT_WELCOME;
            detectStartTime = now;
            // 只在首次进入时设置状态消息
            if (statusMessage.equals("空闲")) {
                statusMessage = "等待IRC连接...";
            }
            return;
        }

        // 等待欢迎消息超时
        if (detectState == IrcDetectState.WAIT_WELCOME && now - detectStartTime > IRC_WELCOME_TIMEOUT) {
            detectState = IrcDetectState.FALLBACK;
            statusMessage = "未检测到IRC连接，请手动输入IRC用户名";
            return;
        }

        // 等待回显消息超时
        if (detectState == IrcDetectState.WAIT_ECHO && now - detectStartTime > IRC_ECHO_TIMEOUT) {
            detectState = IrcDetectState.FALLBACK;
            statusMessage = "IRC用户名自动检测失败，请手动输入";
            return;
        }
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void updateNames(String localIrcName, String peerIrcName) {
        config.localIrcName(localIrcName);
        config.peerIrcName(peerIrcName);
        config.save();
    }

    private void resetMatch() {
        board.clear();
        status = MatchStatus.IDLE;
        gameId = "";
        nonceA = "";
        nonceB = "";
        inviter = "";
        peer = config.peerIrcName();
        localStone = Stone.EMPTY;
        currentTurn = Stone.BLACK;
        outgoingSeq = 1;
        lastInboundSeq = 0;
        stateDeadlineMs = 0;
        pendingAcks.clear(); // 清空重传队列 - 重置对局状态
    }

    private boolean isScreenOpen() {
        return client.currentScreen != null && client.currentScreen.getClass().getName().equals("com.ssng.gomoku.gui.GomokuScreen");
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    public SsngConfig config() {
        return config;
    }

    public GomokuBoard board() {
        return board;
    }

    public MatchStatus status() {
        return status;
    }

    public String statusMessage() {
        return statusMessage;
    }

    public Stone localStone() {
        return localStone;
    }

    public Stone currentTurn() {
        return currentTurn;
    }

    public String peer() {
        return peer.isBlank() ? config.peerIrcName() : peer;
    }

    public String lastPacketDebug() {
        return lastPacketDebug;
    }

    public String lastSentDebug() {
        return lastSentDebug;
    }

    public String lastSentCommand() {
        return lastSentCommand;
    }

    public String lastIrcDebug() {
        return lastIrcDebug;
    }

    private static String abbreviate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    /**
     * 去除Minecraft格式化代码（§+字符）
     * 这样可以正确解析包含颜色代码的IRC消息
     */
    private static String stripFormattingCodes(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder clean = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // §是Minecraft的格式化标记，后面跟一个字符表示颜色或样式
            if (c == '§' && i + 1 < text.length()) {
                i++; // 跳过格式化代码
                continue;
            }
            clean.append(c);
        }
        return clean.toString();
    }

    private void requestOnlineUsers() {
        if (client.player != null && client.player.networkHandler != null) {
            client.player.networkHandler.sendChatMessage(".irc users");
        }
    }

    public Set<String> getOnlineUsers() {
        return new HashSet<>(onlineUsers);
    }

    public String gameResult() {
        return gameResult;
    }

    public boolean isUserOnline(String username) {
        return onlineUsers.contains(username);
    }
}
