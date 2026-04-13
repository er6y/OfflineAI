// Enhanced ChatViewHolders with support for multiple collapsible sections
// Simplified from MNN LLM Chat for OfflineAI project
package com.example.offlineai.chat.chatlist

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.offlineai.R
import com.example.offlineai.chat.model.ChatDataItem
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonPlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin

object ChatViewHolders {
    const val HEADER: Int = 0
    const val ASSISTANT: Int = 1
    const val USER: Int = 2

    class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val viewTime: TextView = itemView.findViewById(R.id.tv_date)

        fun bind(data: ChatDataItem) {
            viewTime.text = data.time
        }
    }

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView), 
        View.OnClickListener {
        
        val audioLayout: View = itemView.findViewById(R.id.layout_audio)
        val viewText: TextView = itemView.findViewById(R.id.tv_chat_text)
        val chatImage: ImageView = itemView.findViewById(R.id.tv_chat_image)
        val textDuration: TextView = itemView.findViewById(R.id.tv_chat_voice_duration)
        val iconPlayPause: ImageView = itemView.findViewById(R.id.iv_audio_play_pause)
        val audioSeekBar: SeekBar = itemView.findViewById(R.id.audio_seek_bar)
        
        // Callback for image preview
        private var imagePreviewCallback: ((String) -> Unit)? = null

        init {
            iconPlayPause.setOnClickListener(this)
            chatImage.setOnClickListener(this)
            
            // Enable text selection for user text
            viewText.setTextIsSelectable(true)
        }
        
        fun setImagePreviewCallback(callback: ((String) -> Unit)?) {
            this.imagePreviewCallback = callback
        }

        @SuppressLint("DefaultLocale")
        fun bind(data: ChatDataItem) {
            // Apply global text size
            try {
                val fontSize = com.example.offlineai.ConfigManager.getGlobalTextSize(itemView.context)
                viewText.textSize = fontSize
            } catch (e: Exception) {
                // Ignore if ConfigManager is not available
            }
            
            // Audio layout
            audioLayout.visibility = if (data.audioUri != null) View.VISIBLE else View.GONE
            audioLayout.tag = data
            iconPlayPause.tag = data
            itemView.tag = data
            
            // CRITICAL: Debug audio binding
            android.util.Log.i("UserViewHolder", "[BIND] audioUri=${data.audioUri}, audioPath=${data.audioPath}, duration=${data.audioDuration}, component=${data.audioPlayComponent != null}")
            
            // Text
            viewText.text = data.text
            viewText.tag = data
            audioLayout.tag = data
            chatImage.tag = data
            
            // Text visibility
            viewText.visibility = if (TextUtils.isEmpty(data.text)) View.GONE else View.VISIBLE
            
            // Audio duration
            textDuration.text = formatTime(data.audioDuration.toInt())
            
            // Image visibility
            val imageUri = data.imageUri
            chatImage.visibility = if (imageUri != null) View.VISIBLE else View.GONE
            if (imageUri != null) {
                chatImage.setImageURI(imageUri)
            }
            
            // Audio player component
            if (data.audioPlayComponent != null) {
                data.audioPlayComponent!!.bindViewHolder(this)
            }
        }

        override fun onClick(v: View) {
            val chatDataItem = v.tag as ChatDataItem
            android.util.Log.i("UserViewHolder", "[CLICK] View clicked: ${v.id}, audioUri=${chatDataItem.audioUri}")
            
            when (v.id) {
                R.id.iv_audio_play_pause -> {
                    android.util.Log.i("UserViewHolder", "[CLICK] Play/Pause button clicked")
                    if (chatDataItem.audioUri != null) {
                        android.util.Log.i("UserViewHolder", "[CLICK] audioUri exists: ${chatDataItem.audioUri}, audioPath=${chatDataItem.audioPath}")
                        if (chatDataItem.audioPlayComponent == null) {
                            android.util.Log.i("UserViewHolder", "[CLICK] Creating new AudioPlayerComponent")
                            chatDataItem.audioPlayComponent = AudioPlayerComponent(chatDataItem)
                        } else {
                            android.util.Log.i("UserViewHolder", "[CLICK] Reusing existing AudioPlayerComponent")
                        }
                        chatDataItem.audioPlayComponent!!.bindViewHolder(this)
                        chatDataItem.audioPlayComponent!!.onPlayPauseClicked()
                    } else {
                        android.util.Log.w("UserViewHolder", "[CLICK] audioUri is NULL, cannot play")
                    }
                }
                R.id.tv_chat_image -> {
                    // Show full screen image viewer
                    val imageUri = chatDataItem.imageUri
                    if (imageUri != null && imagePreviewCallback != null) {
                        imagePreviewCallback?.invoke(imageUri.toString())
                    } else {
                        Toast.makeText(itemView.context, "No image to preview", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Text selection is now handled by system via setTextIsSelectable(true)

        companion object {
            @SuppressLint("DefaultLocale")
            private fun formatTime(seconds: Int): String {
                val minutes = seconds / 60
                val remainingSeconds = seconds % 60
                return String.format("%d:%02d", minutes, remainingSeconds)
            }
        }
    }

    class AssistantViewHolder(view: View) : RecyclerView.ViewHolder(view), 
        View.OnClickListener, View.OnLongClickListener {
        
        companion object {
            const val TAG: String = "AssistantViewHolder"
            
            // Static switches to control collapsible sections visibility
            // These are controlled by upper layer (e.g., RagQaFragment)
            // Use @JvmField to expose as public static fields to Java
            @JvmField
            var showThinkingEnabled = true  // Default: show thinking
            
            @JvmField
            var showDebugEnabled = true  // Default: show debug
            
            @JvmField
            var showPerformanceEnabled = true  // Default: show performance
        }
        
        private val viewText: TextView = view.findViewById(R.id.tv_chat_text)
        private val imageGenerated: ImageView = view.findViewById(R.id.image_generated)
        var viewAssistantLoading: View = view.findViewById(R.id.view_assistant_loading)
        
        // TTS Audio Player
        private val audioPlayerContainer: View = view.findViewById(R.id.audio_player_container)
        private val btnPlayPauseTts: ImageView = view.findViewById(R.id.btnPlayPauseTts)
        private val seekBarTtsProgress: android.widget.SeekBar = view.findViewById(R.id.seekBarTtsProgress)
        private val textViewTtsDuration: TextView = view.findViewById(R.id.textViewTtsDuration)
        private var ttsMediaPlayer: android.media.MediaPlayer? = null
        private var ttsAudioPath: String? = null
        
        // Callback for transferring selected text to knowledge note
        private var transferToNoteCallback: ((String) -> Unit)? = null
        
        // Callback for image preview
        private var imagePreviewCallback: ((String) -> Unit)? = null
        
        // Callback for image long press menu
        private var imageLongPressCallback: ((String) -> Unit)? = null
        
        // Store current ActionMode reference for dismissal
        private var currentImageActionMode: android.view.ActionMode? = null
        
        // Thinking section
        private val thinkingToggle: LinearLayout = view.findViewById(R.id.ll_thinking_toggle)
        private val thinkingContainer: View = view.findViewById(R.id.ll_thinking_container)
        private val viewThinking: TextView = view.findViewById(R.id.tv_chat_thinking)
        private val textThinkingHeader: TextView = view.findViewById(R.id.tv_thinking_header)
        private val ivThinkingHeader: ImageView = view.findViewById(R.id.iv_thinking_header)
        private val thinkingMarker: View = view.findViewById(R.id.view_thinking_marker)
        
        // Debug section
        private val debugToggle: LinearLayout = view.findViewById(R.id.ll_debug_toggle)
        private val debugContainer: View = view.findViewById(R.id.ll_debug_container)
        private val viewDebug: TextView = view.findViewById(R.id.tv_chat_debug)
        private val textDebugHeader: TextView = view.findViewById(R.id.tv_debug_header)
        private val ivDebugHeader: ImageView = view.findViewById(R.id.iv_debug_header)
        private val debugMarker: View = view.findViewById(R.id.view_debug_marker)
        
        // Agent section
        private val agentToggle: LinearLayout = view.findViewById(R.id.ll_agent_toggle)
        private val agentContainer: View = view.findViewById(R.id.ll_agent_container)
        private val viewAgent: TextView = view.findViewById(R.id.tv_chat_agent)
        private val textAgentHeader: TextView = view.findViewById(R.id.tv_agent_header)
        private val ivAgentHeader: ImageView = view.findViewById(R.id.iv_agent_header)
        private val agentMarker: View = view.findViewById(R.id.view_agent_marker)
        
        // Performance section
        private val performanceToggle: LinearLayout = view.findViewById(R.id.ll_performance_toggle)
        private val performanceContainer: View = view.findViewById(R.id.ll_performance_container)
        private val viewPerformance: TextView = view.findViewById(R.id.tv_chat_performance)
        private val textPerformanceHeader: TextView = view.findViewById(R.id.tv_performance_header)
        private val ivPerformanceHeader: ImageView = view.findViewById(R.id.iv_performance_header)
        private val performanceMarker: View = view.findViewById(R.id.view_performance_marker)
        
        // Markwon with LaTeX support (inline $...$ and block $$...$$)
        // Also includes file link handler for clickable file links in assistant messages
        private val markdown = Markwon.builder(itemView.context)
            .usePlugin(MarkwonInlineParserPlugin.create())  // Required for inline LaTeX
            .usePlugin(JLatexMathPlugin.create(32f) { builder ->
                builder.inlinesEnabled(true)  // Enable $...$ inline formulas
            })
            .usePlugin(createFileLinkPlugin(itemView.context))
            .build()
        
        init {
            // Enable text selection for all text views
            setupTextSelection(viewText)
            setupTextSelection(viewThinking)
            setupTextSelection(viewDebug)
            setupTextSelection(viewPerformance)
            
            // Set click listener to dismiss ActionMode if active
            imageGenerated.setOnClickListener {
                if (currentImageActionMode != null) {
                    currentImageActionMode?.finish()
                    currentImageActionMode = null
                } else {
                    // Normal click behavior
                    onClick(it)
                }
            }
            imageGenerated.setOnLongClickListener(this)
            
            // Thinking toggle
            thinkingToggle.setOnClickListener {
                val chatDataItem = it.tag as ChatDataItem
                chatDataItem.toggleThinking()
                updateCollapsibleSections(chatDataItem)
            }
            
            // Debug toggle
            debugToggle.setOnClickListener {
                val chatDataItem = it.tag as ChatDataItem
                chatDataItem.toggleDebug()
                updateCollapsibleSections(chatDataItem)
            }
            
            // Agent toggle
            agentToggle.setOnClickListener {
                val chatDataItem = it.tag as ChatDataItem
                chatDataItem.toggleAgent()
                updateCollapsibleSections(chatDataItem)
            }
            
            // Performance toggle
            performanceToggle.setOnClickListener {
                val chatDataItem = it.tag as ChatDataItem
                chatDataItem.togglePerformance()
                updateCollapsibleSections(chatDataItem)
            }
        }
        
        fun setTransferToNoteCallback(callback: ((String) -> Unit)?) {
            this.transferToNoteCallback = callback
        }
        
        fun setImagePreviewCallback(callback: ((String) -> Unit)?) {
            this.imagePreviewCallback = callback
        }
        
        fun setImageLongPressCallback(callback: ((String) -> Unit)?) {
            this.imageLongPressCallback = callback
        }
        
        private fun setupTextSelection(textView: TextView) {
            textView.setTextIsSelectable(true)
            textView.customSelectionActionModeCallback = object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    return true
                }
                
                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                    // Check if "Transfer to Note" option already exists
                    val menuItemText = itemView.context.getString(com.example.offlineai.R.string.menu_item_transfer_to_note)
                    var hasTransferOption = false
                    for (i in 0 until menu.size()) {
                        if (menu.getItem(i).title == menuItemText) {
                            hasTransferOption = true
                            break
                        }
                    }
                    
                    // Add "Transfer to Note" option if it doesn't exist
                    if (!hasTransferOption) {
                        menu.add(Menu.NONE, Menu.FIRST + 100, 5, menuItemText)
                    }
                    return true
                }
                
                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                    val menuItemText = itemView.context.getString(com.example.offlineai.R.string.menu_item_transfer_to_note)
                    if (item.title == menuItemText) {
                        // Get selected text
                        val start = textView.selectionStart
                        val end = textView.selectionEnd
                        val selectedText = if (start >= 0 && end >= 0 && start != end) {
                            textView.text.substring(start, end)
                        } else {
                            textView.text.toString()
                        }
                        
                        // Call callback
                        transferToNoteCallback?.invoke(selectedText)
                        
                        // Close selection mode
                        mode.finish()
                        return true
                    }
                    return false
                }
                
                override fun onDestroyActionMode(mode: ActionMode) {
                    // No special handling needed
                }
            }
        }

        fun bind(data: ChatDataItem, @Suppress("UNUSED_PARAMETER") modelName: String?, payloads: List<Any?>?) {
            // Apply global text size
            applyGlobalTextSize()
            
            if (!payloads.isNullOrEmpty()) {
                // Incremental update (streaming)
                updateCollapsibleSections(data)
                if (data.displayText != null) {
                    if (data.markdownLocked) {
                        if (data.hasUnclosedLatex) {
                            bindFrozenMarkdownPrefix(data)
                        } else {
                            // Stable formula state: apply markdown with Spanned cache to avoid re-parse.
                            setMarkdownCached(viewText, data)
                        }
                    } else {
                        // Early streaming: plain text only, no LaTeX parse cost.
                        viewText.text = data.displayText
                    }
                    viewText.visibility = View.VISIBLE
                }
                return
            }
            
            // Full bind
            updateCollapsibleSections(data)
            
            // Main text
            android.util.Log.i(TAG, "[BIND] type=${data.type}, displayText.len=${data.displayText?.length}, agentText.len=${data.agentText?.length}, pos=$adapterPosition")
            if (TextUtils.isEmpty(data.displayText)) {
                viewText.visibility = View.GONE
            } else {
                setMarkdownCached(viewText, data)
                viewText.visibility = View.VISIBLE
            }
            // Post-layout diagnostic: check actual rendered state
            viewText.post {
                android.util.Log.i(TAG, "[BIND_POST] pos=$adapterPosition, viewText.visibility=${viewText.visibility}, w=${viewText.width}, h=${viewText.height}, text.len=${viewText.text?.length}, parent.w=${(viewText.parent as? View)?.width}, parent.h=${(viewText.parent as? View)?.height}")
            }

            // Loading indicator
            viewAssistantLoading.visibility = if (data.loading) View.VISIBLE else View.GONE
            
            // Generated image
            imageGenerated.visibility = if (data.imageUri != null) View.VISIBLE else View.GONE
            if (data.imageUri != null) {
                imageGenerated.setImageURI(data.imageUri)
            }
            
            // TTS Audio Player
            setupTtsAudioPlayer(data)
            
            // Set tags for click listeners
            imageGenerated.tag = data
            viewText.tag = data
            viewThinking.tag = data
            viewDebug.tag = data
            viewAgent.tag = data
            viewPerformance.tag = data
            thinkingToggle.tag = data
            debugToggle.tag = data
            agentToggle.tag = data
            performanceToggle.tag = data
        }
        
        private fun setupTtsAudioPlayer(data: ChatDataItem) {
            // Check if TTS audio exists (hasOmniAudio flag or audioUri with assistant type)
            val audioUri = data.audioUri
            val hasTtsAudio = data.hasOmniAudio && audioUri != null && audioUri.scheme == "file"
            
            if (!hasTtsAudio) {
                audioPlayerContainer.visibility = View.GONE
                releaseTtsMediaPlayer()
                return
            }
            
            audioPlayerContainer.visibility = View.VISIBLE
            ttsAudioPath = audioUri?.path
            
            if (ttsAudioPath == null) {
                audioPlayerContainer.visibility = View.GONE
                return
            }
            
            // Setup MediaPlayer
            try {
                releaseTtsMediaPlayer()
                ttsMediaPlayer = android.media.MediaPlayer().apply {
                    setDataSource(ttsAudioPath!!)
                    prepare()
                    
                    val duration = this.duration
                    textViewTtsDuration.text = formatDuration(duration)
                    seekBarTtsProgress.max = duration
                    seekBarTtsProgress.progress = 0
                }
                
                // Play/Pause button
                btnPlayPauseTts.setOnClickListener {
                    ttsMediaPlayer?.let { player ->
                        if (player.isPlaying) {
                            player.pause()
                            btnPlayPauseTts.setImageResource(R.drawable.ic_audio_play)
                        } else {
                            player.start()
                            btnPlayPauseTts.setImageResource(R.drawable.ic_audio_pause)
                            updateTtsProgress()
                        }
                    }
                }
                
                // SeekBar change listener
                seekBarTtsProgress.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            ttsMediaPlayer?.seekTo(progress)
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
                })
                
                // Reset button on completion
                ttsMediaPlayer?.setOnCompletionListener {
                    btnPlayPauseTts.setImageResource(R.drawable.ic_audio_play)
                    seekBarTtsProgress.progress = 0
                }
                
            } catch (e: Exception) {
                com.example.offlineai.LogManager.logE("AssistantViewHolder", "Failed to setup TTS audio player", e)
                audioPlayerContainer.visibility = View.GONE
            }
        }
        
        private fun updateTtsProgress() {
            ttsMediaPlayer?.let { player ->
                if (player.isPlaying) {
                    seekBarTtsProgress.progress = player.currentPosition
                    seekBarTtsProgress.postDelayed({ updateTtsProgress() }, 100)
                }
            }
        }
        
        private fun releaseTtsMediaPlayer() {
            try {
                ttsMediaPlayer?.release()
            } catch (e: Exception) {
                // Ignore
            }
            ttsMediaPlayer = null
        }
        
        private fun formatDuration(durationMs: Int): String {
            val seconds = (durationMs / 1000) % 60
            val minutes = (durationMs / 1000) / 60
            // Always use minutes:seconds format for consistency
            return String.format("%d:%02d", minutes, seconds)
        }
        
        /**
         * Normalize LaTeX formulas:
         * 1. Remove spaces adjacent to $ symbols
         * 2. Convert single $ formulas to $$ (Markwon only supports $$)
         * Example: "$ x $" -> "$$x$$", "$$ x + y $$" -> "$$x + y$$"
         */
        private fun normalizeLatex(text: String): String {
            var result = text
                .replace(Regex("\\$\\s+")) { "$" }  // Remove spaces after $
                .replace(Regex("\\s+\\$")) { "$" }  // Remove spaces before $
            
            // Convert single $ to $$ (match $...$ but not $$...$$)
            // Pattern: $ followed by non-$ text, then $ (not preceded/followed by $)
            result = result.replace(Regex("(?<!\\$)\\$(?!\\$)([^\\$]+?)\\$(?!\\$)")) { matchResult ->
                "$$" + matchResult.groupValues[1] + "$$"
            }
            
            return result
        }

        /**
         * Apply markdown to viewText, skipping re-render if text is unchanged.
         * Must use setMarkdown() (not toMarkdown+setText) because JLatexMath needs
         * setMarkdown to register its async ImageSpan callbacks for formula rendering.
         * The skip-if-same optimization prevents redundant LaTeX re-parse on every
         * streaming frame when displayText hasn't actually changed.
         */
        private fun setMarkdownCached(tv: TextView, data: ChatDataItem) {
            val text = normalizeLatex(data.displayText ?: "")
            if (data.cachedSpannedKey == text && tv.width > 0) {
                // Same text as last render AND view has been laid out: skip to avoid redundant LaTeX parse.
                return
            }
            // Full markdown path owns the whole text; clear any frozen-tail cache.
            data.streamingPlainTail = ""
            // Only cache key if view has valid width; otherwise re-render on next bind after layout
            if (tv.width > 0) {
                data.cachedSpannedKey = text
            } else {
                data.cachedSpannedKey = null
            }
            markdown.setMarkdown(tv, text)
        }

        private fun bindFrozenMarkdownPrefix(data: ChatDataItem) {
            val fullText = data.displayText ?: ""
            val stableText = data.stableMarkdownText ?: ""

            if (stableText.isEmpty() || !fullText.startsWith(stableText)) {
                // Fallback: no stable prefix, render full text normally (cache-protected).
                setMarkdownCached(viewText, data)
                return
            }

            // Stable prefix exists: keep it rendered as markdown, then append tail incrementally as plain text.
            // This keeps old formulas stable while still showing in-progress unclosed formula tail.
            val normalizedStable = normalizeLatex(stableText)
            if (data.cachedSpannedKey != normalizedStable) {
                data.streamingPlainTail = ""
                data.cachedSpannedKey = normalizedStable
                markdown.setMarkdown(viewText, normalizedStable)
            }

            val tailText = fullText.substring(stableText.length)
            if (tailText.isEmpty()) {
                data.streamingPlainTail = ""
                return
            }

            val previousTail = data.streamingPlainTail
            if (tailText.startsWith(previousTail)) {
                // Append only delta chars to avoid touching already-rendered prefix.
                val delta = tailText.substring(previousTail.length)
                if (delta.isNotEmpty()) {
                    viewText.append(delta)
                }
                data.streamingPlainTail = tailText
                return
            }

            // Tail was rewritten (parser trim or stream correction). Rebuild as prefix + full current tail.
            data.streamingPlainTail = tailText
            data.cachedSpannedKey = normalizedStable
            markdown.setMarkdown(viewText, normalizedStable)
            viewText.append(tailText)
        }
        
        private fun applyGlobalTextSize() {
            try {
                val fontSize = com.example.offlineai.ConfigManager.getGlobalTextSize(itemView.context)
                viewText.textSize = fontSize
                viewThinking.textSize = fontSize
                viewDebug.textSize = fontSize
                viewAgent.textSize = fontSize
                viewPerformance.textSize = fontSize
            } catch (e: Exception) {
                // Ignore if ConfigManager is not available
            }
        }
        
        private fun updateCollapsibleSections(data: ChatDataItem) {
            // Update thinking section (controlled by static switch)
            val thinkingHeaderBase = itemView.context.getString(com.example.offlineai.R.string.collapsible_thinking)
            val thinkingHasContent = showThinkingEnabled && !TextUtils.isEmpty(data.thinkingText)
            updateSection(
                hasContent = thinkingHasContent,
                isExpanded = data.showThinking,
                toggleView = thinkingToggle,
                containerView = thinkingContainer,
                contentView = viewThinking,
                headerTextView = textThinkingHeader,
                headerIconView = ivThinkingHeader,
                markerView = thinkingMarker,
                content = data.thinkingText,
                headerText = if (data.thinkingFinishedTime >= 0)
                    "$thinkingHeaderBase (${data.thinkingFinishedTime / 1000}s)"
                else "$thinkingHeaderBase..."
            )
            
            // Update debug section (controlled by static switch)
            updateSection(
                hasContent = showDebugEnabled && !TextUtils.isEmpty(data.debugText),
                isExpanded = data.showDebug,
                toggleView = debugToggle,
                containerView = debugContainer,
                contentView = viewDebug,
                headerTextView = textDebugHeader,
                headerIconView = ivDebugHeader,
                markerView = debugMarker,
                content = data.debugText,
                headerText = itemView.context.getString(com.example.offlineai.R.string.collapsible_debug)
            )
            
            // Update agent section (always show when content exists, default collapsed)
            val agentHeaderBase = itemView.context.getString(com.example.offlineai.R.string.collapsible_agent)
            updateSection(
                hasContent = !TextUtils.isEmpty(data.agentText),
                isExpanded = data.showAgent,
                toggleView = agentToggle,
                containerView = agentContainer,
                contentView = viewAgent,
                headerTextView = textAgentHeader,
                headerIconView = ivAgentHeader,
                markerView = agentMarker,
                content = data.agentText,
                headerText = agentHeaderBase
            )
            
            // Update performance section (controlled by static switch)
            updateSection(
                hasContent = showPerformanceEnabled && !TextUtils.isEmpty(data.performanceText),
                isExpanded = data.showPerformance,
                toggleView = performanceToggle,
                containerView = performanceContainer,
                contentView = viewPerformance,
                headerTextView = textPerformanceHeader,
                headerIconView = ivPerformanceHeader,
                markerView = performanceMarker,
                content = data.performanceText,
                headerText = itemView.context.getString(com.example.offlineai.R.string.collapsible_performance)
            )
        }
        
        private fun updateSection(
            hasContent: Boolean,
            isExpanded: Boolean,
            toggleView: View,
            containerView: View,
            contentView: TextView,
            headerTextView: TextView,
            headerIconView: ImageView,
            markerView: View,
            content: String?,
            headerText: String
        ) {
            if (!hasContent) {
                toggleView.visibility = View.GONE
                containerView.visibility = View.GONE
                return
            }
            
            toggleView.visibility = View.VISIBLE
            headerTextView.text = headerText
            
            if (isExpanded && !TextUtils.isEmpty(content)) {
                containerView.visibility = View.VISIBLE
                contentView.visibility = View.VISIBLE
                markdown.setMarkdown(contentView, content!!)
                headerIconView.setImageResource(R.drawable.ic_arrow_up)
                markerView.visibility = View.VISIBLE
            } else {
                containerView.visibility = View.GONE
                contentView.visibility = View.GONE
                headerIconView.setImageResource(R.drawable.ic_arrow_down)
            }
        }

        override fun onClick(v: View) {
            when (v.id) {
                R.id.image_generated -> {
                    // Show full screen image viewer
                    val chatDataItem = v.tag as? ChatDataItem
                    val imageUri = chatDataItem?.imageUri
                    if (imageUri != null && imagePreviewCallback != null) {
                        imagePreviewCallback?.invoke(imageUri.toString())
                    } else {
                        Toast.makeText(itemView.context, "No image to preview", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        
        override fun onLongClick(v: View): Boolean {
            when (v.id) {
                R.id.image_generated -> {
                    // Start ActionMode for image operations (copy/save/share) - similar to text selection
                    val chatDataItem = v.tag as? ChatDataItem
                    val imageUri = chatDataItem?.imageUri
                    if (imageUri != null) {
                        startImageActionMode(v, imageUri.toString())
                        return true
                    }
                }
            }
            return false
        }
        
        /**
         * Start ActionMode for image operations (copy/save/share)
         */
        private fun startImageActionMode(view: View, imagePath: String) {
            val context = view.context
            
            // Strip file:// prefix if present
            val filePath = if (imagePath.startsWith("file://")) {
                imagePath.substring(7)
            } else {
                imagePath
            }
            
            val imageFile = java.io.File(filePath)
            if (!imageFile.exists()) {
                Toast.makeText(context, "Image file not found", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Create Uri for the file
            val imageUri = androidx.core.content.FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                imageFile
            )
            
            // Create ActionMode callback (similar to text selection)
            val callback = object : android.view.ActionMode.Callback {
                override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                    // Don't keep system default menu, we'll add custom items
                    return true
                }
                
                override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                    // Clear any existing items to avoid duplicates
                    menu.clear()
                    
                    // Add custom image operation menu items (COPY, SAVE, SHARE)
                    // Use order values to control display order: lower values appear first
                    menu.add(android.view.Menu.NONE, android.view.Menu.FIRST + 100, 1, context.getString(R.string.common_copy))
                    menu.add(android.view.Menu.NONE, android.view.Menu.FIRST + 101, 2, context.getString(R.string.common_save))
                    menu.add(android.view.Menu.NONE, android.view.Menu.FIRST + 102, 3, context.getString(R.string.common_share))
                    return true
                }
                
                override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                    when (item.itemId) {
                        android.view.Menu.FIRST + 100 -> { // Copy
                            copyImageToClipboard(context, imageUri)
                            mode.finish()
                            return true
                        }
                        android.view.Menu.FIRST + 101 -> { // Save
                            saveImageToGallery(context, imageFile)
                            mode.finish()
                            return true
                        }
                        android.view.Menu.FIRST + 102 -> { // Share
                            shareImage(context, imageUri)
                            mode.finish()
                            return true
                        }
                    }
                    return false
                }
                
                override fun onDestroyActionMode(mode: android.view.ActionMode) {
                    // Clean up reference
                    if (currentImageActionMode == mode) {
                        currentImageActionMode = null
                    }
                }
            }
            
            // Dismiss previous ActionMode if exists
            currentImageActionMode?.finish()
            
            // Start floating ActionMode (like text selection) instead of primary ActionMode (top bar)
            currentImageActionMode = view.startActionMode(callback, android.view.ActionMode.TYPE_FLOATING)
        }
        
        /**
         * Copy image to clipboard
         */
        private fun copyImageToClipboard(context: android.content.Context, imageUri: android.net.Uri) {
            try {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newUri(context.contentResolver, "Image", imageUri)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Image copied to clipboard", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to copy image", Toast.LENGTH_SHORT).show()
            }
        }
        
        /**
         * Save image to gallery
         */
        private fun saveImageToGallery(context: android.content.Context, imageFile: java.io.File) {
            try {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "diffusion_${System.currentTimeMillis()}.jpg")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_PICTURES}/OfflineAI")
                }
                
                val uri = context.contentResolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        java.io.FileInputStream(imageFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    Toast.makeText(context, "Image saved to gallery", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to save image", Toast.LENGTH_SHORT).show()
            }
        }
        
        /**
         * Share image via system share dialog
         */
        private fun shareImage(context: android.content.Context, imageUri: android.net.Uri) {
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(android.content.Intent.EXTRA_STREAM, imageUri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(android.content.Intent.createChooser(intent, "Share Image"))
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to share image", Toast.LENGTH_SHORT).show()
            }
        }

        // Text selection is now handled by system via setTextIsSelectable(true)
        // Custom "Transfer to Note" menu is added via customSelectionActionModeCallback
    }

    /**
     * Create a Markwon plugin that makes markdown links clickable.
     * For local file paths, opens the system file chooser dialog.
     * For http(s) URLs, opens the browser.
     * Uses afterSetText to replace URLSpans with custom ClickableSpans
     * so they work alongside setTextIsSelectable(true).
     */
    private fun createFileLinkPlugin(ctx: Context): MarkwonPlugin {
        return object : AbstractMarkwonPlugin() {
            override fun afterSetText(textView: TextView) {
                val text = textView.text
                if (text !is android.text.Spannable) return
                
                val urlSpans = text.getSpans(0, text.length, android.text.style.URLSpan::class.java)
                if (urlSpans.isNullOrEmpty()) return
                
                for (span in urlSpans) {
                    val url = span.url ?: continue
                    val start = text.getSpanStart(span)
                    val end = text.getSpanEnd(span)
                    val flags = text.getSpanFlags(span)
                    
                    text.removeSpan(span)
                    text.setSpan(object : android.text.style.ClickableSpan() {
                        override fun onClick(widget: View) {
                            openFileOrUrl(widget.context, url)
                        }
                        override fun updateDrawState(ds: android.text.TextPaint) {
                            super.updateDrawState(ds)
                            ds.isUnderlineText = true
                        }
                    }, start, end, flags)
                }
                
                // Only set LinkMovementMethod when links are present (avoid overriding ArrowKeyMovementMethod on every frame)
                if (textView.movementMethod !is android.text.method.LinkMovementMethod) {
                    textView.movementMethod = android.text.method.LinkMovementMethod.getInstance()
                }
            }
        }
    }
    
    /**
     * Open a file path or URL: local files via ACTION_VIEW with FileProvider, URLs via browser
     */
    private fun openFileOrUrl(context: Context, url: String) {
        try {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                context.startActivity(intent)
                return
            }
            // Local file: try absolute path first, then resolve relative to known chat folders
            val file = java.io.File(url)
            if (file.isAbsolute && file.exists()) {
                openLocalFile(context, file)
                return
            }
            val chatFolders = listOf(
                com.example.offlineai.ConfigManager.getString(context, com.example.offlineai.ConfigManager.KEY_AGENT_CHAT_FOLDER, ""),
                com.example.offlineai.ConfigManager.getString(context, com.example.offlineai.ConfigManager.KEY_CURRENT_CHAT_FOLDER, "")
            )
            for (folder in chatFolders) {
                if (folder.isNotEmpty()) {
                    val resolved = java.io.File(folder, url)
                    if (resolved.exists()) {
                        openLocalFile(context, resolved)
                        return
                    }
                }
            }
            Toast.makeText(context, "File not found: $url", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Open a local file using system file chooser via FileProvider
     */
    private fun openLocalFile(context: Context, file: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val mimeType = android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase())
                ?: "application/octet-stream"
            
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "No app to open this file type", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
