package uk.xynex.ducksapi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public final class DucksApiPlugin extends JavaPlugin implements Listener {
    private static final String CONFIG_API_PORT_PATH = "api.port";
    private static final String STAFF_PERMISSION = "ducksapi.staff";
    private static final int MAX_EVENTS = 10;

    private HttpServer httpServer;
    private final AtomicReference<String> serverStatus = new AtomicReference<>("offline");
    private final Deque<StatusEvent> recentEvents = new ArrayDeque<>();
    private long startTimeMillis;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        final int port = getConfig().getInt(CONFIG_API_PORT_PATH, 8080);
        serverStatus.set("online");
        startTimeMillis = System.currentTimeMillis();
        getServer().getPluginManager().registerEvents(this, this);

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
        } catch (IOException ex) {
            serverStatus.set("offline");
            getLogger().severe("Failed to start Duck's API HTTP server: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (httpServer != null) {
            serverStatus.set("restarting");
            httpServer.stop(1);
            httpServer = null;
        }

        serverStatus.set("offline");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        addEvent("join", event.getPlayer().getName());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        addEvent("leave", event.getPlayer().getName());
    }

    private void addEvent(String type, String player) {
        synchronized (recentEvents) {
            recentEvents.addLast(new StatusEvent(type, player, System.currentTimeMillis() / 1000L));
            while (recentEvents.size() > MAX_EVENTS) {
                recentEvents.removeFirst();
            }
        }
    }

    private final class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            final Permission permissionService = getPermissionService();
            final String permissionPlugin = permissionService == null
                    ? "none"
                    : permissionService.getName();

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
                playersListJson
                        .append("{\"name\":\"").append(jsonEscape(player.getName())).append("\",")
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
                    staffListJson
                            .append("{\"name\":\"").append(jsonEscape(player.getName())).append("\",")
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
                worldsJson
                        .append("{\"name\":\"").append(jsonEscape(world.getName())).append("\",")
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

            final String json = "{" +
                    "\"status\":\"" + serverStatus.get() + "\"," +
                    "\"timestamp\":{\"unix\":" + timestamp.getEpochSecond() + "," +
                    "\"iso8601\":\"" + timestamp + "\"}," +
                    "\"server\":{" +
                    "\"name\":\"Xynex\"," +
                    "\"motd\":\"" + jsonEscape(Bukkit.getMotd()) + "\"," +
                    "\"loader\":{" +
                    "\"name\":\"" + jsonEscape(Bukkit.getName()) + "\"," +
                    "\"version\":\"" + jsonEscape(Bukkit.getVersion()) + "\"}," +
                    "\"minecraft\":{" +
                    "\"version\":\"" + jsonEscape(Bukkit.getMinecraftVersion()) + "\"," +
                    "\"bukkitVersion\":\"" + jsonEscape(Bukkit.getBukkitVersion()) + "\"}," +
                    "\"uptime\":{" +
                    "\"seconds\":" + uptimeSeconds + "," +
                    "\"hours\":" + uptimeHours + "," +
                    "\"minutes\":" + uptimeMinutes + "," +
                    "\"remainingSeconds\":" + uptimeRemainingSeconds + "," +
                    "\"formatted\":\"" + formatUptime(uptimeHours, uptimeMinutes, uptimeRemainingSeconds) + "\"}" +
                    "}," +
                    "\"players\":{" +
                    "\"online\":" + onlinePlayers + "," +
                    "\"max\":" + maxPlayers + "," +
                    "\"list\":[" + playersListJson + "]" +
                    "}," +
                    "\"staff\":{" +
                    "\"online\":" + staffOnline + "," +
                    "\"permission\":\"" + STAFF_PERMISSION + "\"," +
                    "\"provider\":\"" + jsonEscape(permissionPlugin) + "\"," +
                    "\"list\":[" + staffListJson + "]" +
                    "}," +
                    "\"worlds\":[" + worldsJson + "]," +
                    "\"performance\":{" +
                    "\"tps\":" + formatTwoDecimals(tps) + "," +
                    "\"mspt\":" + formatTwoDecimals(mspt) +
                    "}," +
                    "\"events\":[" + eventsJson + "]" +
                    "}";

            final byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(payload);
            }
        }

        private Permission getPermissionService() {
            final RegisteredServiceProvider<Permission> registration =
                    Bukkit.getServicesManager().getRegistration(Permission.class);
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
    }

    private record StatusEvent(String type, String player, long timestamp) {
    }
}
