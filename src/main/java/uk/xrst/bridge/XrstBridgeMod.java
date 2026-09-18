package uk.xrst.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.ChatType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 仅服务端：
 *  - 游戏 -> 网页：监听玩家聊天，异步 POST 到 xrst.uk Worker（/api/mc/ingest）
 *  - 网页 -> 游戏：定时 GET /api/mc/poll 拉取网页新消息，在游戏内广播
 *    （面板服没有公网 RCON 端口，所以由 mod 主动轮询，走标准 HTTPS）
 */
public class XrstBridgeMod implements DedicatedServerModInitializer {

    // 开源版本：默认不指向任何服务器，使用者必须在配置文件里填写自己的网址，
    // 避免误把聊天发到别人的站点。
    private static final String DEFAULT_WEBHOOK = "";
    private static final String DEFAULT_POLL = "";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private String webhookUrl = DEFAULT_WEBHOOK;
    private String pollUrl = DEFAULT_POLL;
    private String secret = "";
    private boolean enabled = true;
    private boolean pollEnabled = true;
    private int pollInterval = 4;

    private MinecraftServer server;
    private ScheduledExecutorService scheduler;
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private volatile long lastTs = System.currentTimeMillis();

    @Override
    public void onInitializeServer() {
        loadConfig();
        if (!enabled) {
            System.out.println("[XRST-Bridge] 已在 config/xrst-bridge.properties 中禁用（enabled=false），不转发聊天。");
            return;
        }
        if (secret == null || secret.isBlank() || "CHANGE_ME".equals(secret)) {
            System.out.println("[XRST-Bridge] 未配置 secret：请编辑 config/xrst-bridge.properties，"
                    + "填入与网站后端一致的密钥后重启。本次不启动任何转发。");
            return;
        }

        boolean webhookReady = !webhookUrl.isBlank();
        boolean pollReady = pollEnabled && !pollUrl.isBlank();
        if (!webhookReady && !pollReady) {
            System.out.println("[XRST-Bridge] webhook-url 与 poll-url 均为空：请在 "
                    + "config/xrst-bridge.properties 填写你自己的网站接口地址后重启。本次不启动任何转发。");
            return;
        }

        // 游戏 -> 网页：返回 true 放行（不拦截、不修改聊天）
        if (webhookReady) {
            ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(this::onChatMessage);
            System.out.println("[XRST-Bridge] 游戏 -> 网页 已启动：" + webhookUrl);
        } else {
            System.out.println("[XRST-Bridge] webhook-url 为空，游戏 -> 网页 方向不启动。");
        }

        if (pollReady) {
            ServerLifecycleEvents.SERVER_STARTED.register(s -> {
                this.server = s;
                startPolling();
                System.out.println("[XRST-Bridge] 网页 -> 游戏 已启动，每 " + pollInterval + " 秒拉取一次：" + pollUrl);
            });
            ServerLifecycleEvents.SERVER_STOPPING.register(s -> stopPolling());
        } else {
            System.out.println("[XRST-Bridge] poll-url 为空或已禁用，网页 -> 游戏 方向不启动。");
        }
    }

    /* ================= 游戏 -> 网页 ================= */

    private boolean onChatMessage(PlayerChatMessage message, ServerPlayer sender, ChatType.Bound params) {
        try {
            String name = sender.getName().getString();
            String text = message.signedContent();
            if (text != null && !text.isBlank()) {
                postChat(name, text);
            }
        } catch (Throwable t) {
            // 任何异常都不能影响游戏内聊天
            System.out.println("[XRST-Bridge] 转发失败: " + t);
        }
        return true;
    }

    private void postChat(String username, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("text", text);

        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (secret != null && !secret.isBlank()) {
            b.header("X-MC-Secret", secret);
        }
        http.sendAsync(b.build(), HttpResponse.BodyHandlers.discarding())
                .thenAccept(resp -> {
                    if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                        System.out.println("[XRST-Bridge] 密钥被拒绝（" + resp.statusCode()
                                + "），请检查 config/xrst-bridge.properties 的 secret");
                    }
                })
                .exceptionally(t -> null); // 网络抖动静默丢弃，下一条再说
    }

    /* ================= 网页 -> 游戏（轮询）================= */

    private void startPolling() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "xrst-bridge-poll");
            t.setDaemon(true);
            return t;
        });
        int interval = Math.max(2, pollInterval);
        scheduler.scheduleAtFixedRate(this::pollOnce, 2, interval, TimeUnit.SECONDS);
    }

    private void stopPolling() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void pollOnce() {
        // 上一次还没回来就跳过，避免请求堆积
        if (!polling.compareAndSet(false, true)) return;
        String url = pollUrl + (pollUrl.contains("?") ? "&" : "?") + "after=" + lastTs;
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (secret != null && !secret.isBlank()) {
            b.header("X-MC-Secret", secret);
        }
        http.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> {
                    polling.set(false);
                    if (err != null) return; // 网络抖动静默，下个周期重试
                    handlePollResponse(resp);
                });
    }

    private void handlePollResponse(HttpResponse<String> resp) {
        if (resp.statusCode() == 401 || resp.statusCode() == 403) {
            System.out.println("[XRST-Bridge] 轮询密钥被拒绝（" + resp.statusCode()
                    + "），请检查 config/xrst-bridge.properties 的 secret");
            return;
        }
        if (resp.statusCode() / 100 != 2) return;
        try {
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            if (!root.has("messages")) return;
            JsonArray arr = root.getAsJsonArray("messages");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject m = el.getAsJsonObject();
                long ts = m.has("ts") ? m.get("ts").getAsLong() : 0;
                if (ts <= lastTs) continue;
                String username = m.has("username") ? m.get("username").getAsString() : "?";
                String text = m.has("text") ? m.get("text").getAsString() : "";
                lastTs = ts;
                broadcastInGame(username, text);
            }
            if (root.has("latest")) {
                long latest = root.get("latest").getAsLong();
                if (latest > lastTs) lastTs = latest;
            }
        } catch (Throwable t) {
            System.out.println("[XRST-Bridge] 轮询响应解析失败: " + t);
        }
    }

    private void broadcastInGame(String username, String text) {
        MinecraftServer s = this.server;
        if (s == null) return;
        MutableComponent line = Component.empty()
                .append(Component.literal("[网页] ").setStyle(Style.EMPTY.withColor(0x55FFFF)))
                .append(Component.literal(username).setStyle(Style.EMPTY.withColor(0xFFFF55)))
                .append(Component.literal(": ").setStyle(Style.EMPTY.withColor(0xAAAAAA)))
                .append(Component.literal(text).setStyle(Style.EMPTY.withColor(0xFFFFFF)));
        // 必须在服务器主线程发包
        s.execute(() -> {
            for (ServerPlayer p : s.getPlayerList().getPlayers()) {
                p.sendSystemMessage(line);
            }
        });
    }

    /* ================= 配置 ================= */

    private void loadConfig() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path file = configDir.resolve("xrst-bridge.properties");
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException e) {
                System.out.println("[XRST-Bridge] 读取配置失败，使用默认值: " + e);
            }
        }
        enabled = Boolean.parseBoolean(p.getProperty("enabled", "true"));
        webhookUrl = p.getProperty("webhook-url", DEFAULT_WEBHOOK);
        pollUrl = p.getProperty("poll-url", DEFAULT_POLL);
        pollEnabled = Boolean.parseBoolean(p.getProperty("poll-enabled", "true"));
        int interval = 4;
        try {
            interval = Integer.parseInt(p.getProperty("poll-interval", "4").trim());
        } catch (NumberFormatException ignored) {
            // 配置不是数字就用默认值
        }
        pollInterval = Math.max(2, interval);
        secret = p.getProperty("secret", "");

        if (!Files.exists(file)) {
            // 默认全部留空：必须填入使用者自己的网站地址与密钥，mod 才会启动。
            p.setProperty("enabled", "true");
            p.setProperty("webhook-url", "");
            p.setProperty("poll-url", "");
            p.setProperty("poll-enabled", "true");
            p.setProperty("poll-interval", "4");
            p.setProperty("secret", "CHANGE_ME");
            try {
                Files.createDirectories(configDir);
                try (OutputStream out = Files.newOutputStream(file)) {
                    p.store(out,
                            "XRST web chat bridge configuration.\n"
                            + "REQUIRED: set webhook-url / poll-url to YOUR OWN website endpoints,\n"
                            + "and set secret to the same shared key used by the website backend.\n"
                            + "Leave a URL empty to disable that direction.");
                }
                System.out.println("[XRST-Bridge] 已生成配置文件 config/xrst-bridge.properties，"
                        + "请填写 webhook-url / poll-url / secret 后重启服务器。");
            } catch (IOException e) {
                System.out.println("[XRST-Bridge] 生成配置文件失败: " + e);
            }
        }
    }
}
