package com.example.starlocalrag;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * API URL 适配器，支持删除功能
 */
public class ApiUrlAdapter extends BaseAdapter {
    private final Context context;
    private final List<String> apiUrls;
    private final OnApiUrlDeleteListener deleteListener;
    private final OnApiUrlSelectListener selectListener;
    private final Spinner parentSpinner;
    private int selectedPosition = -1;

    /**
     * API URL 删除监听器
     */
    public interface OnApiUrlDeleteListener {
        void onApiUrlDelete(String apiUrl, int position);
    }

    /**
     * API URL 选择监听器
     */
    public interface OnApiUrlSelectListener {
        void onApiUrlSelect(String apiUrl, int position);
    }

    /**
     * 构造函数
     * @param context 上下文
     * @param apiUrls API URL 列表
     * @param deleteListener 删除监听器
     */
    public ApiUrlAdapter(Context context, List<String> apiUrls, OnApiUrlDeleteListener deleteListener) {
        this(context, apiUrls, deleteListener, null, null);
    }

    /**
     * 构造函数
     * @param context 上下文
     * @param apiUrls API URL 列表
     * @param deleteListener 删除监听器
     * @param selectListener 选择监听器
     * @param parentSpinner 父Spinner
     */
    public ApiUrlAdapter(Context context, List<String> apiUrls, 
                         OnApiUrlDeleteListener deleteListener,
                         OnApiUrlSelectListener selectListener,
                         Spinner parentSpinner) {
        this.context = context;
        this.apiUrls = new ArrayList<>(apiUrls);
        this.deleteListener = deleteListener;
        this.selectListener = selectListener;
        this.parentSpinner = parentSpinner;
    }

    @Override
    public int getCount() {
        return apiUrls.size();
    }

    @Override
    public Object getItem(int position) {
        return apiUrls.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        // 非下拉视图使用系统默认的布局
        if (convertView == null) {
            LayoutInflater inflater = LayoutInflater.from(context);
            convertView = inflater.inflate(android.R.layout.simple_spinner_item, parent, false);
        }
        
        TextView textView = (TextView) convertView;
        textView.setText(apiUrls.get(position));
        
        return convertView;
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        // 下拉视图使用自定义布局，包含删除按钮
        View view = convertView;
        if (view == null) {
            LayoutInflater inflater = LayoutInflater.from(context);
            view = inflater.inflate(R.layout.item_api_url, parent, false);
        }

        String apiUrl = apiUrls.get(position);
        TextView textViewApiUrl = view.findViewById(R.id.textViewApiUrl);
        ImageButton buttonDelete = view.findViewById(R.id.buttonDeleteApiUrl);
        RadioButton radioButtonSelect = view.findViewById(R.id.radioButtonSelectApiUrl);

        textViewApiUrl.setText(apiUrl);
        
        // 设置单选按钮状态
        radioButtonSelect.setChecked(position == selectedPosition);
        
        // 设置单选按钮点击事件
        View finalView = view;
        radioButtonSelect.setOnClickListener(v -> {
            selectedPosition = position;
            notifyDataSetChanged();
            
            if (parentSpinner != null) {
                parentSpinner.setSelection(position);
            }
            
            if (selectListener != null) {
                selectListener.onApiUrlSelect(apiUrl, position);
            }
            
            // 关闭下拉列表
            if (parentSpinner != null) {
                parentSpinner.performClick();
            }
        });
        
        // 设置整行点击事件
        finalView.setOnClickListener(v -> {
            radioButtonSelect.performClick();
        });

        // 只为非"新建..."和非"local"选项显示删除按钮，且不为第一个选项（新建...）和第二个选项（local）
        String newApiUrlText = StateDisplayManager.getApiUrlDisplayText(context, AppConstants.ApiUrl.NEW);
        String localApiUrlText = StateDisplayManager.getApiUrlDisplayText(context, AppConstants.ApiUrl.LOCAL);
        if (!apiUrl.equals(newApiUrlText) && !apiUrl.equals(localApiUrlText) && position > 1) {
            buttonDelete.setVisibility(View.VISIBLE);
            buttonDelete.setOnClickListener(v -> {
                if (deleteListener != null) {
                    deleteListener.onApiUrlDelete(apiUrl, position);
                }
            });
        } else {
            buttonDelete.setVisibility(View.GONE);
        }

        return view;
    }

    /**
     * 更新数据
     * @param newApiUrls 新的API URL列表
     */
    public void updateData(List<String> newApiUrls) {
        apiUrls.clear();
        apiUrls.addAll(newApiUrls);
        notifyDataSetChanged();
    }
    
    /**
     * 设置选中位置
     * @param position 位置
     */
    public void setSelectedPosition(int position) {
        if (position >= 0 && position < apiUrls.size()) {
            selectedPosition = position;
            notifyDataSetChanged();
        }
    }
}
