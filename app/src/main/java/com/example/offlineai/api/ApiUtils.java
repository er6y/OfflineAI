package com.example.offlineai.api;

import android.content.Context;

import com.example.offlineai.ConfigManager;
import com.example.offlineai.LogManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Shared utility methods for API operations.
 * Eliminates duplicate code between StreamingApiClient and LlmApiAdapter.
 */
public class ApiUtils {
    private static final String TAG = "ApiUtils";

    /**
     * Encode image file to Base64 string.
     * @param imagePath Path to image file
     * @return Base64 encoded string, or null if failed
     */
    public static String encodeImageToBase64(String imagePath) {
        try {
            File imageFile = new File(imagePath);
            if (!imageFile.exists()) {
                LogManager.logE(TAG, "Image file not found: " + imagePath);
                return null;
            }

            FileInputStream fis = new FileInputStream(imageFile);
            byte[] imageBytes = new byte[(int) imageFile.length()];
            fis.read(imageBytes);
            fis.close();

            LogManager.logD(TAG, "Encoded image to Base64: " + imageFile.length() + " bytes");
            return android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to encode image to Base64: " + imagePath, e);
            return null;
        }
    }

    /**
     * Download image from URL and save to current chat history folder.
     * @param context Android context
     * @param imageUrl URL to download from
     * @param client OkHttpClient to use
     * @return Absolute path of saved image file, or null if failed
     */
    public static String downloadAndSaveImage(Context context, String imageUrl, okhttp3.OkHttpClient client) {
        try {
            okhttp3.Request request = new okhttp3.Request.Builder().url(imageUrl).build();
            okhttp3.Response response = client.newCall(request).execute();

            if (!response.isSuccessful()) {
                LogManager.logE(TAG, "Failed to download image: " + response.code());
                return null;
            }

            byte[] imageBytes = response.body().bytes();
            LogManager.logI(TAG, "Downloaded image: " + imageBytes.length + " bytes");

            String chatFolder = ConfigManager.getString(context, ConfigManager.KEY_CURRENT_CHAT_FOLDER, "");
            if (chatFolder.isEmpty()) {
                LogManager.logW(TAG, "No chat folder set, cannot save image");
                return null;
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String filename = "generated_image_" + timestamp + ".jpg";
            File imageFile = new File(chatFolder, filename);

            FileOutputStream fos = new FileOutputStream(imageFile);
            fos.write(imageBytes);
            fos.close();

            LogManager.logI(TAG, "Image saved: " + imageFile.getAbsolutePath());
            return imageFile.getAbsolutePath();
        } catch (Exception e) {
            LogManager.logE(TAG, "Failed to download/save image: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Extract base URL from user-configured API URL.
     * Strips common suffixes like /compatible-mode/v1, /v1/chat/completions, etc.
     * @param apiUrl User-configured API URL
     * @return Clean base URL without trailing slash
     */
    public static String extractBaseUrl(String apiUrl) {
        String url = apiUrl;
        // Strip known path suffixes
        String[] suffixes = {
            "/compatible-mode/v1",
            "/v1/chat/completions",
            "/v1/images/generations",
            "/chat/completions",
            "/api/generate"
        };
        for (String suffix : suffixes) {
            if (url.contains(suffix)) {
                url = url.substring(0, url.indexOf(suffix));
            }
        }
        // Remove trailing slash
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
