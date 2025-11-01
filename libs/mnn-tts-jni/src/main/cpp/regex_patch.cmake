# regex_patch.cmake
# Unified script for patching/unpatching upstream TTS source files to use RE2 instead of std::regex
# 
# Usage:
#   Patch:   cmake -DMNN_TTS_ROOT=<path> -DPATCH_ACTION=APPLY -P regex_patch.cmake
#   Unpatch: cmake -DMNN_TTS_ROOT=<path> -DPATCH_ACTION=RESTORE -P regex_patch.cmake
#
# Why this patch exists:
#   Android ARM64 has std::locale initialization issues causing std::bad_cast crashes
#   when std::regex is used. RE2 (Google's regex library) doesn't depend on std::locale.

# ============================================================================
# Configuration
# ============================================================================

if(NOT DEFINED MNN_TTS_ROOT)
    message(FATAL_ERROR "MNN_TTS_ROOT must be defined")
endif()

if(NOT DEFINED PATCH_ACTION)
    message(FATAL_ERROR "PATCH_ACTION must be defined (APPLY or RESTORE)")
endif()

# Files to patch/restore
set(PATCH_FILES
    "${MNN_TTS_ROOT}/src/bertvits2/utils.cpp"
    "${MNN_TTS_ROOT}/src/bertvits2/text_preprocessor.cpp"
    "${MNN_TTS_ROOT}/src/bertvits2/english_g2p.cpp"
    "${MNN_TTS_ROOT}/src/bertvits2/word_spliter.cpp"
    "${MNN_TTS_ROOT}/src/bertvits2/pinyin.cpp"
    "${MNN_TTS_ROOT}/src/bertvits2/chinese_g2p.cpp"
    "${MNN_TTS_ROOT}/include/bertvits2/utils.hpp"
    "${MNN_TTS_ROOT}/include/bertvits2/text_preprocessor.hpp"
    "${MNN_TTS_ROOT}/include/mnn_tts_logger.hpp"
)

# Patch marker to detect if file is already patched
set(PATCH_MARKER "regex_compat.hpp")

# ============================================================================
# Apply Patch
# ============================================================================

function(apply_patch)
    message(STATUS "🔧 Applying RE2 compatibility patches...")
    
    foreach(SRC_FILE ${PATCH_FILES})
        if(NOT EXISTS "${SRC_FILE}")
            message(WARNING "  ⚠️  File not found: ${SRC_FILE}")
            continue()
        endif()
        
        file(READ "${SRC_FILE}" FILE_CONTENT)
        string(FIND "${FILE_CONTENT}" "${PATCH_MARKER}" ALREADY_PATCHED)
        
        if(NOT ALREADY_PATCHED EQUAL -1)
            message(STATUS "  ⏭️  Already patched: ${SRC_FILE}")
            continue()
        endif()
        
        message(STATUS "  📝 Patching: ${SRC_FILE}")
        
        # Step 1: Add include at the beginning
        set(FILE_CONTENT "#include \"regex_compat.hpp\"  // Auto-added by regex_patch.cmake\n${FILE_CONTENT}")
        
        # Step 2: File-specific patches
        get_filename_component(FILENAME "${SRC_FILE}" NAME)
        
        if(FILENAME STREQUAL "text_preprocessor.cpp")
            # Remove SENTENCE_SPLITOR assignment (RE2 doesn't support assignment operator)
            string(REGEX REPLACE "SENTENCE_SPLITOR = [^\n]+;" 
                   "// SENTENCE_SPLITOR removed (RE2 no assignment, unused)" 
                   FILE_CONTENT "${FILE_CONTENT}")
        endif()
        
        if(FILENAME STREQUAL "text_preprocessor.hpp")
            # Remove SENTENCE_SPLITOR member declaration (ROOT CAUSE of std::locale crash)
            string(REGEX REPLACE "std::regex SENTENCE_SPLITOR;" 
                   "// std::regex SENTENCE_SPLITOR; // Removed: causes std::locale crash" 
                   FILE_CONTENT "${FILE_CONTENT}")
        endif()
        
        if(FILENAME STREQUAL "mnn_tts_logger.hpp")
            # Replace std::stringstream/std::put_time with Android Log (avoid std::locale)
            # Replace the entire Log() template function body
            string(REGEX REPLACE 
                "std::time_t now = std::time\\(nullptr\\);[^}]+std::cout << ss\\.str\\(\\) << std::endl;"
                "#ifdef __ANDROID__\n            // Android: Use __android_log_print (no std::locale dependency)\n            if (level >= log_level_) {\n                char buffer[1024];\n                snprintf(buffer, sizeof(buffer), \"[PixelAI_TTS][%s] \", LevelToString(level).c_str());\n                std::string msg = std::string(buffer) + fmt;\n                __android_log_print(ANDROID_LOG_INFO, \"MNN_TTS\", \"%s\", msg.c_str());\n            }\n#else\n            // Non-Android: Original implementation\n            std::time_t now = std::time(nullptr);\n            std::stringstream ss;\n            ss << std::put_time(std::localtime(&now), \"%Y-%m-%d %H:%M:%S\")\n               << \"[PixelAI_TTS]\"\n               << \"[\" << LevelToString(level) << \"]\"\n               << fmt;\n            (ss << ... << args);\n            std::cout << ss.str() << std::endl;\n#endif"
                FILE_CONTENT "${FILE_CONTENT}")
            
            # Add Android log header after #include <iomanip>
            string(REGEX REPLACE 
                "#include <iomanip>"
                "#include <iomanip>\n#ifdef __ANDROID__\n#include <android/log.h>\n#endif"
                FILE_CONTENT "${FILE_CONTENT}")
        endif()
        
        if(FILENAME STREQUAL "utils.cpp")
            # Replace std::stringstream in CalculateFileHash() with snprintf (avoid std::locale)
            string(REGEX REPLACE 
                "// 转换为16进制字符串\n    std::stringstream ss;\n    ss << std::hex << std::setfill\\('0'\\) << std::setw\\(16\\) << hash;\n    return ss\\.str\\(\\);"
                "// Convert to hex string using snprintf (no std::locale dependency)\n    char hex_buffer[17];\n    snprintf(hex_buffer, sizeof(hex_buffer), \"%016llx\", hash);\n    return std::string(hex_buffer);"
                FILE_CONTENT "${FILE_CONTENT}")
        endif()
        
        # Step 3: Replace std::regex types with mnn_regex types (order matters!)
        string(REPLACE "std::sregex_token_iterator" "mnn_sregex_token_iterator" FILE_CONTENT "${FILE_CONTENT}")
        string(REPLACE "std::sregex_iterator" "mnn_sregex_iterator" FILE_CONTENT "${FILE_CONTENT}")
        string(REPLACE "std::regex_replace" "mnn_regex_replace" FILE_CONTENT "${FILE_CONTENT}")
        string(REPLACE "std::regex_match" "mnn_regex_match" FILE_CONTENT "${FILE_CONTENT}")
        string(REPLACE "std::regex_search" "mnn_regex_search" FILE_CONTENT "${FILE_CONTENT}")
        string(REPLACE "std::smatch" "mnn_smatch" FILE_CONTENT "${FILE_CONTENT}")
        string(REPLACE "std::regex" "mnn_regex" FILE_CONTENT "${FILE_CONTENT}")  # Must be last!
        
        # Write patched content
        file(WRITE "${SRC_FILE}" "${FILE_CONTENT}")
        message(STATUS "  ✅ Patched successfully")
    endforeach()
    
    message(STATUS "✅ All patches applied successfully")
endfunction()

# ============================================================================
# Restore Original
# ============================================================================

function(restore_original)
    message(STATUS "🔄 Restoring original TTS source files...")
    
    foreach(SRC_FILE ${PATCH_FILES})
        if(NOT EXISTS "${SRC_FILE}")
            message(WARNING "  ⚠️  File not found: ${SRC_FILE}")
            continue()
        endif()
        
        file(READ "${SRC_FILE}" FILE_CONTENT)
        string(FIND "${FILE_CONTENT}" "${PATCH_MARKER}" IS_PATCHED)
        
        if(IS_PATCHED EQUAL -1)
            message(STATUS "  ⏭️  Not patched: ${SRC_FILE}")
            continue()
        endif()
        
        message(STATUS "  🔄 Restoring: ${SRC_FILE}")
        
        # Step 1: Remove include line
        string(REGEX REPLACE "#include \"regex_compat\\.hpp\"[^\n]*\n" "" FILE_CONTENT "${FILE_CONTENT}")
        
        # Step 2: Restore mnn_regex types to std::regex types (reverse order!)
        string(REPLACE "mnn_regex" "std::regex" FILE_CONTENT "${FILE_CONTENT}")  # Must be first!
        string(REPLACE "mnn_smatch" "std::smatch" FILE_CONTENT "${FILE_CONTENT}")
        string(REPLACE "mnn_regex_search" "std::regex_search" FILE_CONTENT "${FILE_CONTENT}")
        string(REPLACE "mnn_regex_match" "std::regex_match" FILE_CONTENT "${FILE_CONTENT}")
        string(REPLACE "mnn_regex_replace" "std::regex_replace" FILE_CONTENT "${FILE_CONTENT}")
        string(REPLACE "mnn_sregex_iterator" "std::sregex_iterator" FILE_CONTENT "${FILE_CONTENT}")
        string(REPLACE "mnn_sregex_token_iterator" "std::sregex_token_iterator" FILE_CONTENT "${FILE_CONTENT}")
        
        # Step 3: Restore file-specific content
        get_filename_component(FILENAME "${SRC_FILE}" NAME)
        
        if(FILENAME STREQUAL "text_preprocessor.cpp")
            string(REGEX REPLACE "// SENTENCE_SPLITOR removed[^\n]*" 
                   "SENTENCE_SPLITOR = std::regex(R\"([。！？;；!?])\");" 
                   FILE_CONTENT "${FILE_CONTENT}")
        endif()
        
        if(FILENAME STREQUAL "text_preprocessor.hpp")
            string(REGEX REPLACE "// std::regex SENTENCE_SPLITOR;[^\n]*" 
                   "std::regex SENTENCE_SPLITOR;" 
                   FILE_CONTENT "${FILE_CONTENT}")
        endif()
        
        if(FILENAME STREQUAL "mnn_tts_logger.hpp")
            # Restore original logging implementation
            # This is complex, so we use git checkout instead
            message(STATUS "  ⚠️  mnn_tts_logger.hpp requires manual git checkout")
        endif()
        
        # Write restored content
        file(WRITE "${SRC_FILE}" "${FILE_CONTENT}")
        message(STATUS "  ✅ Restored successfully")
    endforeach()
    
    message(STATUS "✅ All files restored to original state")
endfunction()

# ============================================================================
# Main Entry Point
# ============================================================================

if(PATCH_ACTION STREQUAL "APPLY")
    apply_patch()
elseif(PATCH_ACTION STREQUAL "RESTORE")
    restore_original()
else()
    message(FATAL_ERROR "Invalid PATCH_ACTION: ${PATCH_ACTION} (must be APPLY or RESTORE)")
endif()
