package com.example.starlocalrag;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * 文件工具类，提供文件读写操作
 */
public class FileUtil {
    private static final String TAG = "FileUtil";

    /**
     * 读取文件内容
     * @param file 文件对象
     * @return 文件内容字符串，如果读取失败则返回空字符串
     */
    public static String readFile(File file) {
        if (file == null || !file.exists() || !file.canRead()) {
            LogManager.logE(TAG, "文件不存在或无法读取: " + (file != null ? file.getAbsolutePath() : "null"));
            return "";
        }

        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            return content.toString();
        } catch (IOException e) {
            LogManager.logE(TAG, "读取文件失败: " + file.getAbsolutePath(), e);
            return "";
        }
    }

    /**
     * 写入内容到文件
     * @param file 文件对象
     * @param content 要写入的内容
     * @return 是否写入成功
     */
    public static boolean writeFile(File file, String content) {
        if (file == null) {
            LogManager.logE(TAG, "文件对象为空");
            return false;
        }

        // 确保父目录存在
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            boolean created = parentDir.mkdirs();
            if (!created) {
                LogManager.logE(TAG, "无法创建父目录: " + parentDir.getAbsolutePath());
                return false;
            }
        }

        try (FileWriter writer = new FileWriter(file)) {
            writer.write(content);
            return true;
        } catch (IOException e) {
            LogManager.logE(TAG, "写入文件失败: " + file.getAbsolutePath(), e);
            return false;
        }
    }

    /**
     * 删除文件
     * @param file 文件对象
     * @return 是否删除成功
     */
    public static boolean deleteFile(File file) {
        if (file == null || !file.exists()) {
            return false;
        }
        return file.delete();
    }

    /**
     * 检查文件是否存在
     * @param filePath 文件路径
     * @return 文件是否存在
     */
    public static boolean fileExists(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return false;
        }
        File file = new File(filePath);
        return file.exists() && file.isFile();
    }
}
