package com.example.offlineai.api;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.List;

/**
 * Cache for API authentication methods.
 * Automatically tries different auth methods and remembers which one works for each API URL.
 */
public class AuthMethodCache {
    private static final String PREFS_NAME = "api_auth_cache";
    private static final String KEY_PREFIX = "auth_method_";
    
    // All supported auth methods - will try in order
    public static final String AUTH_BEARER = "bearer";      // Authorization: Bearer xxx
    public static final String AUTH_API_KEY = "api-key";    // api-key: xxx
    
    public static final List<String> ALL_AUTH_METHODS = Arrays.asList(
            AUTH_BEARER,
            AUTH_API_KEY
    );
    
    /**
     * Get cached auth method for a URL, or null if not cached
     */
    public static String getCachedMethod(Context context, String apiUrl) {
        if (context == null || apiUrl == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String key = KEY_PREFIX + normalizeUrl(apiUrl);
        return prefs.getString(key, null);
    }
    
    /**
     * Save successful auth method for a URL
     */
    public static void cacheMethod(Context context, String apiUrl, String authMethod) {
        if (context == null || apiUrl == null || authMethod == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String key = KEY_PREFIX + normalizeUrl(apiUrl);
        prefs.edit().putString(key, authMethod).apply();
    }
    
    /**
     * Clear cached auth method for a URL (e.g., when auth fails)
     */
    public static void clearCache(Context context, String apiUrl) {
        if (context == null || apiUrl == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String key = KEY_PREFIX + normalizeUrl(apiUrl);
        prefs.edit().remove(key).apply();
    }
    
    /**
     * Apply auth header based on method type
     */
    public static void applyAuthHeader(okhttp3.Request.Builder builder, String apiKey, String authMethod) {
        if (AUTH_API_KEY.equals(authMethod)) {
            builder.header("api-key", apiKey);
        } else {
            // Default to Bearer
            builder.header("Authorization", "Bearer " + apiKey);
        }
    }
    
    /**
     * Get auth header for Volley request
     */
    public static java.util.Map<String, String> getAuthHeaders(String apiKey, String authMethod) {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        if (AUTH_API_KEY.equals(authMethod)) {
            headers.put("api-key", apiKey);
        } else {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        return headers;
    }
    
    /**
     * Normalize URL for cache key (remove trailing slash, lowercase)
     */
    private static String normalizeUrl(String url) {
        if (url == null) return "";
        String normalized = url.toLowerCase().trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        // Replace special chars for SharedPreferences key
        return normalized.replaceAll("[^a-z0-9]", "_");
    }
}
