package uk.xynex.ducksapi;

import com.sun.management.OperatingSystemMXBean;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class DucksApiPlugin extends JavaPlugin implements Listener {
    private static final String CONFIG_API_PORT_PATH = "api.port";
    private static final String STAFF_PERMISSION = "ducksapi.staff";
    private static final String ADMIN_PERMISSION = "ducksapi.admin";
    private static final int MAX_EVENTS = 10;
    private static final String WEBHOOK_ENABLED_PATH = "webhooks.enabled";
    private static final String WEBHOOK_URL_PATH = "webhooks.url";
    private static final String CONFIG_VERSION_PATH = "config-version";
    private static final int CONFIG_VERSION = 2;

    private static final double BYTES_PER_MEGABYTE = 1024.0d * 1024.0d;
    private static final double BYTES_PER_GIGABYTE = 1024.0d * 1024.0d * 1024.0d;
    private static final long WEBHOOK_LOG_THROTTLE_MILLIS = 60_000L;

    private HttpServer httpServer;
    private final AtomicReference<String> serverStatus = new AtomicReference<>("offline");
    private final Deque<StatusEvent> recentEvents = new ArrayDeque<>();
    private long startTimeMillis;
    private volatile int apiPort = 8080;
    private volatile boolean webhooksEnabled = false;
    private volatile String webhookUrl = "";
    private final AtomicLong lastWebhookFailureLogMillis = new AtomicLong(0L);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfigIfNeeded("startup");
        loadRuntimeSettings();

        serverStatus.set("online");
        startTimeMillis = System.currentTimeMillis();
        getServer().getPluginManager().registerEvents(this, this);

        if (!startHttpServer(apiPort)) {
            serverStatus.set("offline");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        sendWebhook("ONLINE", null);
    }

    @Override
    public void onDisable() {
        stopHttpServer();
        serverStatus.set("offline");
        sendWebhook("OFFLINE", null);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!"ducksapi".equalsIgnoreCase(command.getName())) {
            return false;
        }

        if (args.length == 1 && "reload".equalsIgnoreCase(args[0])) {
            if (!sender.hasPermission(ADMIN_PERMISSION)) {
                sender.sendMessage("§cYou do not have permission to run this command.");
                return true;
            }
            return handleReloadCommand(sender);
        }

        sender.sendMessage("§eUsage: /ducksapi reload");
        return true;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        final String playerName = event.getPlayer().getName();
        addEvent("join", playerName);
        sendWebhook("JOIN", playerName);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        final String playerName = event.getPlayer().getName();
        addEvent("leave", playerName);
        sendWebhook("LEAVE", playerName);
    }

    private boolean handleReloadCommand(CommandSender sender) {
        try {
            reloadConfig();
            migrateConfigIfNeeded("reload");
            final int previousPort = apiPort;
            loadRuntimeSettings();

            final boolean portChanged = previousPort != apiPort;
            if (portChanged) {
                stopHttpServer();
                if (!startHttpServer(apiPort)) {
                    sender.sendMessage("§cDucksAPI reload failed: could not bind API port " + apiPort + ".");
                    getLogger().severe("DucksAPI reload failed while rebinding to port " + apiPort + ".");
                    return true;
                }
            }

            sender.sendMessage("§aDucksAPI configuration reloaded successfully."
                    + (portChanged ? " HTTP listener restarted on port " + apiPort + "." : ""));
            return true;
        } catch (Exception exception) {
            getLogger().severe("Unexpected error while reloading DucksAPI config: " + exception.getMessage());
            sender.sendMessage("§cDucksAPI reload failed. Check console for details.");
            return true;
        }
    }

    private void migrateConfigIfNeeded(String source) {
        final int existingVersion = getConfig().getInt(CONFIG_VERSION_PATH, 1);
        final Map<String, Object> defaultsAdded = new LinkedHashMap<>();

        if (!getConfig().isSet(CONFIG_VERSION_PATH)) {
            defaultsAdded.put(CONFIG_VERSION_PATH, CONFIG_VERSION);
        }
        if (!getConfig().isSet(CONFIG_API_PORT_PATH)) {
            defaultsAdded.put(CONFIG_API_PORT_PATH, 8080);
        }
        if (!getConfig().isSet(WEBHOOK_ENABLED_PATH)) {
            defaultsAdded.put(WEBHOOK_ENABLED_PATH, false);
        }
        if (!getConfig().isSet(WEBHOOK_URL_PATH)) {
            defaultsAdded.put(WEBHOOK_URL_PATH, "");
        }

        for (Map.Entry<String, Object> entry : defaultsAdded.entrySet()) {
            getConfig().set(entry.getKey(), entry.getValue());
        }

        if (!defaultsAdded.isEmpty() || existingVersion < CONFIG_VERSION) {
            getConfig().set(CONFIG_VERSION_PATH, CONFIG_VERSION);
            saveConfig();
            getLogger().info("Config migration (" + source + "): v" + existingVersion
                    + " -> v" + CONFIG_VERSION + ", added " + defaultsAdded.size() + " missing keys.");
        }
    }

    private void loadRuntimeSettings() {
        apiPort = getConfig().getInt(CONFIG_API_PORT_PATH, 8080);
        webhooksEnabled = getConfig().getBoolean(WEBHOOK_ENABLED_PATH, false);

        String configuredWebhook = getConfig().getString(WEBHOOK_URL_PATH, "");
        if (configuredWebhook == null) {
            configuredWebhook = "";
        }
        webhookUrl = configuredWebhook.trim();
    }

    private boolean startHttpServer(int port) {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            httpServer.createContext("/status", new StatusHandler());
            httpServer.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ducks-api-http");
                thread.setDaemon(true);
                return thread;
            }));
            httpServer.start();
            getLogger().info("Duck's API is listening on port " + port + " at /status");
            return true;
        } catch (IOException ex) {
            getLogger().severe("Failed to start Duck's API HTTP server on port " + port + ": " + ex.getMessage());
            return false;
        }
    }

    private void stopHttpServer() {
        if (httpServer != null) {
            serverStatus.set("restarting");
            httpServer.stop(1);
            httpServer = null;
        }
    }

    private void addEvent(String type, String player) {
        synchronized (recentEvents) {
            recentEvents.addLast(new StatusEvent(type, player, System.currentTimeMillis() / 1000L));
            while (recentEvents.size() > MAX_EVENTS) {
                recentEvents.removeFirst();
            }
        }
    }

    private void sendWebhook(String event, String player) {
        if (!webhooksEnabled) {
            return;
        }
        if (webhookUrl.isBlank()) {
            logWebhookFailure("Webhook skipped: webhooks.enabled=true but webhooks.url is blank.");
            return;
        }

        final URL endpoint;
        try {
            endpoint = URI.create(webhookUrl).toURL();
        } catch (IllegalArgumentException | MalformedURLException exception) {
            logWebhookFailure("Webhook skipped: malformed URL '" + webhookUrl + "' (" + exception.getMessage() + ").");
            return;
        }

        final String payload = "{" +
                "\"event\":\"" + jsonEscape(event) + "\"," +
                "\"timestamp\":" + System.currentTimeMillis() + "," +
                "\"player\":" + (player == null ? "null" : "\"" + jsonEscape(player) + "\"") +
                "}";

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) endpoint.openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setRequestProperty("Accept", "application/json");
                connection.setDoOutput(true);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                try (OutputStream outputStream = connection.getOutputStream()) {
                    outputStream.write(payload.getBytes(StandardCharsets.UTF_8));
                }

                final int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    logWebhookFailure("Webhook request returned HTTP " + responseCode + " for event " + event + ".");
                }
            } catch (java.net.SocketTimeoutException timeoutException) {
                logWebhookFailure("Webhook timeout for event " + event + ": " + timeoutException.getMessage());
            } catch (javax.net.ssl.SSLException sslException) {
                logWebhookFailure("Webhook TLS failure for event " + event + ": " + sslException.getMessage());
            } catch (IOException ioException) {
                logWebhookFailure("Webhook connection failure for event " + event + ": " + ioException.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    private void logWebhookFailure(String message) {
        final long now = System.currentTimeMillis();
        final long last = lastWebhookFailureLogMillis.get();
        if (now - last >= WEBHOOK_LOG_THROTTLE_MILLIS && lastWebhookFailureLogMillis.compareAndSet(last, now)) {
            getLogger().warning(message);
        }
    }

    private double roundToTwoDecimals(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private String formatTwoDecimals(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private String formatUptime(long hours, long minutes, long seconds) {
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    private String jsonEscape(String input) {
        if (input == null) {
            return "";
        }

        final StringBuilder escaped = new StringBuilder(input.length());
        for (int index = 0; index < input.length(); index++) {
            final char character = input.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.US, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private final class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            final Permission permissionService = getPermissionService();
            final String permissionPlugin = permissionService == null ? "none" : permissionService.getName();
            final StringBuilder playersListJson = new StringBuilder();
            final StringBuilder staffListJson = new StringBuilder();
            int onlinePlayers = 0;
            int staffOnline = 0;
            boolean firstPlayer = true;
            boolean firstStaff = true;
            for (Player player : Bukkit.getOnlinePlayers()) {
                onlinePlayers++;
                if (!firstPlayer) {
                    playersListJson.append(',');
                }
                firstPlayer = false;
                playersListJson.append("{\"name\":\"").append(jsonEscape(player.getName())).append("\",")
                        .append("\"uuid\":\"").append(player.getUniqueId()).append("\"}");
                final boolean hasPermission = permissionService == null
                        ? player.hasPermission(STAFF_PERMISSION)
                        : permissionService.playerHas(player, STAFF_PERMISSION);
                if (hasPermission) {
                    staffOnline++;
                    if (!firstStaff) {
                        staffListJson.append(',');
                    }
                    firstStaff = false;
                    staffListJson.append("{\"name\":\"").append(jsonEscape(player.getName())).append("\",")
                            .append("\"rank\":\"")
                            .append(jsonEscape(resolveStaffRank(permissionService, player)))
                            .append("\"}");
                }
            }
            final StringBuilder worldsJson = new StringBuilder();
            boolean firstWorld = true;
            for (World world : Bukkit.getWorlds()) {
                if (!firstWorld) {
                    worldsJson.append(',');
                }
                firstWorld = false;
                worldsJson.append("{\"name\":\"").append(jsonEscape(world.getName())).append("\",")
                        .append("\"players\":").append(world.getPlayers().size()).append('}');
            }
            final StringBuilder eventsJson = new StringBuilder();
            synchronized (recentEvents) {
                boolean firstEvent = true;
                for (StatusEvent statusEvent : recentEvents) {
                    if (!firstEvent) {
                        eventsJson.append(',');
                    }
                    firstEvent = false;
                    eventsJson.append("{\"type\":\"").append(statusEvent.type()).append("\",")
                            .append("\"player\":\"").append(jsonEscape(statusEvent.player())).append("\",")
                            .append("\"timestamp\":").append(statusEvent.timestamp()).append('}');
                }
            }
            final int maxPlayers = Bukkit.getMaxPlayers();
            final long uptimeSeconds = Math.max(0L, (System.currentTimeMillis() - startTimeMillis) / 1000L);
            final long uptimeHours = uptimeSeconds / 3600L;
            final long uptimeMinutes = (uptimeSeconds % 3600L) / 60L;
            final long uptimeRemainingSeconds = uptimeSeconds % 60L;
            final double tps = roundToTwoDecimals(Bukkit.getServer().getTPS()[0]);
            final double mspt = roundToTwoDecimals(Bukkit.getServer().getAverageTickTime());
            final Instant timestamp = Instant.now();
            final Runtime runtime = Runtime.getRuntime();
            final long usedMemoryBytes = runtime.totalMemory() - runtime.freeMemory();
            final long maxMemoryBytes = runtime.maxMemory();
            final long freeMemoryBytes = Math.max(0L, maxMemoryBytes - usedMemoryBytes);
            final long ramUsedMb = Math.round(usedMemoryBytes / BYTES_PER_MEGABYTE);
            final long ramMaxMb = Math.round(maxMemoryBytes / BYTES_PER_MEGABYTE);
            final long ramFreeMb = Math.round(freeMemoryBytes / BYTES_PER_MEGABYTE);
            final double ramUsagePercent = maxMemoryBytes > 0L ? roundToTwoDecimals((usedMemoryBytes * 100.0d) / maxMemoryBytes) : 0.0d;
            double processCpuUsagePercent = 0.0d;
            final java.lang.management.OperatingSystemMXBean operatingSystemBean = ManagementFactory.getOperatingSystemMXBean();
            if (operatingSystemBean instanceof OperatingSystemMXBean osBean) {
                final double processCpuLoad = osBean.getProcessCpuLoad();
                if (processCpuLoad >= 0.0d) {
                    processCpuUsagePercent = roundToTwoDecimals(processCpuLoad * 100.0d);
                }
            }
            long diskTotalBytes = 0L;
            long diskUsableBytes = 0L;
            long diskUsedBytes = 0L;
            try {
                final Path storagePath = getDataFolder().toPath();
                final FileStore fileStore = java.nio.file.Files.getFileStore(storagePath);
                diskTotalBytes = fileStore.getTotalSpace();
                diskUsableBytes = fileStore.getUsableSpace();
                diskUsedBytes = Math.max(0L, diskTotalBytes - diskUsableBytes);
            } catch (IOException ignored) {
            }
            final double diskTotalGb = roundToTwoDecimals(diskTotalBytes / BYTES_PER_GIGABYTE);
            final double diskFreeGb = roundToTwoDecimals(diskUsableBytes / BYTES_PER_GIGABYTE);
            final double diskUsedGb = roundToTwoDecimals(diskUsedBytes / BYTES_PER_GIGABYTE);
            final double diskUsagePercent = diskTotalBytes > 0L ? roundToTwoDecimals((diskUsedBytes * 100.0d) / diskTotalBytes) : 0.0d;

            final String json = "{" +
                    "\"status\":\"" + serverStatus.get() + "\"," +
                    "\"timestamp\":{\"unix\":" + timestamp.getEpochSecond() + ",\"iso8601\":\"" + timestamp + "\"}," +
                    "\"server\":{\"name\":\"Xynex\",\"motd\":\"" + jsonEscape(Bukkit.getMotd()) + "\"," +
                    "\"loader\":{\"name\":\"" + jsonEscape(Bukkit.getName()) + "\",\"version\":\"" + jsonEscape(Bukkit.getVersion()) + "\"}," +
                    "\"minecraft\":{\"version\":\"" + jsonEscape(Bukkit.getMinecraftVersion()) + "\",\"bukkitVersion\":\"" + jsonEscape(Bukkit.getBukkitVersion()) + "\"}," +
                    "\"uptime\":{\"seconds\":" + uptimeSeconds + ",\"hours\":" + uptimeHours + ",\"minutes\":" + uptimeMinutes + ",\"remainingSeconds\":" + uptimeRemainingSeconds + ",\"formatted\":\"" + formatUptime(uptimeHours, uptimeMinutes, uptimeRemainingSeconds) + "\"}}," +
                    "\"players\":{\"online\":" + onlinePlayers + ",\"max\":" + maxPlayers + ",\"list\":[" + playersListJson + "]}," +
                    "\"staff\":{\"online\":" + staffOnline + ",\"permission\":\"" + STAFF_PERMISSION + "\",\"provider\":\"" + jsonEscape(permissionPlugin) + "\",\"list\":[" + staffListJson + "]}," +
                    "\"worlds\":[" + worldsJson + "]," +
                    "\"performance\":{\"tps\":" + formatTwoDecimals(tps) + ",\"mspt\":" + formatTwoDecimals(mspt) + "}," +
                    "\"system\":{\"ram\":{\"used_mb\":" + ramUsedMb + ",\"max_mb\":" + ramMaxMb + ",\"free_mb\":" + ramFreeMb + ",\"usage_percent\":" + formatTwoDecimals(ramUsagePercent) + "}," +
                    "\"cpu\":{\"process_usage_percent\":" + formatTwoDecimals(processCpuUsagePercent) + "}," +
                    "\"disk\":{\"used_gb\":" + formatTwoDecimals(diskUsedGb) + ",\"free_gb\":" + formatTwoDecimals(diskFreeGb) + ",\"total_gb\":" + formatTwoDecimals(diskTotalGb) + ",\"usage_percent\":" + formatTwoDecimals(diskUsagePercent) + "}}," +
                    "\"events\":[" + eventsJson + "]}";
            final byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(payload);
            }
        }

        private Permission getPermissionService() {
            final RegisteredServiceProvider<Permission> registration = Bukkit.getServicesManager().getRegistration(Permission.class);
            return registration == null ? null : registration.getProvider();
        }

        private String resolveStaffRank(Permission permissionService, Player player) {
            if (permissionService == null) {
                return "staff";
            }
            String rank = permissionService.getPrimaryGroup(player);
            if (rank == null || rank.isBlank()) {
                return "staff";
            }
            return rank;
        }
    }

    private record StatusEvent(String type, String player, long timestamp) {
    }
}
