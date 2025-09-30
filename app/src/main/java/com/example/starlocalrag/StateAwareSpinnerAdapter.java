package com.example.starlocalrag.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import com.example.starlocalrag.StateDisplayManager;
import java.util.List;

/**
 * State-aware Spinner adapter
 * Supports automatic conversion between state constants and display text
 */
public class StateAwareSpinnerAdapter extends BaseAdapter {
    
    private final Context context;
    private final List<String> stateKeys;
    private final String stateType;
    private final StateDisplayManager stateDisplayManager;
    private final LayoutInflater inflater;
    
    /**
     * Constructor
     * @param context Context
     * @param stateKeys State key list
     * @param stateType State type (used to determine which display method to use)
     */
    public StateAwareSpinnerAdapter(Context context, List<String> stateKeys, String stateType) {
        this.context = context;
        this.stateKeys = stateKeys;
        this.stateType = stateType;
        this.stateDisplayManager = new StateDisplayManager(context);
        this.inflater = LayoutInflater.from(context);
    }
    
    @Override
    public int getCount() {
        return stateKeys != null ? stateKeys.size() : 0;
    }
    
    @Override
    public Object getItem(int position) {
        return stateKeys != null ? stateKeys.get(position) : null;
    }
    
    @Override
    public long getItemId(int position) {
        return position;
    }
    
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        return createView(position, convertView, parent, false);
    }
    
    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        return createView(position, convertView, parent, true);
    }
    
    private View createView(int position, View convertView, ViewGroup parent, boolean isDropDown) {
        View view = convertView;
        if (view == null) {
            int layoutId = isDropDown ? 
                android.R.layout.simple_spinner_dropdown_item : 
                android.R.layout.simple_spinner_item;
            view = inflater.inflate(layoutId, parent, false);
        }
        
        TextView textView = view.findViewById(android.R.id.text1);
        if (textView != null && position < stateKeys.size()) {
            String stateKey = stateKeys.get(position);
            String displayText = stateDisplayManager.getDisplayText(stateType, stateKey);
            textView.setText(displayText);
        }
        
        return view;
    }
    
    /**
     * Get position by state key
     */
    public int getPositionByStateKey(String stateKey) {
        if (stateKeys != null && stateKey != null) {
            return stateKeys.indexOf(stateKey);
        }
        return -1;
    }
    
    /**
     * Get state key by position
     */
    public String getStateKeyByPosition(int position) {
        if (stateKeys != null && position >= 0 && position < stateKeys.size()) {
            return stateKeys.get(position);
        }
        return null;
    }
    
    /**
     * Update state key list
     */
    public void updateStateKeys(List<String> newStateKeys) {
        this.stateKeys.clear();
        if (newStateKeys != null) {
            this.stateKeys.addAll(newStateKeys);
        }
        notifyDataSetChanged();
    }
    
    /**
     * Get state type
     */
    public String getStateType() {
        return stateType;
    }
    
    /**
     * Get all state keys
     */
    public List<String> getStateKeys() {
        return stateKeys;
    }
}