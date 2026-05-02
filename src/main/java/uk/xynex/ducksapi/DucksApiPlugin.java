package uk.xynex.ducksapi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public final class DucksApiPlugin extends JavaPlugin {
    private static final String CONFIG_API_PORT_PATH = "api.port";

    private HttpServer httpServer;
    private final AtomicReference<String> serverStatus = new AtomicReference<>("offline");

    @Override
    public void onEnable() {
        saveDefaultConfig();

        final int port = getConfig().getInt(CONFIG_API_PORT_PATH, 8080);
        serverStatus.set("online");

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

    private final class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            final int onlinePlayers = Bukkit.getOnlinePlayers().size();
            final int maxPlayers = Bukkit.getMaxPlayers();
            final String json = "{" +
                    "\"status\":\"" + serverStatus.get() + "\"," +
                    "\"players\":{" +
                    "\"online\":" + onlinePlayers + "," +
                    "\"max\":" + maxPlayers +
                    "}" +
                    "}";

            final byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(payload);
            }
        }
    }
}
