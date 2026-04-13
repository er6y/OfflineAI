package com.example.offlineai;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat History Fragment
 * Displays list of all saved conversations
 */
public class ChatHistoryFragment extends Fragment {
    private static final String TAG = "ChatHistoryFragment";
    
    private RecyclerView recyclerView;
    private ChatHistoryAdapter adapter;
    private List<ChatHistoryManager.ChatHistoryItem> allHistoryItems = new ArrayList<>();
    private List<ChatHistoryManager.ChatHistoryItem> filteredItems = new ArrayList<>();
    private EditText searchEditText;
    private TextView emptyView;
    private FloatingActionButton fabDeleteAll;
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_chat_history, container, false);
        
        // Initialize views
        recyclerView = view.findViewById(R.id.recyclerViewHistory);
        searchEditText = view.findViewById(R.id.editTextSearch);
        emptyView = view.findViewById(R.id.textViewEmpty);
        fabDeleteAll = view.findViewById(R.id.fabDeleteAll);
        
        // Setup RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
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
        
        return view;
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Setup menu provider for back button (new API)
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                // No additional menu items needed
            }
            
            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                if (menuItem.getItemId() == android.R.id.home) {
                    // CRITICAL: Check if fragment is still attached before accessing Activity
                    if (!isAdded() || getActivity() == null) {
                        android.util.Log.w("ChatHistoryFragment", "[MENU] Fragment not attached, ignoring back button");
                        return true;
                    }
                    
                    // CRITICAL: Restore main UI BEFORE popBackStack (Fragment will be detached after pop)
                    androidx.appcompat.app.ActionBar actionBar = ((androidx.appcompat.app.AppCompatActivity) getActivity()).getSupportActionBar();
                    if (actionBar != null) {
                        actionBar.setDisplayHomeAsUpEnabled(false);
                        actionBar.setTitle(R.string.app_name);
                    }
                    getActivity().findViewById(R.id.container).setVisibility(android.view.View.GONE);
                    getActivity().findViewById(R.id.viewPager).setVisibility(android.view.View.VISIBLE);
                    
                    // Now clear all fragments from back stack
                    androidx.fragment.app.FragmentManager fm = getActivity().getSupportFragmentManager();
                    while (fm.getBackStackEntryCount() > 0) {
                        fm.popBackStackImmediate();
                    }
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }
    
    @Override
    public void onResume() {
        super.onResume();
        // Show back button and set title in ActionBar
        if (getActivity() != null && ((androidx.appcompat.app.AppCompatActivity) getActivity()).getSupportActionBar() != null) {
            ((androidx.appcompat.app.AppCompatActivity) getActivity()).getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            ((androidx.appcompat.app.AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.chat_history_title);
        }
    }
    
    @Override
    public void onPause() {
        super.onPause();
        // Title restoration is handled in MenuProvider to avoid multiple calls
    }
    
    /**
     * Load chat history from disk
     */
    private void loadHistory() {
        try {
            allHistoryItems = ChatHistoryManager.getChatHistoryList(requireContext());
            filteredItems.clear();
            filteredItems.addAll(allHistoryItems);
            adapter.notifyDataSetChanged();
            
            updateEmptyView();
            
            LogManager.logD(TAG, "Loaded " + allHistoryItems.size() + " chat histories");
        } catch (Exception e) {
            LogManager.logE(TAG, "Error loading history", e);
            Toast.makeText(requireContext(), R.string.toast_chat_history_load_failed, Toast.LENGTH_SHORT).show();
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
        // CRITICAL: Check if fragment is still attached before accessing Context
        if (!isAdded() || getActivity() == null) {
            android.util.Log.w("ChatHistoryFragment", "[LOAD] Fragment not attached, cannot load conversation");
            return;
        }
        try {
            // CRITICAL: Save Context and Activity reference BEFORE popBackStack
            android.content.Context context = requireContext();
            androidx.fragment.app.FragmentActivity activity = getActivity();
            androidx.fragment.app.FragmentManager fm = activity.getSupportFragmentManager();
            
            // CRITICAL: Get RagQaFragment BEFORE popBackStack
            androidx.fragment.app.Fragment ragQaFragment = fm.findFragmentByTag("f" + 0);
            
            // Set current chat folder (BEFORE popBackStack)
            ConfigManager.setString(context, 
                ConfigManager.KEY_CURRENT_CHAT_FOLDER, item.folderPath);
            
            // Also persist to mode-specific key so switchChatFolderByMode can restore it
            boolean isAgent = ConfigManager.getBoolean(context, ConfigManager.KEY_AGENT_MODE_ENABLED, false);
            if (!isAgent) {
                ConfigManager.setString(context, ConfigManager.KEY_RAG_CHAT_FOLDER, item.folderPath);
                LogManager.logD(TAG, "[LOAD] Saved RAG chat folder: " + item.folderPath);
            }
            
            LogManager.logD(TAG, "Loading conversation from: " + item.folderPath);
            
            // Show toast BEFORE popBackStack
            Toast.makeText(context, R.string.toast_chat_history_loaded, Toast.LENGTH_SHORT).show();
            
            // Restore main UI
            androidx.appcompat.app.ActionBar actionBar = ((androidx.appcompat.app.AppCompatActivity) activity).getSupportActionBar();
            if (actionBar != null) {
                actionBar.setDisplayHomeAsUpEnabled(false);
                actionBar.setTitle(R.string.app_name);
            }
            activity.findViewById(R.id.container).setVisibility(android.view.View.GONE);
            activity.findViewById(R.id.viewPager).setVisibility(android.view.View.VISIBLE);
            
            // Clear all fragments from back stack
            while (fm.getBackStackEntryCount() > 0) {
                fm.popBackStackImmediate();
            }
            
            // CRITICAL: Manually trigger reload in RagQaFragment (it won't onResume because it's in ViewPager)
            if (ragQaFragment instanceof RagQaFragment) {
                ((RagQaFragment) ragQaFragment).loadChatHistory();
                LogManager.logD(TAG, "[LOAD] Manually triggered RagQaFragment.loadChatHistory()");
            } else {
                LogManager.logW(TAG, "[LOAD] RagQaFragment not found, conversation may not reload");
            }
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Error loading conversation", e);
            // CRITICAL: Check before Toast in catch block
            if (isAdded() && getActivity() != null) {
                try {
                    Toast.makeText(requireContext(), R.string.toast_chat_history_load_failed, Toast.LENGTH_SHORT).show();
                } catch (Exception toastEx) {
                    android.util.Log.e("ChatHistoryFragment", "[LOAD] Failed to show error toast", toastEx);
                }
            }
        }
    }
    
    /**
     * Confirm delete single conversation
     */
    private void confirmDeleteConversation(ChatHistoryManager.ChatHistoryItem item) {
        new AlertDialog.Builder(requireContext())
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
                
                Toast.makeText(requireContext(), R.string.toast_chat_history_deleted, Toast.LENGTH_SHORT).show();
                LogManager.logD(TAG, "Deleted conversation: " + item.folderPath);
            } else {
                Toast.makeText(requireContext(), R.string.toast_chat_history_delete_failed, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "Error deleting conversation", e);
            Toast.makeText(requireContext(), R.string.toast_chat_history_delete_failed, Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * Confirm delete all conversations
     */
    private void confirmDeleteAll() {
        new AlertDialog.Builder(requireContext())
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
            int deletedCount = ChatHistoryManager.deleteAllChatHistory(requireContext());
            
            // Clear lists
            allHistoryItems.clear();
            filteredItems.clear();
            adapter.notifyDataSetChanged();
            updateEmptyView();
            
            String message = getString(R.string.toast_all_chat_history_deleted, deletedCount);
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
            LogManager.logD(TAG, "Deleted all conversations: " + deletedCount);
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Error deleting all conversations", e);
            Toast.makeText(requireContext(), R.string.toast_chat_history_delete_failed, Toast.LENGTH_SHORT).show();
        }
    }
}
