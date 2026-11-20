package com.powerxhost;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TestRam {

    private TestRam() {}

    public static Map<String, Object> snapshot() {
        Runtime runtime = Runtime.getRuntime();

        long used = runtime.totalMemory() - runtime.freeMemory();

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("usedMb", used / 1024 / 1024);
        result.put("maxMb", runtime.maxMemory() / 1024 / 1024);
        result.put("processors", runtime.availableProcessors());

        return result;
    }
}
