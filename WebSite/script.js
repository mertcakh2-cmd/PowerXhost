"use strict";

/*
 * POWERXHOST WEBSITE
 * Backend API:
 *
 * GET  /api/status
 * GET  /api/config
 * POST /api/config
 * GET  /api/console
 * POST /api/start
 * POST /api/stop
 * POST /api/command
 */

const $ = (id) => document.getElementById(id);

const elements = {
    host: $("backendUrl"),
    token: $("accessToken"),

    connect: $("connectButton"),
    connectionMessage: $("connectionMessage"),

    status: $("statusBadge"),
    sidebarStatus: $("sidebarStatus"),
    sidebarDot: $("sidebarDot"),

    cpu: $("cpuValue"),
    memory: $("memoryValue"),
    serverState: $("serverState"),
    serverDescription: $("serverDescription"),
    serverPort: $("serverPort"),

    version: $("paperVersion"),
    port: $("serverPortInput"),
    ram: $("memoryInput"),

    save: $("saveConfig"),
    configMessage: $("configMessage"),

    start: $("startButton"),
    start2: $("startButton2"),
    stop: $("stopButton"),
    stop2: $("stopButton2"),

    console: $("consoleOutput"),
    command: $("commandInput"),
    send: $("sendCommand"),

    refresh: $("refreshButton"),
    clear: $("clearConsole"),

    toast: $("toast")
};

let connected = false;
let pollTimer = null;
let consoleTimer = null;
let requestInProgress = false;


/* STORAGE */

function loadSavedConnection() {
    elements.host.value =
        localStorage.getItem("pxh_host") || "";

    elements.token.value =
        localStorage.getItem("pxh_token") || "";
}


/* HELPERS */

function getBaseUrl() {
    return elements.host.value.trim().replace(/\/+$/, "");
}

function showToast(message) {
    elements.toast.textContent = message;
    elements.toast.classList.add("show");

    setTimeout(() => {
        elements.toast.classList.remove("show");
    }, 2800);
}

function setConnectionState(state, message) {
    connected = state;

    elements.connectionMessage.textContent = message;
    elements.sidebarStatus.textContent =
        state ? "Connected" : "Disconnected";

    elements.sidebarDot.classList.toggle("online", state);

    if (!state) {
        elements.status.className = "server-badge offline";
        elements.status.innerHTML = "<span></span> OFFLINE";
    }
}

function appendConsole(message) {
    const current = elements.console.textContent;

    elements.console.textContent =
        current + "\n" + message;

    elements.console.scrollTop =
        elements.console.scrollHeight;
}

async function api(path, options = {}) {
    const base = getBaseUrl();

    if (!base) {
        throw new Error("Enter a backend address first.");
    }

    const headers = {
        "Content-Type": "application/json"
    };

    const token = elements.token.value.trim();

    if (token) {
        headers.Authorization = "Bearer " + token;
    }

    const response = await fetch(base + path, {
        ...options,
        headers: {
            ...headers,
            ...(options.headers || {})
        }
    });

    const text = await response.text();

    let data = {};

    if (text) {
        try {
            data = JSON.parse(text);
        } catch {
            data = { message: text };
        }
    }

    if (!response.ok) {
        throw new Error(
            data.error ||
            data.message ||
            "HTTP error " + response.status
        );
    }

    return data;
}


/* CONNECTION */

async function connectBackend() {
    if (requestInProgress) return;

    requestInProgress = true;
    elements.connect.disabled = true;
    elements.connect.textContent = "Connecting...";

    try {
        await api("/api/status");

        localStorage.setItem("pxh_host", getBaseUrl());
        localStorage.setItem(
            "pxh_token",
            elements.token.value.trim()
        );

        setConnectionState(true, "Connected successfully.");

        showToast("Connected to PowerXhost.");

        await loadConfig();
        await updateStatus();
        await updateConsole();

        startPolling();

    } catch (error) {
        setConnectionState(false, error.message);
        showToast("Connection failed: " + error.message);
    } finally {
        requestInProgress = false;
        elements.connect.disabled = false;
        elements.connect.textContent = "Connect";
    }
}


/* CONFIGURATION */

async function loadConfig() {
    try {
        const data = await api("/api/config");

        if (data.version) {
            elements.version.value = data.version;
        }

        if (data.port) {
            elements.port.value = data.port;
        }

        if (data.memoryMb) {
            elements.ram.value = data.memoryMb;
        }

        if (data.memoryMB) {
            elements.ram.value = data.memoryMB;
        }

        elements.configMessage.textContent =
            "Configuration loaded.";

    } catch (error) {
        elements.configMessage.textContent =
            "Could not load configuration: " + error.message;
    }
}

async function saveConfig() {
    const version = elements.version.value.trim();
    const port = Number(elements.port.value);
    const memoryMb = Number(elements.ram.value);

    if (!/^\d+\.\d+(?:\.\d+)?$/.test(version)) {
        showToast("Invalid Paper version.");
        return;
    }

    if (!Number.isInteger(port) || port < 1024 || port > 65535) {
        showToast("Port must be between 1024 and 65535.");
        return;
    }

    if (!Number.isInteger(memoryMb) ||
        memoryMb < 1024 ||
        memoryMb > 32768) {
        showToast("Memory must be between 1024 and 32768 MB.");
        return;
    }

    try {
        await api("/api/config", {
            method: "POST",
            body: JSON.stringify({
                version,
                port,
                memoryMb
            })
        });

        elements.serverPort.textContent = port;
        elements.configMessage.textContent =
            "Configuration saved successfully.";

        showToast("Configuration saved.");

    } catch (error) {
        showToast("Save failed: " + error.message);
    }
}


/* SERVER STATUS */

async function updateStatus() {
    try {
        const data = await api("/api/status");

        const online = Boolean(data.online);

        elements.status.className =
            "server-badge " + (online ? "online" : "offline");

        elements.status.innerHTML =
            "<span></span>" + (online ? "ONLINE" : "OFFLINE");

        elements.serverState.textContent =
            online ? "Online" : "Offline";

        elements.serverDescription.textContent =
            online ? "Server is running" : "Server is stopped";

        if (data.cores !== undefined) {
            elements.cpu.textContent = data.cores;
        }

        if (data.memoryUsed !== undefined &&
            data.memoryMax !== undefined) {
            elements.memory.textContent =
                data.memoryUsed + " / " + data.memoryMax + " MB";
        } else if (data.memoryMax !== undefined) {
            elements.memory.textContent =
                data.memoryMax + " MB";
        }

        if (data.port !== undefined) {
            elements.serverPort.textContent = data.port;
        }

    } catch (error) {
        setConnectionState(false, error.message);
    }
}


/* SERVER CONTROLS */

async function startServer() {
    try {
        await api("/api/start", {
            method: "POST",
            body: "{}"
        });

        showToast("Start request sent.");
        appendConsole("[PowerXhost] Starting server...");

        setTimeout(updateStatus, 1500);

    } catch (error) {
        showToast("Start failed: " + error.message);
    }
}

async function stopServer() {
    try {
        await api("/api/stop", {
            method: "POST",
            body: "{}"
        });

        showToast("Stop request sent.");
        appendConsole("[PowerXhost] Stopping server...");

        setTimeout(updateStatus, 1500);

    } catch (error) {
        showToast("Stop failed: " + error.message);
    }
}


/* CONSOLE */

async function updateConsole() {
    try {
        const data = await api("/api/console");

        if (Array.isArray(data.lines)) {
            elements.console.textContent =
                data.lines.join("\n");
        } else if (typeof data.console === "string") {
            elements.console.textContent = data.console;
        }

        elements.console.scrollTop =
            elements.console.scrollHeight;

    } catch (error) {
        // Avoid repeatedly showing polling errors in the console.
    }
}

async function sendCommand() {
    const command = elements.command.value.trim();

    if (!command) return;

    if (command.length > 300 ||
        command.includes("\n") ||
        command.includes("\r")) {
        showToast("Invalid command.");
        return;
    }

    try {
        await api("/api/command", {
            method: "POST",
            body: JSON.stringify({ command })
        });

        appendConsole("> " + command);

        elements.command.value = "";

        await updateConsole();

    } catch (error) {
        showToast("Command failed: " + error.message);
    }
}


/* POLLING */

function startPolling() {
    stopPolling();

    pollTimer = setInterval(() => {
        if (connected) updateStatus();
    }, 5000);

    consoleTimer = setInterval(() => {
        if (connected) updateConsole();
    }, 3000);
}

function stopPolling() {
    if (pollTimer) clearInterval(pollTimer);
    if (consoleTimer) clearInterval(consoleTimer);

    pollTimer = null;
    consoleTimer = null;
}


/* NAVIGATION */

document.querySelectorAll(".nav-item").forEach(item => {
    item.addEventListener("click", () => {
        document.querySelectorAll(".nav-item").forEach(nav => {
            nav.classList.remove("active");
        });

        item.classList.add("active");
    });
});


/* EVENTS */

elements.connect.addEventListener("click", connectBackend);

elements.save.addEventListener("click", saveConfig);

elements.start.addEventListener("click", startServer);
elements.start2.addEventListener("click", startServer);

elements.stop.addEventListener("click", stopServer);
elements.stop2.addEventListener("click", stopServer);

elements.send.addEventListener("click", sendCommand);

elements.command.addEventListener("keydown", event => {
    if (event.key === "Enter") sendCommand();
});

elements.refresh.addEventListener("click", async () => {
    if (!connected) {
        showToast("Connect to your backend first.");
        return;
    }

    await updateStatus();
    await updateConsole();

    showToast("Dashboard refreshed.");
});

elements.clear.addEventListener("click", () => {
    elements.console.textContent = "Console display cleared.";
});

window.addEventListener("beforeunload", stopPolling);


/* INITIALIZATION */

loadSavedConnection();
