// Enhanced ChatViewHolders with support for multiple collapsible sections
// Simplified from MNN LLM Chat for OfflineAI project
package com.example.offlineai.chat.chatlist

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import io.noties.markwon.Markwon

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
            when (v.id) {
                R.id.iv_audio_play_pause -> {
                    if (chatDataItem.audioUri != null) {
                        if (chatDataItem.audioPlayComponent == null) {
                            chatDataItem.audioPlayComponent = AudioPlayerComponent(chatDataItem)
                        }
                        chatDataItem.audioPlayComponent!!.bindViewHolder(this)
                        chatDataItem.audioPlayComponent!!.onPlayPauseClicked()
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
        View.OnClickListener {
        
        private val viewText: TextView = view.findViewById(R.id.tv_chat_text)
        private val imageGenerated: ImageView = view.findViewById(R.id.image_generated)
        var viewAssistantLoading: View = view.findViewById(R.id.view_assistant_loading)
        
        // Callback for transferring selected text to knowledge note
        private var transferToNoteCallback: ((String) -> Unit)? = null
        
        // Callback for image preview
        private var imagePreviewCallback: ((String) -> Unit)? = null
        
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
        
        // Performance section
        private val performanceToggle: LinearLayout = view.findViewById(R.id.ll_performance_toggle)
        private val performanceContainer: View = view.findViewById(R.id.ll_performance_container)
        private val viewPerformance: TextView = view.findViewById(R.id.tv_chat_performance)
        private val textPerformanceHeader: TextView = view.findViewById(R.id.tv_performance_header)
        private val ivPerformanceHeader: ImageView = view.findViewById(R.id.iv_performance_header)
        private val performanceMarker: View = view.findViewById(R.id.view_performance_marker)
        
        private val markdown = Markwon.create(itemView.context)

        init {
            // Enable text selection for all text views
            setupTextSelection(viewText)
            setupTextSelection(viewThinking)
            setupTextSelection(viewDebug)
            setupTextSelection(viewPerformance)
            imageGenerated.setOnClickListener(this)
            
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

        fun bind(data: ChatDataItem, modelName: String?, payloads: List<Any?>?) {
            // Apply global text size
            applyGlobalTextSize()
            
            if (!payloads.isNullOrEmpty()) {
                // Incremental update
                updateCollapsibleSections(data)
                if (data.displayText != null) {
                    markdown.setMarkdown(viewText, data.displayText!!)
                }
                return
            }

            // Full bind
            updateCollapsibleSections(data)
            
            // Main text
            if (TextUtils.isEmpty(data.displayText)) {
                viewText.visibility = View.GONE
            } else {
                markdown.setMarkdown(viewText, data.displayText!!)
                viewText.visibility = View.VISIBLE
            }

            // Loading indicator
            viewAssistantLoading.visibility = if (data.loading) View.VISIBLE else View.GONE
            
            // Generated image
            imageGenerated.visibility = if (data.imageUri != null) View.VISIBLE else View.GONE
            if (data.imageUri != null) {
                imageGenerated.setImageURI(data.imageUri)
            }
            
            // Set tags for click listeners
            imageGenerated.tag = data
            viewText.tag = data
            viewThinking.tag = data
            viewDebug.tag = data
            viewPerformance.tag = data
            thinkingToggle.tag = data
            debugToggle.tag = data
            performanceToggle.tag = data
        }
        
        private fun applyGlobalTextSize() {
            try {
                val fontSize = com.example.offlineai.ConfigManager.getGlobalTextSize(itemView.context)
                viewText.textSize = fontSize
                viewThinking.textSize = fontSize
                viewDebug.textSize = fontSize
                viewPerformance.textSize = fontSize
            } catch (e: Exception) {
                // Ignore if ConfigManager is not available
            }
        }
        
        private fun updateCollapsibleSections(data: ChatDataItem) {
            // Update thinking section
            val thinkingHeaderBase = itemView.context.getString(com.example.offlineai.R.string.collapsible_thinking)
            updateSection(
                hasContent = !TextUtils.isEmpty(data.thinkingText),
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
            
            // Update debug section
            updateSection(
                hasContent = !TextUtils.isEmpty(data.debugText),
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
            
            // Update performance section
            updateSection(
                hasContent = !TextUtils.isEmpty(data.performanceText),
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

        // Text selection is now handled by system via setTextIsSelectable(true)
        // Custom "Transfer to Note" menu is added via customSelectionActionModeCallback

        companion object {
            const val TAG: String = "AssistantViewHolder"
        }
    }
}
