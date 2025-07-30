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
 * 状态感知的Spinner适配器
 * 支持状态常量与显示文本的自动转换
 */
public class StateAwareSpinnerAdapter extends BaseAdapter {
    
    private final Context context;
    private final List<String> stateKeys;
    private final String stateType;
    private final StateDisplayManager stateDisplayManager;
    private final LayoutInflater inflater;
    
    /**
     * 构造函数
     * @param context 上下文
     * @param stateKeys 状态键列表
     * @param stateType 状态类型（用于确定使用哪种显示方法）
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
     * 根据状态键获取位置
     */
    public int getPositionByStateKey(String stateKey) {
        if (stateKeys != null && stateKey != null) {
            return stateKeys.indexOf(stateKey);
        }
        return -1;
    }
    
    /**
     * 根据位置获取状态键
     */
    public String getStateKeyByPosition(int position) {
        if (stateKeys != null && position >= 0 && position < stateKeys.size()) {
            return stateKeys.get(position);
        }
        return null;
    }
    
    /**
     * 更新状态键列表
     */
    public void updateStateKeys(List<String> newStateKeys) {
        this.stateKeys.clear();
        if (newStateKeys != null) {
            this.stateKeys.addAll(newStateKeys);
        }
        notifyDataSetChanged();
    }
    
    /**
     * 获取状态类型
     */
    public String getStateType() {
        return stateType;
    }
    
    /**
     * 获取所有状态键
     */
    public List<String> getStateKeys() {
        return stateKeys;
    }
}