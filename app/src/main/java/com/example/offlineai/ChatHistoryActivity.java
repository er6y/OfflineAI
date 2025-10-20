package com.example.offlineai;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat History Activity
 * Displays list of all saved conversations
 */
public class ChatHistoryActivity extends AppCompatActivity {
    private static final String TAG = "ChatHistoryActivity";
    
    private RecyclerView recyclerView;
    private ChatHistoryAdapter adapter;
    private List<ChatHistoryManager.ChatHistoryItem> allHistoryItems = new ArrayList<>();
    private List<ChatHistoryManager.ChatHistoryItem> filteredItems = new ArrayList<>();
    private EditText searchEditText;
    private TextView emptyView;
    private FloatingActionButton fabDeleteAll;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_history);
        
        // Setup toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.chat_history_title);
        }
        
        // Initialize views
        recyclerView = findViewById(R.id.recyclerViewHistory);
        searchEditText = findViewById(R.id.editTextSearch);
        emptyView = findViewById(R.id.textViewEmpty);
        fabDeleteAll = findViewById(R.id.fabDeleteAll);
        
        // Setup RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ChatHistoryAdapter(filteredItems, new ChatHistoryAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(ChatHistoryManager.ChatHistoryItem item) {
                loadConversation(item);
            }
            
            @Override
            public void onDeleteClick(ChatHistoryManager.ChatHistoryItem item) {
                confirmDeleteConversation(item);
            }
        });
        recyclerView.setAdapter(adapter);
        
        // Setup search filter
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterHistory(s.toString());
            }
            
            @Override
            public void afterTextChanged(Editable s) {}
        });
        
        // Setup delete all button
        fabDeleteAll.setOnClickListener(v -> confirmDeleteAll());
        
        // Load history
        loadHistory();
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    /**
     * Load chat history from disk
     */
    private void loadHistory() {
        try {
            allHistoryItems = ChatHistoryManager.getChatHistoryList(this);
            filteredItems.clear();
            filteredItems.addAll(allHistoryItems);
            adapter.notifyDataSetChanged();
            
            updateEmptyView();
            
            LogManager.logD(TAG, "Loaded " + allHistoryItems.size() + " chat histories");
        } catch (Exception e) {
            LogManager.logE(TAG, "Error loading history", e);
            Toast.makeText(this, R.string.toast_chat_history_load_failed, Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Filter history by search query
     */
    private void filterHistory(String query) {
        filteredItems.clear();
        
        if (query == null || query.trim().isEmpty()) {
            // Show all if query is empty
            filteredItems.addAll(allHistoryItems);
        } else {
            // Filter by preview text or folder name
            String lowerQuery = query.toLowerCase();
            for (ChatHistoryManager.ChatHistoryItem item : allHistoryItems) {
                if (item.preview.toLowerCase().contains(lowerQuery) ||
                    item.folderName.toLowerCase().contains(lowerQuery)) {
                    filteredItems.add(item);
                }
            }
        }
        
        adapter.notifyDataSetChanged();
        updateEmptyView();
    }
    
    /**
     * Update empty view visibility
     */
    private void updateEmptyView() {
        if (filteredItems.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
            fabDeleteAll.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
            fabDeleteAll.setVisibility(View.VISIBLE);
        }
    }
    
    /**
     * Load selected conversation
     */
    private void loadConversation(ChatHistoryManager.ChatHistoryItem item) {
        try {
            // Set current chat folder
            ConfigManager.setString(this, 
                ConfigManager.KEY_CURRENT_CHAT_FOLDER, item.folderPath);
            
            LogManager.logD(TAG, "Loading conversation from: " + item.folderPath);
            Toast.makeText(this, R.string.toast_chat_history_loaded, Toast.LENGTH_SHORT).show();
            
            // Close activity and return to RAG QA fragment
            finish();
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Error loading conversation", e);
            Toast.makeText(this, R.string.toast_chat_history_load_failed, Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Confirm delete single conversation
     */
    private void confirmDeleteConversation(ChatHistoryManager.ChatHistoryItem item) {
        new AlertDialog.Builder(this)
            .setTitle(R.string.common_confirm)
            .setMessage(R.string.dialog_confirm_delete_chat)
            .setPositiveButton(R.string.common_delete, (dialog, which) -> {
                deleteConversation(item);
            })
            .setNegativeButton(R.string.common_cancel, null)
            .show();
    }
    
    /**
     * Delete single conversation
     */
    private void deleteConversation(ChatHistoryManager.ChatHistoryItem item) {
        try {
            boolean success = ChatHistoryManager.deleteChatFolder(item.folderPath);
            if (success) {
                // Remove from lists
                allHistoryItems.remove(item);
                filteredItems.remove(item);
                adapter.notifyDataSetChanged();
                updateEmptyView();
                
                Toast.makeText(this, R.string.toast_chat_history_deleted, Toast.LENGTH_SHORT).show();
                LogManager.logD(TAG, "Deleted conversation: " + item.folderPath);
            } else {
                Toast.makeText(this, R.string.toast_chat_history_delete_failed, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Error deleting conversation", e);
            Toast.makeText(this, R.string.toast_chat_history_delete_failed, Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Confirm delete all conversations
     */
    private void confirmDeleteAll() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.common_confirm)
            .setMessage(R.string.dialog_confirm_delete_all_chats)
            .setPositiveButton(R.string.common_delete, (dialog, which) -> {
                deleteAllConversations();
            })
            .setNegativeButton(R.string.common_cancel, null)
            .show();
    }
    
    /**
     * Delete all conversations
     */
    private void deleteAllConversations() {
        try {
            int deletedCount = ChatHistoryManager.deleteAllChatHistory(this);
            
            // Clear lists
            allHistoryItems.clear();
            filteredItems.clear();
            adapter.notifyDataSetChanged();
            updateEmptyView();
            
            String message = getString(R.string.toast_all_chat_history_deleted, deletedCount);
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            LogManager.logD(TAG, "Deleted all conversations: " + deletedCount);
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Error deleting all conversations", e);
            Toast.makeText(this, R.string.toast_chat_history_delete_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
