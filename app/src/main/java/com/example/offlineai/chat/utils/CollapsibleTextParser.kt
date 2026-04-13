// Parser for collapsible text sections in chat messages
// Supports <think>, <debug>, and performance metrics
package com.example.offlineai.chat.utils

import com.example.offlineai.chat.model.ChatDataItem
import java.util.regex.Pattern

object CollapsibleTextParser {
    
    /**
     * Parse text and extract collapsible sections
     * Supports:
     * - <think>...</think> or <thinking>...</thinking>
     * - <debug>...</debug>
     * - Performance metrics patterns
     */
    fun parseAndPopulate(text: String, chatDataItem: ChatDataItem) {
        var remainingText = text
        
        // Extract thinking section
        val thinkingPattern = Pattern.compile("<think(?:ing)?>(.*?)</think(?:ing)?>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val thinkingMatcher = thinkingPattern.matcher(remainingText)
        if (thinkingMatcher.find()) {
            chatDataItem.thinkingText = thinkingMatcher.group(1)?.trim()
            remainingText = thinkingMatcher.replaceAll("")
        }
        
        // Extract agent section
        val agentPattern = Pattern.compile("<agent>(.*?)</agent>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val agentMatcher = agentPattern.matcher(remainingText)
        if (agentMatcher.find()) {
            chatDataItem.agentText = agentMatcher.group(1)?.trim()
            remainingText = agentMatcher.replaceAll("")
        }
        
        // Extract debug section
        val debugPattern = Pattern.compile("<debug>(.*?)</debug>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val debugMatcher = debugPattern.matcher(remainingText)
        if (debugMatcher.find()) {
            chatDataItem.debugText = debugMatcher.group(1)?.trim()
            remainingText = debugMatcher.replaceAll("")
        }
        
        // Extract performance metrics (various formats)
        val performancePatterns = listOf(
            // Format: "<performance>...</performance>"
            Pattern.compile("<performance>(.*?)</performance>", Pattern.DOTALL or Pattern.CASE_INSENSITIVE),
            // Format: "prefill: 149 tokens/s decode: 149 tokens/s"
            Pattern.compile("(?:prefill|prompt):\\s*([\\d.]+)\\s*(?:tokens?/s|tok/s).*?decode:\\s*([\\d.]+)\\s*(?:tokens?/s|tok/s)", Pattern.CASE_INSENSITIVE),
            // Format: "Speed: X tokens/s"
            Pattern.compile("(?:speed|throughput):\\s*([\\d.]+)\\s*(?:tokens?/s|tok/s)", Pattern.CASE_INSENSITIVE)
        )
        
        for (pattern in performancePatterns) {
            val matcher = pattern.matcher(remainingText)
            if (matcher.find()) {
                val perfText = when {
                    matcher.groupCount() >= 2 -> {
                        // Format with prefill and decode
                        "Prefill: ${matcher.group(1)} tokens/s\nDecode: ${matcher.group(2)} tokens/s"
                    }
                    matcher.groupCount() >= 1 -> {
                        matcher.group(1)?.trim()
                    }
                    else -> matcher.group(0)?.trim()
                }
                chatDataItem.performanceText = perfText
                remainingText = matcher.replaceAll("")
                break
            }
        }
        
        // Set display text (remaining text after extraction)
        chatDataItem.displayText = remainingText.trim()
        
        // If no display text but has other sections, show hint (except when there's an image)
        if (chatDataItem.displayText.isNullOrEmpty() && 
            chatDataItem.imageUri == null &&  // Don't show hint if there's an image
            (!chatDataItem.thinkingText.isNullOrEmpty() || 
             !chatDataItem.debugText.isNullOrEmpty() || 
             !chatDataItem.performanceText.isNullOrEmpty())) {
            chatDataItem.displayText = "[Response sections available above]"
        }
    }
    
    /**
     * Quick check if text contains any collapsible sections
     */
    fun hasCollapsibleSections(text: String): Boolean {
        return text.contains("<think", ignoreCase = true) ||
               text.contains("<agent>", ignoreCase = true) ||
               text.contains("<debug>", ignoreCase = true) ||
               text.contains("<performance>", ignoreCase = true) ||
               text.contains("prefill:", ignoreCase = true) ||
               text.contains("decode:", ignoreCase = true)
    }
    
    /**
     * Extract thinking time from text if present
     * Format: "思考了X秒" or "Thinking time: Xs"
     */
    fun extractThinkingTime(text: String): Long {
        val patterns = listOf(
            Pattern.compile("思考了(\\d+)秒"),
            Pattern.compile("thinking\\s+time:\\s*(\\d+)s", Pattern.CASE_INSENSITIVE),
            Pattern.compile("thought\\s+for\\s*(\\d+)s", Pattern.CASE_INSENSITIVE)
        )
        
        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1)?.toLongOrNull()?.times(1000) ?: -1L
            }
        }
        return -1L
    }
}
