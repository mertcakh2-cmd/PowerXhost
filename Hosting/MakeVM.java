package com.powerxhost;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public final class MakeVM {

    // ==========================================
    // POWERXHOST CONFIGURATION
    // ==========================================

    private static final Path ROOT = Path.of(
        System.getenv().getOrDefault("PXH_HOME", "powerxhost-data")
    ).toAbsolutePath();

    private static final Path SERVER = ROOT.resolve("server");
    private static final Path JAR = SERVER.resolve("paper.jar");
    private static final Path CONFIG = ROOT.resolve("config.properties");

    private static final Deque<String> LOGS = new ArrayDeque<>();

    private static final Object SERVER_LOCK = new Object();

    private static volatile Process process;

    private static volatile String version = "1.21.4";
    private static volatile int port = 25565;
    private static volatile int memoryMb = 2048;

    private static volatile String state = "stopped";

    private static HttpServer httpServer;

    private MakeVM() {}


    // ==========================================
    // MAIN
    // ==========================================

    public static void main(String[] args) throws Exception {

        Files.createDirectories(ROOT);
        Files.createDirectories(SERVER);

        loadConfig();

        String bind = System.getenv().getOrDefault(
            "PXH_BIND",
            "127.0.0.1"
        );

        int panelPort = Integer.parseInt(
            System.getenv().getOrDefault(
                "PXH_PORT",
                "8765"
            )
        );

        httpServer = HttpServer.create(
            new InetSocketAddress(bind, panelPort),
            0
        );

        httpServer.setExecutor(
            Executors.newCachedThreadPool()
        );

        httpServer.createContext("/", MakeVM::handle);

        Runtime.getRuntime().addShutdownHook(
            new Thread(() -> {
                stopServer();

                if (httpServer != null) {
                    httpServer.stop(1);
                }
            })
        );

        httpServer.start();

        log("================================");
        log("       POWERXHOST STARTED       ");
        log("================================");

        log("Panel: http://" + bind + ":" + panelPort);
        log("Server directory: " + SERVER);
        log("Paper version: " + version);
        log("Minecraft port: " + port);
        log("Memory: " + memoryMb + " MB");
    }


    // ==========================================
    // HTTP ROUTER
    // ==========================================

    private static void handle(HttpExchange exchange)
            throws IOException {

        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        try {

            if (path.equals("/") ||
                path.equals("/index.html") ||
                path.equals("/agent.js") ||
                path.equals("/script.js") ||
                path.equals("/Style.css") ||
                path.equals("/style.css")) {

                serveWebsite(exchange, path);
                return;
            }

            if (!path.startsWith("/api/")) {
                sendJson(exchange, 404, Map.of(
                    "error", "Not found"
                ));
                return;
            }

            if (!authorized(exchange)) {
                sendJson(exchange, 401, Map.of(
                    "ok", false,
                    "error", "Unauthorized"
                ));
                return;
            }

            switch (path) {

                case "/api/status" -> {

                    if (!method.equals("GET")) {
                        methodNotAllowed(exchange);
                        return;
                    }

                    sendJson(exchange, 200, status());
                }

                case "/api/config" -> {

                    if (method.equals("GET")) {

                        sendJson(exchange, 200, config());

                    } else if (method.equals("POST")) {

                        updateConfig(exchange);

                    } else {

                        methodNotAllowed(exchange);
                    }
                }

                case "/api/console" -> {

                    if (!method.equals("GET")) {
                        methodNotAllowed(exchange);
                        return;
                    }

                    synchronized (LOGS) {

                        sendJson(exchange, 200, Map.of(
                            "ok", true,
                            "lines", new ArrayList<>(LOGS)
                        ));
                    }
                }

                case "/api/start" -> {

                    if (!method.equals("POST")) {
                        methodNotAllowed(exchange);
                        return;
                    }

                    startServer();

                    sendJson(exchange, 200, Map.of(
                        "ok", true,
                        "message", "Server starting"
                    ));
                }

                case "/api/stop" -> {

                    if (!method.equals("POST")) {
                        methodNotAllowed(exchange);
                        return;
                    }

                    stopServer();

                    sendJson(exchange, 200, Map.of(
                        "ok", true,
                        "message", "Server stopped"
                    ));
                }

                case "/api/command" -> {

                    if (!method.equals("POST")) {
                        methodNotAllowed(exchange);
                        return;
                    }

                    executeCommand(readBody(exchange));

                    sendJson(exchange, 200, Map.of(
                        "ok", true
                    ));
                }

                default -> sendJson(exchange, 404, Map.of(
                    "error", "Unknown API endpoint"
                ));
            }

        } catch (Exception exception) {

            String message = exception.getMessage();

            if (message == null || message.isBlank()) {
                message = "Internal server error";
            }

            log("ERROR: " + message);

            sendJson(exchange, 400, Map.of(
                "ok", false,
                "error", message
            ));
        }
    }


    // ==========================================
    // WEBSITE FILE SERVER
    // ==========================================

    private static void serveWebsite(
        HttpExchange exchange,
        String path
    ) throws IOException {

        String filename;

        switch (path) {

            case "/agent.js" -> filename = "agent.js";

            case "/script.js" -> filename = "script.js";

            case "/Style.css" -> filename = "Style.css";

            case "/style.css" -> filename = "style.css";

            default -> filename = "index.html";
        }

        Path file = Path.of("WebSite").resolve(filename);

        if (!Files.isRegularFile(file)) {

            sendJson(exchange, 404, Map.of(
                "error", "Website file not found: " + filename
            ));

            return;
        }

        String contentType;

        if (filename.endsWith(".js")) {
            contentType = "application/javascript; charset=UTF-8";
        } else if (filename.endsWith(".css")) {
            contentType = "text/css; charset=UTF-8";
        } else {
            contentType = "text/html; charset=UTF-8";
        }

        byte[] data = Files.readAllBytes(file);

        exchange.getResponseHeaders().set(
            "Content-Type",
            contentType
        );

        exchange.getResponseHeaders().set(
            "Cache-Control",
            "no-cache"
        );

        exchange.sendResponseHeaders(200, data.length);

        try (OutputStream output = exchange.getResponseBody()) {
            output.write(data);
        }
    }


    // ==========================================
    // AUTHENTICATION
    // ==========================================

    private static boolean authorized(HttpExchange exchange) {

        String token = System.getenv().getOrDefault(
            "PXH_TOKEN",
            ""
        );

        if (token.isBlank()) {
            return false;
        }

        String authorization = exchange.getRequestHeaders()
            .getFirst("Authorization");

        return authorization != null &&
            authorization.equals("Bearer " + token);
    }


    // ==========================================
    // SERVER STATUS
    // ==========================================

    private static Map<String, Object> status() {

        Process current = process;

        boolean online = current != null && current.isAlive();

        if (!online) {
            state = "stopped";
        }

        Map<String, Object> host = TestRam.snapshot();

        long used = ((Number) host.get("usedMb")).longValue();
        long max = ((Number) host.get("maxMb")).longValue();

        int cores = ((Number) host.get("processors")).intValue();

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("ok", true);

        // Compatibility with the Android application.
        result.put("online", online);
        result.put("running", online);

        result.put("state", state);

        result.put("version", version);
        result.put("port", port);

        result.put("memoryMb", memoryMb);

        result.put("memoryUsed", used);
        result.put("memoryMax", memoryMb);

        result.put("cores", cores);

        result.put("host", host);

        result.put("checkedAt", Instant.now().toString());

        return result;
    }


    // ==========================================
    // CONFIGURATION
    // ==========================================

    private static Map<String, Object> config() {

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("ok", true);
        result.put("version", version);
        result.put("port", port);
        result.put("memoryMb", memoryMb);

        return result;
    }


    private static void updateConfig(HttpExchange exchange)
            throws Exception {

        String body = readBody(exchange);

        String newVersion = jsonString(body, "version");

        Integer newPort = jsonInteger(body, "port");

        Integer newMemory = jsonInteger(body, "memoryMb");

        if (newMemory == null) {
            newMemory = jsonInteger(body, "memoryMB");
        }

        if (newVersion == null || newPort == null || newMemory == null) {
            throw new IllegalArgumentException(
                "Missing version, port or memoryMb"
            );
        }

        if (!newVersion.matches("\\d+\\.\\d+(\\.\\d+)?")) {
            throw new IllegalArgumentException(
                "Invalid Paper version"
            );
        }

        if (newPort < 1024 || newPort > 65535) {
            throw new IllegalArgumentException(
                "Port must be between 1024 and 65535"
            );
        }

        if (newMemory < 1024 || newMemory > 32768) {
            throw new IllegalArgumentException(
                "Memory must be between 1024 and 32768 MB"
            );
        }

        if (process != null && process.isAlive()) {
            throw new IllegalStateException(
                "Stop the server before changing configuration"
            );
        }

        version = newVersion;
        port = newPort;
        memoryMb = newMemory;

        saveConfig();

        sendJson(exchange, 200, Map.of(
            "ok", true,
            "message", "Configuration saved",
            "config", config()
        ));

        log("Configuration updated");
    }


    private static void loadConfig() throws IOException {

        if (!Files.exists(CONFIG)) {
            saveConfig();
            return;
        }

        Properties properties = new Properties();

        try (InputStream input = Files.newInputStream(CONFIG)) {
            properties.load(input);
        }

        version = properties.getProperty(
            "version",
            version
        );

        port = Integer.parseInt(
            properties.getProperty(
                "port",
                String.valueOf(port)
            )
        );

        memoryMb = Integer.parseInt(
            properties.getProperty(
                "memoryMb",
                String.valueOf(memoryMb)
            )
        );
    }


    private static void saveConfig() throws IOException {

        Files.createDirectories(ROOT);

        Properties properties = new Properties();

        properties.setProperty("version", version);
        properties.setProperty("port", String.valueOf(port));
        properties.setProperty("memoryMb", String.valueOf(memoryMb));

        try (OutputStream output = Files.newOutputStream(CONFIG)) {
            properties.store(output, "PowerXhost configuration");
        }
    }


    // ==========================================
    // START MINECRAFT SERVER
    // ==========================================

    private static void startServer() throws Exception {

        synchronized (SERVER_LOCK) {

            if (process != null && process.isAlive()) {
                throw new IllegalStateException(
                    "Minecraft server is already running"
                );
            }

            Files.createDirectories(SERVER);

            checkEula();

            configureServerProperties();

            if (!Files.exists(JAR)) {
                downloadPaper();
            }

            String java = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").toLowerCase().contains("win")
                    ? "java.exe"
                    : "java"
            ).toString();

            ProcessBuilder builder = new ProcessBuilder(
                java,
                "-Xms512M",
                "-Xmx" + memoryMb + "M",
                "-jar",
                JAR.toAbsolutePath().toString(),
                "nogui"
            );

            builder.directory(SERVER.toFile());
            builder.redirectErrorStream(true);

            process = builder.start();

            state = "starting";

            Process started = process;

            Thread logReader = new Thread(() -> {

                try (
                    BufferedReader processReader =
                        new BufferedReader(
                            new InputStreamReader(
                                started.getInputStream(),
                                StandardCharsets.UTF_8
                            )
                        )
                ) {

                    String line;

                    while ((line = processReader.readLine()) != null) {

                        log(line);

                        if (line.contains("Done (")) {
                            state = "online";
                        }
                    }

                } catch (IOException exception) {

                    log("Console reader: " + exception.getMessage());

                } finally {

                    if (process == started) {
                        process = null;
                        state = "stopped";
                    }

                    log("Minecraft process exited");
                }

            }, "PowerXhost-Console");

            logReader.setDaemon(true);
            logReader.start();

            log("Minecraft server process started");
        }
    }


    // ==========================================
    // EULA
    // ==========================================

    private static void checkEula() throws IOException {

        Path eula = SERVER.resolve("eula.txt");

        if (!Files.exists(eula)) {

            Files.writeString(
                eula,
                "# Review the Minecraft EULA before accepting.\n" +
                "eula=false\n"
            );

            throw new IllegalStateException(
                "Created eula.txt. Review the Minecraft EULA and " +
                "change eula=false to eula=true if you agree."
            );
        }

        boolean accepted = Files.readAllLines(eula)
            .stream()
            .map(String::trim)
            .anyMatch(line ->
                line.matches("(?i)^eula\\s*=\\s*true\\s*$")
            );

        if (!accepted) {
            throw new IllegalStateException(
                "Minecraft EULA has not been accepted"
            );
        }
    }


    // ==========================================
    // SERVER.PROPERTIES
    // ==========================================

    private static void configureServerProperties()
            throws IOException {

        Path propertiesFile = SERVER.resolve("server.properties");

        Properties properties = new Properties();

        if (Files.exists(propertiesFile)) {

            try (InputStream input =
                     Files.newInputStream(propertiesFile)) {

                properties.load(input);
            }
        }

        properties.setProperty(
            "server-port",
            String.valueOf(port)
        );

        try (OutputStream output =
                 Files.newOutputStream(propertiesFile)) {

            properties.store(
                output,
                "PowerXhost server configuration"
            );
        }
    }


    // ==========================================
    // STOP MINECRAFT SERVER
    // ==========================================

    private static void stopServer() {

        synchronized (SERVER_LOCK) {

            Process current = process;

            if (current == null || !current.isAlive()) {

                process = null;
                state = "stopped";

                return;
            }

            try {

                log("Stopping Minecraft server...");

                current.getOutputStream().write(
                    "stop\n".getBytes(StandardCharsets.UTF_8)
                );

                current.getOutputStream().flush();

                if (!current.waitFor(30, TimeUnit.SECONDS)) {

                    log("Graceful shutdown timed out");

                    current.destroy();

                    if (!current.waitFor(5, TimeUnit.SECONDS)) {
                        current.destroyForcibly();
                    }
                }

            } catch (Exception exception) {

                log("Shutdown error: " + exception.getMessage());

                current.destroyForcibly();

            } finally {

                process = null;
                state = "stopped";

                log("Server stopped");
            }
        }
    }


    // ==========================================
    // MINECRAFT COMMANDS
    // ==========================================

    private static void executeCommand(String body)
            throws Exception {

        String command = jsonString(body, "command");

        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException(
                "Command cannot be empty"
            );
        }

        if (command.length() > 300 ||
            command.contains("\n") ||
            command.contains("\r")) {

            throw new IllegalArgumentException(
                "Invalid command"
            );
        }

        Process current = process;

        if (current == null || !current.isAlive()) {
            throw new IllegalStateException(
                "Minecraft server is offline"
            );
        }

        current.getOutputStream().write(
            (command + "\n").getBytes(StandardCharsets.UTF_8)
        );

        current.getOutputStream().flush();

        log("COMMAND: " + command);
    }


    // ==========================================
    // PAPERMC DOWNLOAD
    // ==========================================

    private static void downloadPaper() throws Exception {

        log("Looking for PaperMC build " + version);

        String api =
            "https://api.papermc.io/v2/projects/paper/versions/"
            + version + "/builds";

        String response = httpGet(api);

        Matcher matcher = Pattern.compile(
            "\"build\"\\s*:\\s*(\\d+)"
        ).matcher(response);

        int latestBuild = -1;

        while (matcher.find()) {
            latestBuild = Integer.parseInt(matcher.group(1));
        }

        if (latestBuild < 0) {
            throw new IOException(
                "No PaperMC build found for " + version
            );
        }

        String filename =
            "paper-" + version + "-" + latestBuild + ".jar";

        String downloadUrl =
            "https://api.papermc.io/v2/projects/paper/versions/"
            + version + "/builds/" + latestBuild
            + "/downloads/" + filename;

        Path temporary = SERVER.resolve("paper.jar.part");

        log("Downloading " + filename);

        HttpURLConnection connection =
            (HttpURLConnection) URI.create(downloadUrl)
                .toURL()
                .openConnection();

        connection.setConnectTimeout(15000);
        connection.setReadTimeout(180000);

        connection.setRequestProperty(
            "User-Agent",
            "PowerXhost/1.0"
        );

        try {

            int code = connection.getResponseCode();

            if (code != 200) {
                throw new IOException(
                    "Paper download failed: HTTP " + code
                );
            }

            try (
                InputStream input = connection.getInputStream();
                OutputStream output = Files.newOutputStream(temporary)
            ) {

                input.transferTo(output);
            }

            if (Files.size(temporary) < 1024) {
                throw new IOException(
                    "Downloaded Paper JAR is too small"
                );
            }

            try {

                Files.move(
                    temporary,
                    JAR,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                );

            } catch (AtomicMoveNotSupportedException exception) {

                Files.move(
                    temporary,
                    JAR,
                    StandardCopyOption.REPLACE_EXISTING
                );
            }

        } finally {

            connection.disconnect();

            Files.deleteIfExists(temporary);
        }

        log("PaperMC downloaded successfully");
    }


    private static String httpGet(String url)
            throws IOException {

        HttpURLConnection connection =
            (HttpURLConnection) URI.create(url)
                .toURL()
                .openConnection();

        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);

        connection.setRequestProperty(
            "User-Agent",
            "PowerXhost/1.0"
        );

        try {

            int code = connection.getResponseCode();

            if (code != 200) {
                throw new IOException(
                    "Paper API returned HTTP " + code
                );
            }

            try (InputStream input = connection.getInputStream()) {

                return new String(
                    input.readAllBytes(),
                    StandardCharsets.UTF_8
                );
            }

        } finally {

            connection.disconnect();
        }
    }


    // ==========================================
    // REQUEST BODY
    // ==========================================

    private static String readBody(HttpExchange exchange)
            throws IOException {

        try (InputStream input = exchange.getRequestBody()) {

            ByteArrayOutputStream output = new ByteArrayOutputStream();

            byte[] buffer = new byte[2048];

            int total = 0;
            int count;

            while ((count = input.read(buffer)) != -1) {

                total += count;

                if (total > 8192) {
                    throw new IOException(
                        "Request body too large"
                    );
                }

                output.write(buffer, 0, count);
            }

            return output.toString(StandardCharsets.UTF_8);
        }
    }


    // ==========================================
    // SIMPLE JSON FIELD PARSER
    // ==========================================

    private static String jsonString(String json, String key) {

        Matcher matcher = Pattern.compile(
            "\"" + Pattern.quote(key) +
            "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\""
        ).matcher(json);

        if (!matcher.find()) {
            return null;
        }

        return matcher.group(1)
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r");
    }


    private static Integer jsonInteger(String json, String key) {

        Matcher matcher = Pattern.compile(
            "\"" + Pattern.quote(key) +
            "\"\\s*:\\s*(-?\\d+)"
        ).matcher(json);

        if (!matcher.find()) {
            return null;
        }

        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            return null;
        }
    }


    // ==========================================
    // JSON SERIALIZER
    // ==========================================

    private static String toJson(Object value) {

        if (value == null) {
            return "null";
        }

        if (value instanceof String string) {

            return "\"" +
                string.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t") +
                "\"";
        }

        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }

        if (value instanceof Map<?, ?> map) {

            StringBuilder result = new StringBuilder("{");

            boolean first = true;

            for (Map.Entry<?, ?> entry : map.entrySet()) {

                if (!first) {
                    result.append(",");
                }

                first = false;

                result.append(toJson(String.valueOf(entry.getKey())));
                result.append(":");
                result.append(toJson(entry.getValue()));
            }

            return result.append("}").toString();
        }

        if (value instanceof Iterable<?> iterable) {

            StringBuilder result = new StringBuilder("[");

            boolean first = true;

            for (Object item : iterable) {

                if (!first) {
                    result.append(",");
                }

                first = false;

                result.append(toJson(item));
            }

            return result.append("]").toString();
        }

        return toJson(value.toString());
    }


    // ==========================================
    // HTTP RESPONSE
    // ==========================================

    private static void sendJson(
        HttpExchange exchange,
        int status,
        Object value
    ) throws IOException {

        byte[] data = toJson(value).getBytes(
            StandardCharsets.UTF_8
        );

        exchange.getResponseHeaders().set(
            "Content-Type",
            "application/json; charset=UTF-8"
        );

        exchange.getResponseHeaders().set(
            "Cache-Control",
            "no-store"
        );

        exchange.sendResponseHeaders(status, data.length);

        try (OutputStream output = exchange.getResponseBody()) {
            output.write(data);
        }
    }


    private static void methodNotAllowed(HttpExchange exchange)
            throws IOException {

        sendJson(exchange, 405, Map.of(
            "ok", false,
            "error", "Method not allowed"
        ));
    }


    // ==========================================
    // LOGGING
    // ==========================================

    private static void log(String message) {

        String timestamp = Instant.now().toString();

        String line = "[" + timestamp + "] " + message;

        synchronized (LOGS) {

            LOGS.addLast(line);

            while (LOGS.size() > 250) {
                LOGS.removeFirst();
            }
        }

        System.out.println(line);
    }
}
