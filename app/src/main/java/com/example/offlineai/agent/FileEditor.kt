package com.example.offlineai.agent.utils

import com.example.offlineai.LogManager
import org.mozilla.universalchardet.UniversalDetector
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * File Editor - lightweight file editing utility for Agent
 * Supports encoding auto-detection, line-based operations, in-memory editing
 */
class FileEditor private constructor(
    val filePath: String,
    private var lines: MutableList<String>,
    private val charset: String
) {
    companion object {
        private const val TAG = "FileEditor"

        /**
         * Open file and load into memory with encoding auto-detection.
         * If file doesn't exist, returns error unless createIfMissing=true.
         */
        fun open(filePath: String, createIfMissing: Boolean = false): Map<String, Any?> {
            return try {
                val file = File(filePath)
                
                if (!file.exists()) {
                    if (!createIfMissing) {
                        return mapOf(
                            "success" to false,
                            "total_lines" to 0,
                            "charset" to "",
                            "file_path" to filePath,
                            "error" to "File not found: $filePath"
                        )
                    }

                    file.parentFile?.mkdirs()
                    file.createNewFile()
                    LogManager.logI(TAG, "Created new file: $filePath")
                }
                
                if (!file.isFile) {
                    return mapOf(
                        "success" to false,
                        "total_lines" to 0,
                        "charset" to "",
                        "file_path" to filePath,
                        "error" to "Path is not a file: $filePath"
                    )
                }

                val detectedCharset = detectEncoding(file)
                val lines = readAllLines(file, detectedCharset)

                LogManager.logI(TAG, "Opened file: $filePath, encoding: $detectedCharset, lines: ${lines.size}")

                mapOf(
                    "success" to true,
                    "total_lines" to lines.size,
                    "charset" to detectedCharset,
                    "file_path" to filePath,
                    "error" to null
                )
            } catch (e: Exception) {
                LogManager.logE(TAG, "Failed to open file: $filePath", e)
                mapOf(
                    "success" to false,
                    "total_lines" to 0,
                    "charset" to "",
                    "file_path" to filePath,
                    "error" to "Open failed: ${e.message}"
                )
            }
        }

        /**
         * Create FileEditor instance from opened file
         */
        fun create(filePath: String): FileEditor? {
            return try {
                val file = File(filePath)
                if (!file.exists() || !file.isFile) return null

                val detectedCharset = detectEncoding(file)
                val lines = readAllLines(file, detectedCharset)

                FileEditor(filePath, lines.toMutableList(), detectedCharset)
            } catch (e: Exception) {
                LogManager.logE(TAG, "Failed to create FileEditor: $filePath", e)
                null
            }
        }

        private fun detectEncoding(file: File): String {
            return try {
                val detector = UniversalDetector(null)
                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } > 0 && !detector.isDone()) {
                        detector.handleData(buffer, 0, bytesRead)
                    }
                }
                detector.dataEnd()
                val charset = detector.getDetectedCharset()
                charset ?: "UTF-8"
            } catch (e: Exception) {
                LogManager.logW(TAG, "Encoding detection failed, fallback to UTF-8: ${e.message}")
                "UTF-8"
            }
        }

        private fun readAllLines(file: File, charset: String): List<String> {
            return FileInputStream(file).use { fis ->
                InputStreamReader(fis, charset).use { isr ->
                    BufferedReader(isr).use { reader ->
                        reader.readLines()
                    }
                }
            }
        }
    }

    private val file: File = File(filePath)

    fun getTotalLines(): Int = lines.size

    fun getLines(): List<String> = lines.toList()

    /**
     * Read lines from startLine (1-based) with specified count
     */
    fun readLines(startLine: Int, readCount: Int): Map<String, Any?> {
        return try {
            if (startLine < 1) {
                return mapOf(
                    "success" to false,
                    "total_lines" to lines.size,
                    "read_lines" to emptyList<Map<String, Any>>(),
                    "error" to "startLine must be >= 1"
                )
            }

            val startIndex = startLine - 1
            val endIndex = minOf(startIndex + readCount, lines.size)

            if (startIndex >= lines.size) {
                return mapOf(
                    "success" to true,
                    "total_lines" to lines.size,
                    "read_lines" to emptyList<Map<String, Any>>(),
                    "error" to null
                )
            }

            val result = (startIndex until endIndex).map { index ->
                mapOf(
                    "line" to (index + 1),
                    "content" to lines[index]
                )
            }

            LogManager.logI(TAG, "Read lines $startLine-${endIndex} from $filePath")

            mapOf(
                "success" to true,
                "total_lines" to lines.size,
                "read_lines" to result,
                "error" to null
            )
        } catch (e: Exception) {
            LogManager.logE(TAG, "Failed to read lines from $filePath", e)
            mapOf(
                "success" to false,
                "total_lines" to lines.size,
                "read_lines" to emptyList<Map<String, Any>>(),
                "error" to "Read failed: ${e.message}"
            )
        }
    }

    /**
     * Replace lines from startLine to endLine (inclusive, 1-based) with new content
     */
    fun editReplace(startLine: Int, endLine: Int, newContent: List<String>): Map<String, Any?> {
        return try {
            if (startLine < 1 || endLine < startLine) {
                return mapOf(
                    "success" to false,
                    "new_total_lines" to lines.size,
                    "replaced_range" to "",
                    "error" to "Invalid range: startLine=$startLine, endLine=$endLine"
                )
            }

            val startIndex = startLine - 1
            val endIndex = minOf(endLine, lines.size)

            val newLines = mutableListOf<String>()
            newLines.addAll(lines.subList(0, startIndex))
            newLines.addAll(newContent)
            if (endIndex < lines.size) {
                newLines.addAll(lines.subList(endIndex, lines.size))
            }

            lines = newLines

            val rangeStr = "$startLine-$endLine"
            LogManager.logI(TAG, "Replaced lines $rangeStr in $filePath, new total: ${lines.size}")

            mapOf(
                "success" to true,
                "new_total_lines" to lines.size,
                "replaced_range" to rangeStr,
                "error" to null
            )
        } catch (e: Exception) {
            LogManager.logE(TAG, "Failed to replace lines in $filePath", e)
            mapOf(
                "success" to false,
                "new_total_lines" to lines.size,
                "replaced_range" to "",
                "error" to "Replace failed: ${e.message}"
            )
        }
    }

    /**
     * Search keyword and return matching line numbers
     */
    fun searchKeyword(keyword: String, ignoreCase: Boolean = true): Map<String, Any?> {
        return try {
            if (keyword.isEmpty()) {
                return mapOf(
                    "success" to false,
                    "keyword" to keyword,
                    "match_count" to 0,
                    "match_lines" to emptyList<Int>(),
                    "error" to "Empty keyword"
                )
            }

            val matchLines = lines.mapIndexedNotNull { index, line ->
                if (line.contains(keyword, ignoreCase)) (index + 1) else null
            }

            LogManager.logI(TAG, "Search '$keyword' in $filePath: ${matchLines.size} matches")

            mapOf(
                "success" to true,
                "keyword" to keyword,
                "match_count" to matchLines.size,
                "match_lines" to matchLines,
                "error" to null
            )
        } catch (e: Exception) {
            LogManager.logE(TAG, "Failed to search in $filePath", e)
            mapOf(
                "success" to false,
                "keyword" to keyword,
                "match_count" to 0,
                "match_lines" to emptyList<Int>(),
                "error" to "Search failed: ${e.message}"
            )
        }
    }

    /**
     * Save changes to original file using original encoding
     */
    fun save(): Map<String, Any?> {
        return try {
            FileOutputStream(file).use { fos ->
                OutputStreamWriter(fos, charset).use { osw ->
                    lines.forEachIndexed { index, line ->
                        osw.write(line)
                        if (index < lines.size - 1) {
                            osw.write(System.lineSeparator())
                        }
                    }
                }
            }

            LogManager.logI(TAG, "Saved file: $filePath, lines: ${lines.size}, encoding: $charset")

            mapOf(
                "success" to true,
                "message" to "File saved successfully (${lines.size} lines, $charset)",
                "error" to null
            )
        } catch (e: Exception) {
            LogManager.logE(TAG, "Failed to save file: $filePath", e)
            mapOf(
                "success" to false,
                "message" to "",
                "error" to "Save failed: ${e.message}"
            )
        }
    }
}

/**
 * FileEditor Manager - manages active file editor sessions
 */
object FileEditorManager {
    private const val TAG = "FileEditorManager"
    private val activeEditors = mutableMapOf<String, FileEditor>()

    fun openFile(filePath: String, createIfMissing: Boolean = false): Map<String, Any?> {
        val result = FileEditor.open(filePath, createIfMissing)
        if (result["success"] == true) {
            val editor = FileEditor.create(filePath)
            if (editor != null) {
                activeEditors[filePath] = editor
                LogManager.logI(TAG, "Editor session created for: $filePath")
            }
        }
        return result
    }

    fun getEditor(filePath: String): FileEditor? {
        return activeEditors[filePath]
    }

    fun readLines(filePath: String, startLine: Int, readCount: Int): Map<String, Any?> {
        val editor = activeEditors[filePath]
            ?: return mapOf(
                "success" to false,
                "total_lines" to 0,
                "read_lines" to emptyList<Map<String, Any>>(),
                "error" to "File not opened: $filePath"
            )
        return editor.readLines(startLine, readCount)
    }

    fun editReplace(filePath: String, startLine: Int, endLine: Int, newContent: List<String>): Map<String, Any?> {
        val editor = activeEditors[filePath]
            ?: return mapOf(
                "success" to false,
                "new_total_lines" to 0,
                "replaced_range" to "",
                "error" to "File not opened: $filePath"
            )
        return editor.editReplace(startLine, endLine, newContent)
    }

    fun searchKeyword(filePath: String, keyword: String, ignoreCase: Boolean): Map<String, Any?> {
        val editor = activeEditors[filePath]
            ?: return mapOf(
                "success" to false,
                "keyword" to keyword,
                "match_count" to 0,
                "match_lines" to emptyList<Int>(),
                "error" to "File not opened: $filePath"
            )
        return editor.searchKeyword(keyword, ignoreCase)
    }

    fun saveFile(filePath: String): Map<String, Any?> {
        val editor = activeEditors[filePath]
            ?: return mapOf(
                "success" to false,
                "message" to "",
                "error" to "File not opened: $filePath"
            )
        val result = editor.save()
        if (result["success"] == true) {
            activeEditors.remove(filePath)
            LogManager.logI(TAG, "Editor session closed for: $filePath")
        }
        return result
    }

    fun closeFile(filePath: String) {
        activeEditors.remove(filePath)
        LogManager.logI(TAG, "Editor session removed: $filePath")
    }

    fun clearAll() {
        activeEditors.clear()
        LogManager.logI(TAG, "All editor sessions cleared")
    }
}
