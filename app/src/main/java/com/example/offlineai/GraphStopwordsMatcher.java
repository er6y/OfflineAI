package com.example.offlineai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Graph stopwords matcher for knowledge graph entities.
 * Supports exact, prefix and regex rules loaded from a UTF-8 JSON file.
 */
public class GraphStopwordsMatcher {
    private static final String TAG = "OfflineAI_Stopwords";

    private final Set<String> exactSet;
    private final List<String> prefixList;
    private final List<Pattern> regexList;

    private GraphStopwordsMatcher(Set<String> exactSet, List<String> prefixList, List<Pattern> regexList) {
        this.exactSet = exactSet;
        this.prefixList = prefixList;
        this.regexList = regexList;
    }

    /**
     * Load stopwords rules from a UTF-8 JSON file.
     * Expected keys: "exact" (array), "prefix" (array), "regex" (array).
     */
    public static GraphStopwordsMatcher loadFromFile(String filePath) throws Exception {
        if (filePath == null || filePath.isEmpty()) {
            throw new IllegalArgumentException("Stopwords file path is empty");
        }
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("Stopwords file does not exist: " + filePath);
        }

        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
        }

        JSONObject root = new JSONObject(content.toString());

        Set<String> exact = new HashSet<>();
        List<String> prefixes = new ArrayList<>();
        List<Pattern> regexes = new ArrayList<>();

        loadStringArray(root, "exact", exact);
        loadStringArray(root, "prefix", prefixes);
        loadRegexArray(root, "regex", regexes);

        LogManager.logD(TAG, String.format(
                "[STOPWORDS] Parsed stopwords file: %s (exact=%d, prefix=%d, regex=%d)",
                filePath, exact.size(), prefixes.size(), regexes.size()));

        if (exact.isEmpty() && prefixes.isEmpty() && regexes.isEmpty()) {
            LogManager.logW(TAG, "[STOPWORDS] Stopwords file contains no rules");
        }

        return new GraphStopwordsMatcher(exact, prefixes, regexes);
    }

    private static void loadStringArray(JSONObject root, String key, Set<String> target) {
        if (!root.has(key)) {
            return;
        }
        JSONArray arr = root.optJSONArray(key);
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            String value = arr.optString(i, null);
            if (value != null && !value.isEmpty()) {
                target.add(value);
            }
        }
    }

    private static void loadStringArray(JSONObject root, String key, List<String> target) {
        if (!root.has(key)) {
            return;
        }
        JSONArray arr = root.optJSONArray(key);
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            String value = arr.optString(i, null);
            if (value != null && !value.isEmpty()) {
                target.add(value);
            }
        }
    }

    private static void loadRegexArray(JSONObject root, String key, List<Pattern> target) {
        if (!root.has(key)) {
            return;
        }
        JSONArray arr = root.optJSONArray(key);
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            String value = arr.optString(i, null);
            if (value == null || value.isEmpty()) {
                continue;
            }
            try {
                Pattern pattern = Pattern.compile(value);
                target.add(pattern);
            } catch (PatternSyntaxException e) {
                LogManager.logW(TAG, "[STOPWORDS] Invalid regex pattern: " + value);
            }
        }
    }

    /**
     * Check whether the given entity text should be filtered out by stopwords.
     */
    public boolean matches(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        if (exactSet.contains(text)) {
            return true;
        }

        for (String prefix : prefixList) {
            if (!prefix.isEmpty() && text.startsWith(prefix)) {
                return true;
            }
        }

        for (Pattern pattern : regexList) {
            if (pattern.matcher(text).matches()) {
                return true;
            }
        }

        return false;
    }

    public boolean isEmpty() {
        return exactSet.isEmpty() && prefixList.isEmpty() && regexList.isEmpty();
    }

    public int getExactCount() {
        return exactSet.size();
    }

    public int getPrefixCount() {
        return prefixList.size();
    }

    public int getRegexCount() {
        return regexList.size();
    }
}
