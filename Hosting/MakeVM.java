package com.powerxhost;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public final class MakeVM {

    private static final Path ROOT =
        Path.of(System.getenv().getOrDefault("PXH_HOME", "powerxhost-data"));

    private static final Path SERVER = ROOT.resolve("server");
    private static final Path JAR = SERVER.resolve("paper.jar");

    private static final Deque<String> LOG = new ArrayDeque<>();

    private static volatile Process process;
    private static volatile String version = "1.21.4";
    private static volatile int port = 25565;
    private static volatile int memory = 2048;
    private static volatile String state = "stopped";

    public static void main(String[] args) throws Exception {

        Files.createDirectories(SERVER);

        String bind = System.getenv().getOrDefault(
            "PXH_BIND", "127.0.0.1"
        );

        int panelPort = Integer.parseInt(
            System.getenv().getOrDefault("PXH_PORT", "8765")
        );

        HttpServer server = HttpServer.create(
            new InetSocketAddress(bind, panelPort), 0
        );

        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/", MakeVM::handle);

        server.start();

        log("PowerXhost started at http://" + bind + ":" + panelPort);

        Runtime.getRuntime().addShutdownHook(
            new Thread(MakeVM::stopServer)
        );
    }

    private static void handle(HttpExchange x) throws IOException {

        String path = x.getRequestURI().getPath();

        if (path.equals("/") || path.equals("/index.html")
                || path.equals("/agent.js")) {

            String name = path.equals("/agent.js")
                ? "agent.js" : "index.html";

            Path file = Path.of("WebSite", name);

            if (!Files.exists(file)) {
                json(x, 404, Map.of("error", "Website file missing"));
                return;
            }

            byte[] data = Files.readAllBytes(file);

            x.getResponseHeaders().set(
                "Content-Type",
                name.endsWith(".js")
                    ? "application/javascript"
                    : "text/html"
            );

            x.sendResponseHeaders(200, data.length);

            try (OutputStream out = x.getResponseBody()) {
                out.write(data);
            }

            return;
        }

        if (!authorized(x)) {
            json(x, 401, Map.of("error", "Unauthorized"));
            return;
        }

        try {

            switch (path) {

                case "/api/status" -> {

                    Process p = process;

                    if (p != null && !p.isAlive()) {
                        state = "stopped";
                    }

                    Map<String, Object> result = new LinkedHashMap<>();

                    result.put("ok", true);
                    result.put("running", p != null && p.isAlive());
                    result.put("state", state);
                    result.put("version", version);
                    result.put("port", port);
                    result.put("memoryMb", memory);
                    result.put("host", TestRam.snapshot());
                    result.put("checkedAt", Instant.now().toString());

                    json(x, 200, result);
                }

                case "/api/console" -> {

                    synchronized (LOG) {
                        json(x, 200, Map.of(
                            "lines", new ArrayList<>(LOG)
                        ));
                    }
                }

                case "/api/start" -> {

                    startServer();

                    json(x, 200, Map.of("ok", true));
                }

                case "/api/stop" -> {

                    stopServer();

                    json(x, 200, Map.of("ok", true));
                }

                case "/api/command" -> {

                    String command = field(
                        body(x), "command"
                    );

                    if (command.isBlank()) {
                        throw new IllegalArgumentException(
                            "Command cannot be empty"
                        );
                    }

                    Process p = process;

                    if (p == null || !p.isAlive()) {
                        throw new IllegalStateException(
                            "Server is offline"
                        );
                    }

                    p.getOutputStream().write(
                        (command + "\n").getBytes(StandardCharsets.UTF_8)
                    );

                    p.getOutputStream().flush();

                    json(x, 200, Map.of("ok", true));
                }

                default -> json(x, 404, Map.of("error", "Not found"));
            }

        } catch (Exception e) {

            log("ERROR: " + e.getMessage());

            json(x, 400, Map.of(
                "error", e.getMessage() == null
                    ? "Request failed" : e.getMessage()
            ));
        }
    }

    private static void startServer() throws Exception {

        if (process != null && process.isAlive()) return;

        Files.createDirectories(SERVER);

        Path eula = SERVER.resolve("eula.txt");

        if (!Files.exists(eula)) {

            Files.writeString(eula, "eula=false\n");

            throw new Exception(
                "Created eula.txt. Review Minecraft EULA and set eula=true."
            );
        }

        if (!Files.readString(eula).contains("eula=true")) {
            throw new Exception("Minecraft EULA not accepted");
        }

        if (!Files.exists(JAR)) {
            downloadPaper();
        }

        String java = Path.of(
            System.getProperty("java.home"),
            "bin", "java"
        ).toString();

        ProcessBuilder builder = new ProcessBuilder(
            java,
            "-Xms512M",
            "-Xmx" + memory + "M",
            "-jar",
            JAR.toString(),
            "nogui"
        );

        builder.directory(SERVER.toFile());
        builder.redirectErrorStream(true);

        process = builder.start();
        state = "starting";

        Process started = process;

        Thread reader = new Thread(() -> {

            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(started.getInputStream())
            )) {

                String line;

                while ((line = reader.readLine()) != null) {

                    log(line);

                    if (line.contains("Done (")) {
                        state = "online";
                    }
                }

            } catch (Exception e) {
                log(e.getMessage());
            }

        });

        reader.setDaemon(true);
        reader.start();
    }

    private static void stopServer() {

        Process p = process;

        if (p == null || !p.isAlive()) {
            state = "stopped";
            return;
        }

        try {

            p.getOutputStream().write(
                "stop\n".getBytes(StandardCharsets.UTF_8)
            );

            p.getOutputStream().flush();

            if (!p.waitFor(25, TimeUnit.SECONDS)) {
                p.destroy();
            }

        } catch (Exception e) {
            p.destroyForcibly();
        }

        process = null;
        state = "stopped";

        log("Server stopped");
    }

    private static void downloadPaper() throws Exception {

        String api =
            "https://api.papermc.io/v2/projects/paper/versions/"
            + version + "/builds";

        String response = get(api);

        Matcher builds = Pattern.compile(
            "\"build\"\\s*:\\s*(\\d+)"
        ).matcher(response);

        int latest = -1;

        while (builds.find()) {
            latest = Integer.parseInt(builds.group(1));
        }

        if (latest < 0) {
            throw new Exception("No Paper build found");
        }

        String name = "paper-" + version + "-" + latest + ".jar";

        String url =
            "https://api.papermc.io/v2/projects/paper/versions/"
            + version + "/builds/" + latest
            + "/downloads/" + name;

        log("Downloading PaperMC...");

        HttpURLConnection connection =
            (HttpURLConnection) URI.create(url).toURL().openConnection();

        connection.setConnectTimeout(15000);
        connection.setReadTimeout(180000);

        try (InputStream in = connection.getInputStream()) {
            Files.copy(in, JAR, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            connection.disconnect();
        }

        log("PaperMC downloaded successfully");
    }

    private static String get(String url) throws IOException {

        HttpURLConnection connection =
            (HttpURLConnection) URI.create(url).toURL().openConnection();

        connection.setConnectTimeout(15000);

        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static String body(HttpExchange x) throws IOException {
        return new String(
            x.getRequestBody().readAllBytes(),
            StandardCharsets.UTF_8
        );
    }

    private static String field(String json, String key) {

        Matcher matcher = Pattern.compile(
            "\"" + Pattern.quote(key)
            + "\"\\s*:\\s*\"([^\"\\\\]*)\""
        ).matcher(json);

        return matcher.find() ? matcher.group(1) : "";
    }

    private static boolean authorized(HttpExchange x) {

        String token = System.getenv().getOrDefault(
            "PXH_TOKEN", ""
        );

        return !token.isBlank()
            && ("Bearer " + token).equals(
                x.getRequestHeaders().getFirst("Authorization")
            );
    }

    private static void json(
        HttpExchange x, int status, Object value
    ) throws IOException {

        String response = value.toString();

        byte[] data = response.getBytes(StandardCharsets.UTF_8);

        x.getResponseHeaders().set(
            "Content-Type", "application/json"
        );

        x.sendResponseHeaders(status, data.length);

        try (OutputStream out = x.getResponseBody()) {
            out.write(data);
        }
    }

    private static void log(String message) {

        synchronized (LOG) {

            LOG.addLast(
                "[" + Instant.now() + "] " + message
            );

            while (LOG.size() > 250) {
                LOG.removeFirst();
            }
        }

        System.out.println(message);
    }
}
