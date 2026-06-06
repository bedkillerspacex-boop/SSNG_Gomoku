package com.ssng.gomoku.gui;

import com.ssng.gomoku.SsngGomokuClient;
import com.ssng.gomoku.client.GomokuClientController;
import com.ssng.gomoku.client.MatchStatus;
import com.ssng.gomoku.game.GomokuBoard;
import com.ssng.gomoku.game.Stone;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public final class GomokuScreen extends Screen {
    private static final Identifier BOARD_TEXTURE = Identifier.of(SsngGomokuClient.MOD_ID, "textures/gui/board.png");
    private static final int PANEL_WIDTH = 186;
    private static final int GRID_PADDING = 48;
    private static final int BOARD_TEXTURE_SIZE = 2048;

    private final GomokuClientController controller;
    private TextFieldWidget localName;
    private ButtonWidget peerNameButton;
    private ButtonWidget inviteButton;
    private ButtonWidget acceptButton;
    private ButtonWidget declineButton;
    private ButtonWidget resignOrClearButton;
    private ButtonWidget syncButton;
    private String selectedPeerName = "";
    private int boardX;
    private int boardY;
    private int boardSize;

    public GomokuScreen(GomokuClientController controller) {
        super(Text.literal("SSNG Gomoku"));
        this.controller = controller;
    }

    @Override
    protected void init() {
        int panelX = 16;
        int y = 44;

        // 判断游戏状态
        boolean isPlaying = controller.status() == MatchStatus.PLAYING;

        localName = new TextFieldWidget(textRenderer, panelX, y, PANEL_WIDTH, 20, Text.literal("你的 IRC ID"));
        localName.setText(controller.config().localIrcName());
        localName.setEditable(!isPlaying); // 游戏中不可编辑
        addDrawableChild(localName);
        y += 42;

        // 对方IRC名称 - 下拉按钮（从在线用户列表选择）
        selectedPeerName = controller.config().peerIrcName();
        if (selectedPeerName.isEmpty() && !controller.getOnlineUsers().isEmpty()) {
            selectedPeerName = controller.getOnlineUsers().iterator().next();
        }

        peerNameButton = ButtonWidget.builder(
            Text.literal(selectedPeerName.isEmpty() ? "选择对方..." : selectedPeerName),
            button -> cycleNextPeer()
        )
        .dimensions(panelX, y, PANEL_WIDTH, 20)
        .build();
        peerNameButton.active = !isPlaying; // 游戏中禁用
        addDrawableChild(peerNameButton);
        y += 42;

        // 删除收包正则输入框，改为内部硬编码
        // y += 32; 不再需要这个间距

        // 邀请和接受按钮 - 游戏进行中时禁用
        inviteButton = ButtonWidget.builder(Text.literal("邀请"), button -> controller.invite(localName.getText(), selectedPeerName))
            .dimensions(panelX, y, 88, 20)
            .build();
        inviteButton.active = !isPlaying;
        addDrawableChild(inviteButton);

        acceptButton = ButtonWidget.builder(Text.literal("接受"), button -> controller.acceptInvite())
            .dimensions(panelX + 98, y, 88, 20)
            .build();
        acceptButton.active = !isPlaying;
        addDrawableChild(acceptButton);
        y += 24;

        declineButton = ButtonWidget.builder(Text.literal("拒绝"), button -> controller.declineInvite())
            .dimensions(panelX, y, 88, 20)
            .build();
        declineButton.active = !isPlaying; // 游戏中禁用
        addDrawableChild(declineButton);

        // 认输/清空按钮 - 根据游戏状态切换
        String resignOrClearText = isPlaying ? "认输" : "清空棋盘";
        resignOrClearButton = ButtonWidget.builder(Text.literal(resignOrClearText), button -> {
            if (controller.status() == MatchStatus.PLAYING) {
                controller.resign();
            } else {
                controller.clearBoard();
            }
        })
        .dimensions(panelX + 98, y, 88, 20)
        .build();
        addDrawableChild(resignOrClearButton);
        y += 24;

        syncButton = ButtonWidget.builder(Text.literal("重新同步"), button -> controller.requestSync())
            .dimensions(panelX, y, PANEL_WIDTH, 20)
            .build();
        addDrawableChild(syncButton);
        y += 24;

        addDrawableChild(ButtonWidget.builder(Text.literal("刷新在线列表"), button -> {
            controller.refreshOnlineUsers();
            // 刷新后重新初始化GUI以更新下拉列表
            clearChildren();
            init();
        })
        .dimensions(panelX, y, PANEL_WIDTH, 20)
        .build());
        y += 24;

        // 游戏信息显示（移到刷新按钮下方）
        // 在drawHudText中绘制

        layoutBoard();
    }

    private void cycleNextPeer() {
        List<String> users = new ArrayList<>(controller.getOnlineUsers());
        if (users.isEmpty()) {
            selectedPeerName = "";
            peerNameButton.setMessage(Text.literal("无在线用户"));
            return;
        }

        // 循环到下一个用户
        int currentIndex = users.indexOf(selectedPeerName);
        int nextIndex = (currentIndex + 1) % users.size();
        selectedPeerName = users.get(nextIndex);
        peerNameButton.setMessage(Text.literal(selectedPeerName));

        // 保存到配置
        controller.config().peerIrcName(selectedPeerName);
        controller.config().save();
    }

    @Override
    public void tick() {
        super.tick();
        controller.config().localIrcName(localName.getText());
        controller.config().save();
        controller.tick();

        // 动态更新按钮和输入框状态
        boolean isPlaying = controller.status() == MatchStatus.PLAYING;
        boolean isInviting = controller.status() == MatchStatus.INVITING;

        // 计算邀请倒计时
        int remainingSeconds = 0;
        if (isInviting && controller.stateDeadlineMs() > 0) {
            long remaining = controller.stateDeadlineMs() - System.currentTimeMillis();
            remainingSeconds = Math.max(0, (int) Math.ceil(remaining / 1000.0));
        }

        // 更新邀请按钮文字和状态
        if (inviteButton != null) {
            if (isInviting) {
                inviteButton.setMessage(Text.literal("已邀请，等待" + remainingSeconds + "秒"));
                inviteButton.active = false;
            } else {
                inviteButton.setMessage(Text.literal("邀请"));
                inviteButton.active = !isPlaying;
            }
        }

        if (acceptButton != null) {
            acceptButton.active = !isPlaying;
        }
        if (declineButton != null) {
            declineButton.active = !isPlaying;
        }

        // 邀请期间禁用选人按钮
        if (peerNameButton != null) {
            peerNameButton.active = !isPlaying && !isInviting;
        }

        if (localName != null) {
            localName.setEditable(!isPlaying);
        }

        // 更新认输/清空按钮文字
        if (resignOrClearButton != null) {
            String text = isPlaying ? "认输" : "清空棋盘";
            resignOrClearButton.setMessage(Text.literal(text));
        }
    }

    @Override
    public void resize(net.minecraft.client.MinecraftClient client, int width, int height) {
        super.resize(client, width, height);
        layoutBoard();
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // 不渲染默认的模糊背景，我们自己绘制背景
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // 先绘制背景
        context.fill(0, 0, width, height, 0xFF101216);
        context.fill(8, 8, PANEL_WIDTH + 24, height - 8, 0xFF20242A);

        // 绘制widgets（按钮和文本框）- 先画避免被棋盘遮挡
        super.render(context, mouseX, mouseY, delta);

        // 绘制棋盘（覆盖在widgets上方）
        drawBoard(context);

        // 最后绘制文本信息（保证文本在最上层）
        drawHudText(context);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && clickBoard(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void close() {
        controller.config().save();
        super.close();
    }

    private boolean clickBoard(double mouseX, double mouseY) {
        if (controller.status() != MatchStatus.PLAYING) {
            return false;
        }
        int gridStartX = boardX + scaledPadding();
        int gridStartY = boardY + scaledPadding();
        int gridEndX = boardX + boardSize - scaledPadding();
        double cell = (gridEndX - gridStartX) / (double) (GomokuBoard.SIZE - 1);
        int x = (int) Math.round((mouseX - gridStartX) / cell);
        int y = (int) Math.round((mouseY - gridStartY) / cell);
        double px = gridStartX + x * cell;
        double py = gridStartY + y * cell;
        if (x < 0 || x >= GomokuBoard.SIZE || y < 0 || y >= GomokuBoard.SIZE) {
            return false;
        }
        if (Math.abs(mouseX - px) > cell * 0.45 || Math.abs(mouseY - py) > cell * 0.45) {
            return false;
        }
        controller.placeLocalStone(x, y);
        return true;
    }

    private void drawBoard(DrawContext context) {
        context.fill(boardX, boardY, boardX + boardSize, boardY + boardSize, 0xFFC58A45);
        context.fill(boardX + 6, boardY + 6, boardX + boardSize - 6, boardY + boardSize - 6, 0xFFD29A55);
        int gridStartX = boardX + scaledPadding();
        int gridStartY = boardY + scaledPadding();
        int gridEndX = boardX + boardSize - scaledPadding();
        int gridEndY = boardY + boardSize - scaledPadding();
        float cell = (gridEndX - gridStartX) / (float) (GomokuBoard.SIZE - 1);
        int stoneRadius = Math.max(5, Math.round(cell * 0.36f));

        for (int i = 0; i < GomokuBoard.SIZE; i++) {
            int p = Math.round(gridStartX + i * cell);
            int q = Math.round(gridStartY + i * cell);
            context.fill(gridStartX, q - 1, gridEndX + 1, q + 1, 0xFF3E2414);
            context.fill(p - 1, gridStartY, p + 1, gridEndY + 1, 0xFF3E2414);
        }

        int[] stars = {3, 7, 11};
        int starRadius = Math.max(3, Math.round(cell * 0.08f));
        for (int sx : stars) {
            for (int sy : stars) {
                int cx = Math.round(gridStartX + sx * cell);
                int cy = Math.round(gridStartY + sy * cell);
                context.fill(cx - starRadius, cy - starRadius, cx + starRadius + 1, cy + starRadius + 1, 0xFF1D130C);
            }
        }

        for (int y = 0; y < GomokuBoard.SIZE; y++) {
            for (int x = 0; x < GomokuBoard.SIZE; x++) {
                Stone stone = controller.board().get(x, y);
                if (stone == Stone.EMPTY) {
                    continue;
                }
                int cx = Math.round(gridStartX + x * cell);
                int cy = Math.round(gridStartY + y * cell);
                int color = stone == Stone.BLACK ? 0xFF111111 : 0xFFF4F1E8;
                int outline = stone == Stone.BLACK ? 0xFF4D4D4D : 0xFFB8B0A0;
                context.fill(cx - stoneRadius - 1, cy - stoneRadius - 1, cx + stoneRadius + 2, cy + stoneRadius + 2, outline);
                context.fill(cx - stoneRadius, cy - stoneRadius, cx + stoneRadius + 1, cy + stoneRadius + 1, color);
            }
        }

        // 在棋盘上显示输赢结果
        String gameResult = controller.gameResult();
        if (!gameResult.isEmpty()) {
            String resultText;
            int textColor;
            switch (gameResult) {
                case "win" -> {
                    resultText = "你赢了！";
                    textColor = 0xFF00FF00; // 绿色
                }
                case "lose" -> {
                    resultText = "你输了！";
                    textColor = 0xFFFF0000; // 红色
                }
                case "draw" -> {
                    resultText = "平局";
                    textColor = 0xFFFFFF00; // 黄色
                }
                default -> {
                    resultText = "";
                    textColor = 0xFFFFFFFF;
                }
            }

            if (!resultText.isEmpty()) {
                // 计算棋盘中心位置
                int centerX = boardX + boardSize / 2;
                int centerY = boardY + boardSize / 2;

                // 使用大字体渲染（通过缩放）
                var matrices = context.getMatrices();
                matrices.push();
                matrices.translate(centerX, centerY, 0);
                matrices.scale(4.0f, 4.0f, 1.0f); // 放大4倍

                // 计算文本宽度以居中
                int textWidth = textRenderer.getWidth(resultText);
                int textX = -textWidth / 2;
                int textY = -4; // 半个字符高度

                // 绘制黑色背景增强可读性
                context.fill(textX - 4, textY - 2, textX + textWidth + 4, textY + 10, 0xAA000000);

                // 绘制文字（带阴影）
                context.drawTextWithShadow(textRenderer, resultText, textX, textY, textColor);

                matrices.pop();
            }
        }
    }

    private void drawHudText(DrawContext context) {
        context.drawTextWithShadow(textRenderer, "SSNG 五子棋", 16, 14, 0xFFFFFFFF);

        // 游戏状态提示 - 显眼位置
        if (controller.status() == MatchStatus.PLAYING) {
            context.drawTextWithShadow(textRenderer, ">>> 游戏进行中 <<<", 16, 28, 0xFFFF5555);
        }

        context.drawTextWithShadow(textRenderer, "你", 16, 32, 0xFFE8EEF7);
        context.drawTextWithShadow(textRenderer, "选择你的对手", 16, 74, 0xFFE8EEF7);

        // 简化的状态显示 - 只显示用户友好的信息
        int infoY = 264; // 刷新在线列表按钮下方
        context.drawTextWithShadow(textRenderer, "状态: " + controller.statusMessage(), 16, infoY, 0xFFFFE6A8);

        // 游戏信息（你的颜色、回合、对手） - 放大字体
        String colorText = stoneText(controller.localStone());
        String turnText = stoneText(controller.currentTurn());
        String peerText = controller.peer().isEmpty() ? "无" : controller.peer();

        var matrices = context.getMatrices();
        matrices.push();
        matrices.translate(16, infoY + 20, 0);
        matrices.scale(1.5f, 1.5f, 1.0f); // 放大1.5倍

        context.drawTextWithShadow(textRenderer, "你: " + colorText, 0, 0, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer, "回合: " + turnText, 0, 12, 0xFFFFFFFF);
        context.drawTextWithShadow(textRenderer, "对手: " + peerText, 0, 24, 0xFFFFFFFF);

        matrices.pop();
    }

    private void layoutBoard() {
        int availableW = Math.max(180, width - PANEL_WIDTH - 48);
        int availableH = Math.max(180, height - 32);
        boardSize = Math.min(availableW, availableH);
        boardX = PANEL_WIDTH + 32 + (availableW - boardSize) / 2;
        boardY = 16 + (availableH - boardSize) / 2;
    }

    private int scaledPadding() {
        return Math.max(18, Math.round(boardSize * (GRID_PADDING / 2048.0f)));
    }

    private static String stoneText(Stone stone) {
        return switch (stone) {
            case BLACK -> "黑棋";
            case WHITE -> "白棋";
            case EMPTY -> "未分配";
        };
    }
}
