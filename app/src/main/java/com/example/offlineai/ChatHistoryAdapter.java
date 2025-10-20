package com.example.offlineai;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Adapter for chat history list
 */
public class ChatHistoryAdapter extends RecyclerView.Adapter<ChatHistoryAdapter.ViewHolder> {
    
    private List<ChatHistoryManager.ChatHistoryItem> items;
    private OnItemClickListener listener;
    
    public interface OnItemClickListener {
        void onItemClick(ChatHistoryManager.ChatHistoryItem item);
        void onDeleteClick(ChatHistoryManager.ChatHistoryItem item);
    }
    
    public ChatHistoryAdapter(List<ChatHistoryManager.ChatHistoryItem> items, OnItemClickListener listener) {
        this.items = items;
        this.listener = listener;
    }
    
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_chat_history, parent, false);
        return new ViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ChatHistoryManager.ChatHistoryItem item = items.get(position);
        holder.bind(item, listener);
    }
    
    @Override
    public int getItemCount() {
        return items.size();
    }
    
    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textPreview;
        TextView textFolderName;
        ImageButton buttonDelete;
        
        ViewHolder(View itemView) {
            super(itemView);
            textPreview = itemView.findViewById(R.id.textViewPreview);
            textFolderName = itemView.findViewById(R.id.textViewFolderName);
            buttonDelete = itemView.findViewById(R.id.buttonDelete);
        }
        
        void bind(ChatHistoryManager.ChatHistoryItem item, OnItemClickListener listener) {
            textPreview.setText(item.preview);
            textFolderName.setText(item.folderName);
            
            // Click to load conversation
            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onItemClick(item);
                }
            });
            
            // Click delete button
            buttonDelete.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDeleteClick(item);
                }
            });
        }
    }
}
