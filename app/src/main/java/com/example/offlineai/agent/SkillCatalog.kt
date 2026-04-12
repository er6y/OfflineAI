package com.example.offlineai.agent

import android.content.Context
import com.example.offlineai.ConfigManager
import com.example.offlineai.LogManager
import java.io.File

/**
 * Scans skills directory and caches a compact skill catalog string
 * for injection into Agent Step 0 prompt.
 *
 * Each SKILL.md frontmatter is expected to have:
 *   ---
 *   name: <skill-name>
 *   description: "<concise description>"
 *   ---
 *
 * The catalog is built once at task start and reused.
 */
object SkillCatalog {

    private const val TAG = "SkillCatalog"
    private const val SKILL_FILE = "SKILL.md"

    data class SkillEntry(
        val name: String,
        val description: String,
        val skillMdPath: String
    )

    @Volatile
    private var cachedEntries: List<SkillEntry> = emptyList()

    @Volatile
    private var cachedCatalogText: String = ""

    /**
     * Scan skills directory under data root and build catalog.
     * Call this at app start or before agent task starts.
     * Safe to call multiple times; will re-scan each time.
     */
    fun scan(context: Context) {
        val skillsPath = ConfigManager.getSkillsPath(context)
        val skillsDir = File(skillsPath)
        if (!skillsDir.isDirectory) {
            LogManager.logW(TAG, "[SKILL_SCAN] Skills dir not found: $skillsPath")
            cachedEntries = emptyList()
            cachedCatalogText = ""
            return
        }

        val entries = mutableListOf<SkillEntry>()
        val subDirs = skillsDir.listFiles { f -> f.isDirectory } ?: emptyArray()

        for (dir in subDirs) {
            val skillMd = File(dir, SKILL_FILE)
            if (!skillMd.isFile) {
                LogManager.logD(TAG, "[SKILL_SCAN] No SKILL.md in ${dir.name}, skip")
                continue
            }
            val entry = parseSkillMd(skillMd)
            if (entry != null) {
                entries.add(entry)
                LogManager.logD(TAG, "[SKILL_SCAN] Found: ${entry.name} -> ${entry.description}")
            }
        }

        cachedEntries = entries
        cachedCatalogText = buildCatalogText(entries)
        LogManager.logI(TAG, "[SKILL_SCAN] Scanned ${entries.size} skill(s), catalog ${cachedCatalogText.length} chars")
    }

    /**
     * Get the cached catalog text for prompt injection.
     * Returns empty string if no skills found.
     */
    fun getCatalogText(): String = cachedCatalogText

    /**
     * Get the list of scanned skill entries.
     */
    fun getEntries(): List<SkillEntry> = cachedEntries

    /**
     * Parse SKILL.md frontmatter to extract name and description.
     * Only reads the YAML block between first pair of "---" lines.
     */
    private fun parseSkillMd(file: File): SkillEntry? {
        return try {
            val lines = file.readLines(Charsets.UTF_8)
            var inFrontmatter = false
            var name: String? = null
            var description: String? = null

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed == "---") {
                    if (!inFrontmatter) {
                        inFrontmatter = true
                        continue
                    } else {
                        break // end of frontmatter
                    }
                }
                if (!inFrontmatter) continue

                // Parse "name: xxx" or 'name: "xxx"'
                if (trimmed.startsWith("name:")) {
                    name = extractYamlValue(trimmed.substringAfter("name:"))
                } else if (trimmed.startsWith("description:")) {
                    description = extractYamlValue(trimmed.substringAfter("description:"))
                }
            }

            if (name.isNullOrBlank()) {
                // Fallback: use directory name as skill name
                name = file.parentFile?.name ?: return null
            }
            if (description.isNullOrBlank()) {
                description = name // Fallback: use name as description
            }

            SkillEntry(
                name = name,
                description = description,
                skillMdPath = file.absolutePath
            )
        } catch (e: Exception) {
            LogManager.logW(TAG, "[SKILL_SCAN] Failed to parse ${file.absolutePath}: ${e.message}")
            null
        }
    }

    /**
     * Extract value from YAML line, stripping quotes.
     * e.g. ' "some text"' -> 'some text'
     */
    private fun extractYamlValue(raw: String): String {
        var v = raw.trim()
        // Strip surrounding quotes (single or double)
        if ((v.startsWith("\"") && v.endsWith("\"")) ||
            (v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length - 1)
        }
        return v.trim()
    }

    /**
     * Build compact catalog text for prompt injection.
     */
    private fun buildCatalogText(entries: List<SkillEntry>): String {
        if (entries.isEmpty()) return ""
        val sb = StringBuilder()
        for (entry in entries) {
            sb.append("- ${entry.name}: ${entry.description} [${entry.skillMdPath}]\n")
        }
        return sb.toString().trimEnd()
    }
}
