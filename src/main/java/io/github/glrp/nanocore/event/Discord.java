package io.github.glrp.nanocore.event;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Posts event news to a Discord channel through a webhook. Off when no URL is set. Messages go out one at a time
 * on their own thread, a little apart, so Discord's rate limit is never hit. The URL is a secret: config only.
 */
public final class Discord {

    public static final int GOLD = 0xF1C40F, GREEN = 0x2ECC71, BLUE = 0x3498DB, PURPLE = 0x9B59B6, GRAY = 0x95A5A6;

    private final Logger log;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService queue = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NanoCore-Discord");
        t.setDaemon(true);
        return t;
    });
    private volatile String url = "";
    private volatile String username = "Server events";
    private volatile long lastWarning;

    public Discord(Logger log) {
        this.log = log;
    }

    public void configure(String url, String username) {
        this.url = url == null ? "" : url.trim();
        this.username = username == null || username.isBlank() ? "Server events" : username;
    }

    public boolean enabled() {
        return url.startsWith("https://");
    }

    public void send(String title, String description, int color, List<DiscordPayload.Field> fields) {
        if (!enabled()) return;
        String target = url;
        String body = DiscordPayload.json(username, title, description, color, fields);
        queue.execute(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
                if (res.statusCode() / 100 != 2) warn("Discord answered " + res.statusCode());
                Thread.sleep(1100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                warn("Discord message failed: " + e.getMessage());
            }
        });
    }

    private void warn(String text) {
        long now = System.currentTimeMillis();
        if (now - lastWarning < 60_000) return;
        lastWarning = now;
        log.warning(text);
    }

    public void close() {
        queue.shutdown();
    }
}
