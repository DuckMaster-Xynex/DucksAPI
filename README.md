# Duck's API (Paper Plugin)

A beginner-friendly Minecraft **Paper** plugin that opens a tiny HTTP API endpoint:

- `GET /status`
- Returns JSON with:
  - server status (`online`, `offline`, `restarting`)
  - online player count
  - max player count
  - staff count using permsssion

---

## 1) What this plugin does (in plain English)

When your Minecraft server is running, this plugin can answer a web request like:

`http://YOUR_SERVER_IP:8080/status`

And return data like:

```json
{
  "status": "online",
  "players": {
    "online": 5,
    "max": 100
  }
}
```

This is useful if you want a website, Discord bot, or dashboard to show server status.

---

## 2) Requirements

You need:

1. A **Paper** Minecraft server (latest stable recommended)
2. **Java 21** installed on the machine where you build/run this plugin
3. Access to your server files (`plugins` folder)

---

## 3) Install on your Minecraft Paper server

1. **Stop** your Minecraft server.
2. Copy the plugin jar into your server's `plugins/` folder.
3. Start the server.
4. Wait until startup finishes.
5. Stop the server again (optional but recommended so config file is written cleanly).

The plugin auto-creates `config.yml` on first run.

---

## 4) Configure the API port

1. Open this file:

`plugins/DucksAPI/config.yml`

2. You will see:

```yml
api:
  port: 8080
```

3. Change `8080` if needed (for example `8090`).
5. Save the file.
6. Start or restart your server.

(You may need to expose the port via your server host(if not local) for this to work.

---

## 5) Test that it works

### From the server machine
Run:

```bash
curl http://127.0.0.1:8080/status
```

(Replace `8080` if you changed the port.)

If working, you will get JSON response.

### From another machine
Use:

`http://SERVER_PUBLIC_IP:8080/status`

If it fails externally, see firewall/port sections below.

---

## 6) Open firewall/hosting rules (important)

You must allow inbound TCP traffic on the API port.

- If you use a game host panel: open the port there.
- If you use a VPS/cloud: open it in cloud firewall/security group.
- If you use Linux firewall (UFW):

```bash
sudo ufw allow 8080/tcp
```

Then reload/check firewall if needed.

---

## 7) Home hosting: router port forwarding

If your Minecraft server is at home and you want external access:

1. Log into your router.
2. Find **Port Forwarding**.
3. Forward external TCP port (example `8080`) to internal server IP + same port.
4. Make sure server machine has static local IP (or DHCP reservation).

Without this, outside users cannot reach your API.

---

## 8) Domain + DNS setup (easy explanation)

### Important
DNS only maps **names to IPs**. DNS does **not** map URL paths like `/api`.

So this:
- ✅ `api.xynex.uk` (works with DNS)
- ❌ `xynex.uk/api` (needs reverse proxy, not DNS alone)

### Recommended method
1. Create DNS record:
   - Type: `A`
   - Name: `api`
   - Value: your server public IPv4
2. Wait for DNS to propagate.
3. Use endpoint:
   - `http://api.xynex.uk:8080/status`

If you want `https://xynex.uk/api`, configure reverse proxy (Nginx/Caddy) to route `/api` to this plugin.

---

## 9) Connect a website frontend safely

### Safety checklist
- Prefer HTTPS (via reverse proxy)
- Keep API behind a reverse proxy in production
- Restrict CORS at proxy to your own domain
- Consider rate limiting to avoid abuse

### Example JavaScript fetch

```html
<script>
async function loadServerStatus() {
  try {
    const response = await fetch("https://api.xynex.uk/status", {
      method: "GET",
      headers: { "Accept": "application/json" }
    });

    if (!response.ok) {
      throw new Error("HTTP " + response.status);
    }

    const data = await response.json();

    document.getElementById("status").textContent = data.status;
    document.getElementById("online").textContent = data.players.online;
    document.getElementById("max").textContent = data.players.max;
  } catch (error) {
    console.error("Failed to load status:", error);
  }
}

loadServerStatus();
</script>
```

---

## 10) Release checklist (for publishing later)

If you want to release this plugin publicly:

1. Update version in `build.gradle.kts` and `plugin.yml`
2. Run build and verify startup on a test Paper server
3. Verify `/status` output with real players online
4. Write changelog/release notes
5. Upload final jar to your distribution platform
6. Include config example and quick-start instructions (this README)

---

## 11) Troubleshooting

### "Address already in use"
Another app is already using the port.
- Change `api.port` in `config.yml`
- Restart server

### Endpoint works locally but not publicly
Usually firewall, host panel, security group, or router forwarding issue.

### Empty or wrong response
Check server console for plugin startup errors and verify plugin loaded.

---

## 12) Uninstall

1. Stop server
2. Remove plugin jar from `plugins/`
3. (Optional) delete plugin config folder
4. Start server

Done.
