package com.powerhost;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private final int BACKGROUND = Color.rgb(10, 10, 15);
    private final int SURFACE = Color.rgb(20, 20, 29);
    private final int BORDER = Color.rgb(43, 43, 57);
    private final int PURPLE = Color.rgb(151, 112, 255);
    private final int TEXT = Color.rgb(245, 245, 250);
    private final int MUTED = Color.rgb(145, 145, 160);
    private final int GREEN = Color.rgb(67, 214, 155);
    private final int RED = Color.rgb(255, 100, 124);

    private LinearLayout root;
    private LinearLayout content;

    private EditText hostInput;
    private EditText tokenInput;

    private EditText versionInput;
    private EditText portInput;
    private EditText ramInput;

    private EditText commandInput;

    private TextView connectionStatus;
    private TextView serverStatus;
    private TextView cpuValue;
    private TextView memoryValue;
    private TextView portValue;
    private TextView consoleOutput;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private boolean connected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();

        window.setStatusBarColor(BACKGROUND);
        window.setNavigationBarColor(BACKGROUND);

        window.getDecorView().setSystemUiVisibility(0);

        getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        );

        buildInterface();
        loadPreferences();
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();

        drawable.setColor(color);
        drawable.setCornerRadius(radius);

        return drawable;
    }

    private GradientDrawable bordered(int color, int radius) {
        GradientDrawable drawable = background(color, radius);

        drawable.setStroke(1, BORDER);

        return drawable;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);

        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);

        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }

        return view;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);

        layout.setOrientation(LinearLayout.VERTICAL);

        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);

        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);

        return layout;
    }

    private void space(LinearLayout parent, int height) {
        View view = new View(this);

        parent.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height
        ));
    }

    private void padding(View view, int left, int top, int right, int bottom) {
        view.setPadding(left, top, right, bottom);
    }

    private void addSectionTitle(String title, String subtitle) {
        TextView heading = text(title, 19, TEXT, true);

        content.addView(heading);

        TextView description = text(subtitle, 12, MUTED, false);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        -1, -2
                );

        params.topMargin = 5;

        content.addView(description, params);

        space(content, 16);
    }

    private LinearLayout card() {
        LinearLayout layout = vertical();

        layout.setPadding(17, 17, 17, 17);
        layout.setBackground(bordered(SURFACE, 18));

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);

        params.bottomMargin = 15;

        content.addView(layout, params);

        return layout;
    }

    private EditText input(String hint) {
        EditText edit = new EditText(this);

        edit.setSingleLine(true);
        edit.setTextSize(13);
        edit.setTextColor(TEXT);
        edit.setHintTextColor(MUTED);
        edit.setHint(hint);

        edit.setPadding(13, 11, 13, 11);
        edit.setBackground(bordered(BACKGROUND, 10));

        return edit;
    }

    private Button button(String title, int color) {
        Button button = new Button(this);

        button.setText(title);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setAllCaps(false);

        button.setBackground(background(color, 11));

        button.setPadding(14, 5, 14, 5);

        return button;
    }

    private void addField(
            LinearLayout parent,
            String title,
            EditText field
    ) {
        TextView label = text(title, 12, TEXT, true);

        parent.addView(label);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);

        params.topMargin = 8;
        params.bottomMargin = 17;

        parent.addView(field, params);
    }

    private void buildInterface() {

        ScrollView scroll = new ScrollView(this);

        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);

        root = vertical();
        root.setBackgroundColor(BACKGROUND);

        scroll.addView(root);

        LinearLayout header = horizontal();

        header.setPadding(20, 23, 20, 20);

        root.addView(header);

        LinearLayout logo = vertical();

        logo.setGravity(Gravity.CENTER);
        logo.setBackground(background(PURPLE, 13));

        TextView logoText = text("P", 25, Color.WHITE, true);

        logo.addView(logoText);

        header.addView(logo, new LinearLayout.LayoutParams(45, 45));

        LinearLayout brand = vertical();

        LinearLayout.LayoutParams brandParams =
                new LinearLayout.LayoutParams(-2, -2);

        brandParams.leftMargin = 12;

        header.addView(brand, brandParams);

        brand.addView(text("PowerXhost", 19, TEXT, true));

        brand.addView(text(
                "SERVER MANAGEMENT",
                9,
                MUTED,
                true
        ));

        connectionStatus = text(
                "● DISCONNECTED",
                10,
                RED,
                true
        );

        LinearLayout.LayoutParams statusParams =
                new LinearLayout.LayoutParams(-2, -2);

        statusParams.leftMargin = 10;

        header.addView(connectionStatus, statusParams);


        content = vertical();

        content.setPadding(18, 10, 18, 25);

        root.addView(content);


        // WELCOME

        LinearLayout welcome = vertical();

        welcome.setPadding(22, 23, 22, 23);

        welcome.setBackground(bordered(
                Color.rgb(26, 22, 39),
                20
        ));

        content.addView(welcome);

        welcome.addView(text(
                "POWERXHOST CONTROL CENTER",
                10,
                PURPLE,
                true
        ));

        space(welcome, 13);

        welcome.addView(text(
                "Your server.\nYour control.",
                32,
                TEXT,
                true
        ));

        space(welcome, 10);

        welcome.addView(text(
                "Manage your Minecraft Paper server anywhere.",
                12,
                MUTED,
                false
        ));

        space(welcome, 22);

        Button connectShortcut = button(
                "Connect to server",
                PURPLE
        );

        welcome.addView(connectShortcut);

        connectShortcut.setOnClickListener(v ->
                connectBackend()
        );

        space(content, 27);


        // SERVER STATUS

        addSectionTitle(
                "Server overview",
                "Live information from your host."
        );

        LinearLayout stats = card();

        serverStatus = text("OFFLINE", 25, RED, true);
        stats.addView(text("SERVER STATUS", 10, MUTED, true));

        space(stats, 8);
        stats.addView(serverStatus);

        space(stats, 19);

        cpuValue = text("--", 18, TEXT, true);
        memoryValue = text("--", 18, TEXT, true);
        portValue = text("25565", 18, TEXT, true);

        stats.addView(text("CPU CORES", 10, MUTED, true));
        stats.addView(cpuValue);

        space(stats, 15);

        stats.addView(text("HOST MEMORY", 10, MUTED, true));
        stats.addView(memoryValue);

        space(stats, 15);

        stats.addView(text("MINECRAFT PORT", 10, MUTED, true));
        stats.addView(portValue);


        // CONTROLS

        addSectionTitle(
                "Server controls",
                "Start or stop your Minecraft server."
        );

        LinearLayout controls = card();

        Button start = button("START SERVER", PURPLE);
        Button stop = button("STOP SERVER", RED);
        Button refresh = button("REFRESH STATUS", BORDER);

        controls.addView(start);
        space(controls, 10);
        controls.addView(stop);
        space(controls, 10);
        controls.addView(refresh);

        start.setOnClickListener(v -> startServer());
        stop.setOnClickListener(v -> stopServer());
        refresh.setOnClickListener(v -> updateStatus());


        // CONFIGURATION

        addSectionTitle(
                "Configuration",
                "Customize your Paper server."
        );

        LinearLayout config = card();

        versionInput = input("1.21.4");
        versionInput.setText("1.21.4");

        portInput = input("25565");
        portInput.setText("25565");

        ramInput = input("2048");
        ramInput.setText("2048");

        addField(config, "Paper version", versionInput);
        addField(config, "Server port", portInput);
        addField(config, "Memory allocation (MB)", ramInput);

        Button saveConfig = button(
                "SAVE CONFIGURATION",
                PURPLE
        );

        config.addView(saveConfig);

        saveConfig.setOnClickListener(v -> saveConfig());


        // CONSOLE

        addSectionTitle(
                "Server console",
                "View logs and send commands."
        );

        LinearLayout console = card();

        consoleOutput = text(
                "PowerXhost console initialized.\nWaiting for backend...",
                11,
                GREEN,
                false
        );

        consoleOutput.setTypeface(Typeface.MONOSPACE);
        consoleOutput.setMinHeight(170);

        console.addView(consoleOutput);

        space(console, 15);

        commandInput = input("Enter Minecraft command...");

        console.addView(commandInput);

        space(console, 10);

        Button send = button("EXECUTE COMMAND", PURPLE);

        console.addView(send);

        send.setOnClickListener(v -> sendCommand());


        // CONNECTION

        addSectionTitle(
                "Backend connection",
                "Connect your PowerXhost hosting server."
        );

        LinearLayout connection = card();

        hostInput = input("http://192.168.1.100:8765");
        tokenInput = input("Access token");

        tokenInput.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        );

        addField(connection, "Backend URL", hostInput);
        addField(connection, "Access token", tokenInput);

        Button connect = button("CONNECT", PURPLE);

        connection.addView(connect);

        connect.setOnClickListener(v -> connectBackend());


        // FOOTER

        space(content, 20);

        TextView footer = text(
                "POWERXHOST  •  VERSION 1.0.0",
                10,
                MUTED,
                true
        );

        footer.setGravity(Gravity.CENTER);

        content.addView(footer);

        setContentView(scroll);
    }


    // PREFERENCES

    private void loadPreferences() {
        android.content.SharedPreferences prefs =
                getSharedPreferences("powerxhost", MODE_PRIVATE);

        hostInput.setText(
                prefs.getString("host", "")
        );

        tokenInput.setText(
                prefs.getString("token", "")
        );
    }

    private void savePreferences() {
        getSharedPreferences("powerxhost", MODE_PRIVATE)
                .edit()
                .putString("host", hostInput.getText().toString().trim())
                .putString("token", tokenInput.getText().toString().trim())
                .apply();
    }


    // API REQUEST

    private JSONObject request(
            String endpoint,
            String method,
            JSONObject body
    ) throws Exception {

        String host = hostInput.getText().toString().trim();

        while (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }

        if (host.isEmpty()) {
            throw new Exception("Backend URL is empty.");
        }

        URL url = new URL(host + endpoint);

        HttpURLConnection connection =
                (HttpURLConnection) url.openConnection();

        connection.setRequestMethod(method);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);

        connection.setRequestProperty(
                "Accept",
                "application/json"
        );

        String token = tokenInput.getText().toString().trim();

        if (!token.isEmpty()) {
            connection.setRequestProperty(
                    "Authorization",
                    "Bearer " + token
            );
        }

        if (body != null) {
            connection.setDoOutput(true);

            connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
            );

            byte[] data = body.toString().getBytes(
                    StandardCharsets.UTF_8
            );

            try (OutputStream output = connection.getOutputStream()) {
                output.write(data);
            }
        }

        int responseCode = connection.getResponseCode();

        InputStream stream =
                responseCode >= 200 && responseCode < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();

        StringBuilder response = new StringBuilder();

        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            stream,
                            StandardCharsets.UTF_8
                    )
            )) {
                String line;

                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
        }

        connection.disconnect();

        JSONObject result = response.length() > 0
                ? new JSONObject(response.toString())
                : new JSONObject();

        if (responseCode < 200 || responseCode >= 300) {
            throw new Exception(
                    result.optString(
                            "error",
                            "HTTP " + responseCode
                    )
            );
        }

        return result;
    }


    // CONNECTION

    private void connectBackend() {

        connectionStatus.setText("● CONNECTING...");
        connectionStatus.setTextColor(PURPLE);

        executor.execute(() -> {

            try {
                request("/api/status", "GET", null);

                connected = true;

                savePreferences();

                runOnUiThread(() -> {
                    connectionStatus.setText("● CONNECTED");
                    connectionStatus.setTextColor(GREEN);

                    Toast.makeText(
                            this,
                            "Connected successfully!",
                            Toast.LENGTH_SHORT
                    ).show();
                });

                updateStatus();
                loadConfig();

            } catch (Exception error) {

                connected = false;

                runOnUiThread(() -> {
                    connectionStatus.setText("● DISCONNECTED");
                    connectionStatus.setTextColor(RED);

                    showError(error.getMessage());
                });
            }
        });
    }


    // STATUS

    private void updateStatus() {

        executor.execute(() -> {

            try {
                JSONObject data = request(
                        "/api/status",
                        "GET",
                        null
                );

                boolean online = data.optBoolean("online", false);

                runOnUiThread(() -> {

                    serverStatus.setText(
                            online ? "ONLINE" : "OFFLINE"
                    );

                    serverStatus.setTextColor(
                            online ? GREEN : RED
                    );

                    cpuValue.setText(
                            String.valueOf(data.optInt("cores", 0))
                    );

                    if (data.has("memoryUsed") &&
                            data.has("memoryMax")) {

                        memoryValue.setText(
                                data.optString("memoryUsed") +
                                " / " +
                                data.optString("memoryMax") +
                                " MB"
                        );

                    } else if (data.has("memoryMax")) {

                        memoryValue.setText(
                                data.optString("memoryMax") + " MB"
                        );
                    }

                    if (data.has("port")) {
                        portValue.setText(
                                data.optString("port")
                        );
                    }
                });

                updateConsole();

            } catch (Exception error) {

                runOnUiThread(() -> {
                    connectionStatus.setText("● CONNECTION ERROR");
                    connectionStatus.setTextColor(RED);
                });
            }
        });
    }


    // CONFIGURATION

    private void loadConfig() {

        executor.execute(() -> {

            try {
                JSONObject data = request(
                        "/api/config",
                        "GET",
                        null
                );

                runOnUiThread(() -> {

                    versionInput.setText(
                            data.optString("version", "1.21.4")
                    );

                    portInput.setText(
                            String.valueOf(data.optInt("port", 25565))
                    );

                    int memory = data.has("memoryMb")
                            ? data.optInt("memoryMb", 2048)
                            : data.optInt("memoryMB", 2048);

                    ramInput.setText(
                            String.valueOf(memory)
                    );
                });

            } catch (Exception error) {
                runOnUiThread(() ->
                        showError(error.getMessage())
                );
            }
        });
    }

    private void saveConfig() {

        String version = versionInput.getText().toString().trim();
        String portText = portInput.getText().toString().trim();
        String ramText = ramInput.getText().toString().trim();

        try {
            int port = Integer.parseInt(portText);
            int ram = Integer.parseInt(ramText);

            if (!version.matches("\\d+\\.\\d+(\\.\\d+)?")) {
                throw new Exception("Invalid Paper version.");
            }

            if (port < 1024 || port > 65535) {
                throw new Exception("Invalid server port.");
            }

            if (ram < 1024 || ram > 32768) {
                throw new Exception("Memory must be 1024–32768 MB.");
            }

            JSONObject body = new JSONObject();

            body.put("version", version);
            body.put("port", port);
            body.put("memoryMb", ram);

            executor.execute(() -> {

                try {
                    request(
                            "/api/config",
                            "POST",
                            body
                    );

                    runOnUiThread(() ->
                            Toast.makeText(
                                    this,
                                    "Configuration saved!",
                                    Toast.LENGTH_SHORT
                            ).show()
                    );

                } catch (Exception error) {

                    runOnUiThread(() ->
                            showError(error.getMessage())
                    );
                }
            });

        } catch (Exception error) {
            showError(error.getMessage());
        }
    }


    // SERVER CONTROLS

    private void startServer() {
        serverAction("/api/start", "Starting server...");
    }

    private void stopServer() {
        serverAction("/api/stop", "Stopping server...");
    }

    private void serverAction(String endpoint, String message) {

        executor.execute(() -> {

            try {
                request(
                        endpoint,
                        "POST",
                        new JSONObject()
                );

                runOnUiThread(() -> {
                    Toast.makeText(
                            this,
                            message,
                            Toast.LENGTH_SHORT
                    ).show();
                });

                updateStatus();

            } catch (Exception error) {

                runOnUiThread(() ->
                        showError(error.getMessage())
                );
            }
        });
    }


    // COMMANDS

    private void sendCommand() {

        String command = commandInput.getText().toString().trim();

        if (command.isEmpty()) return;

        if (command.length() > 300 ||
                command.contains("\n") ||
                command.contains("\r")) {

            showError("Invalid command.");
            return;
        }

        JSONObject body = new JSONObject();

        try {
            body.put("command", command);
        } catch (Exception ignored) {
            return;
        }

        executor.execute(() -> {

            try {
                request(
                        "/api/command",
                        "POST",
                        body
                );

                runOnUiThread(() -> {
                    appendConsole("> " + command);
                    commandInput.setText("");
                });

            } catch (Exception error) {

                runOnUiThread(() ->
                        showError(error.getMessage())
                );
            }
        });
    }


    // CONSOLE

    private void updateConsole() {

        executor.execute(() -> {

            try {
                JSONObject data = request(
                        "/api/console",
                        "GET",
                        null
                );

                StringBuilder logs = new StringBuilder();

                if (data.has("lines")) {

                    JSONArray lines = data.getJSONArray("lines");

                    for (int i = 0; i < lines.length(); i++) {
                        logs.append(lines.optString(i)).append("\n");
                    }

                } else if (data.has("console")) {

                    logs.append(data.optString("console"));
                }

                runOnUiThread(() -> {
                    consoleOutput.setText(logs.toString());
                });

            } catch (Exception ignored) {
                // Console refresh can retry on the next status update.
            }
        });
    }

    private void appendConsole(String message) {
        consoleOutput.append("\n" + message);
    }


    // HELPERS

    private void showError(String message) {

        Toast.makeText(
                this,
                message == null ? "Unknown error" : message,
                Toast.LENGTH_LONG
        ).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        executor.shutdownNow();
    }
}
