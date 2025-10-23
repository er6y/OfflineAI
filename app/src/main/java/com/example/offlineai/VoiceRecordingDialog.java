package com.example.offlineai;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Locale;

/**
 * 语音录音Dialog
 * 仿微信风格的录音界面
 * 
 * @author OfflineAI Team
 */
public class VoiceRecordingDialog {
    private static final String TAG = "VoiceRecordingDialog";
    
    private Context context;
    private Dialog dialog;
    private TextView textViewDuration;
    private TextView textViewHint;
    private TextView textViewWaveHint;
    private ImageView imageViewMic;
    private TextView textViewCancelHint;
    
    // 状态
    private boolean isShowing = false;
    private boolean isCanceling = false;
    
    public VoiceRecordingDialog(Context context) {
        this.context = context;
        initDialog();
    }
    
    private void initDialog() {
        // 创建Dialog
        dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        
        // 加载布局
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_voice_recording, null);
        dialog.setContentView(view);
        
        // 透明背景
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        
        // 获取控件
        textViewDuration = view.findViewById(R.id.textViewDuration);
        textViewHint = view.findViewById(R.id.textViewHint);
        textViewWaveHint = view.findViewById(R.id.textViewWaveHint);
        imageViewMic = view.findViewById(R.id.imageViewMic);
        textViewCancelHint = view.findViewById(R.id.textViewCancelHint);
        
        // 不可取消（用户必须通过松开或上滑取消）
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
    }
    
    /**
     * 显示Dialog
     */
    public void show() {
        if (!isShowing) {
            dialog.show();
            isShowing = true;
            isCanceling = false;
            
            // 重置UI
            textViewDuration.setText("00:00");
            textViewHint.setText(R.string.release_to_send);
            textViewWaveHint.setVisibility(View.VISIBLE);
            textViewCancelHint.setVisibility(View.GONE);
            
            LogManager.logD(TAG, "Recording dialog shown");
        }
    }
    
    /**
     * 隐藏Dialog
     */
    public void dismiss() {
        if (isShowing) {
            dialog.dismiss();
            isShowing = false;
            LogManager.logD(TAG, "Recording dialog dismissed");
        }
    }
    
    /**
     * 更新录音时长
     * @param durationMs 时长（毫秒）
     */
    public void updateDuration(long durationMs) {
        int seconds = (int) (durationMs / 1000);
        int minutes = seconds / 60;
        seconds = seconds % 60;
        
        String timeStr = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
        textViewDuration.setText(timeStr);
    }
    
    /**
     * 更新波形（增强版）
     * @param amplitude 振幅 (0-32767)
     */
    public void updateWaveform(int amplitude) {
        // 根据振幅计算显示等级 (1-7级)
        // 振幅范围：静音~500, 轻声500~2000, 正常2000~8000, 大声8000+
        int level;
        if (amplitude < 500) {
            level = 1;  // 几乎静音
        } else if (amplitude < 2000) {
            level = 2;  // 很轻
        } else if (amplitude < 4000) {
            level = 3;  // 轻声
        } else if (amplitude < 8000) {
            level = 4;  // 正常
        } else if (amplitude < 12000) {
            level = 5;  // 较大
        } else if (amplitude < 18000) {
            level = 6;  // 大声
        } else {
            level = 7;  // 非常大声
        }
        
        // 使用竖条表示音频强度，更直观
        StringBuilder wave = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if (i < level) {
                wave.append("▊");  // 填充的竖条
            } else {
                wave.append("▁");  // 空的底线
            }
        }
        textViewWaveHint.setText(wave.toString());
        
        // 根据音量调整波形颜色
        if (level <= 2) {
            textViewWaveHint.setTextColor(android.graphics.Color.parseColor("#9E9E9E"));  // 灰色-静音
        } else if (level <= 4) {
            textViewWaveHint.setTextColor(android.graphics.Color.parseColor("#FF9800"));  // 橙色-正常
        } else {
            textViewWaveHint.setTextColor(android.graphics.Color.parseColor("#4CAF50"));  // 绿色-响亮
        }
        
        // 麦克风图标动画：根据音量调整缩放
        float scale = 1.0f + (level - 1) * 0.05f;  // 1.0 ~ 1.3倍缩放
        imageViewMic.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(100)
            .start();
    }
    
    /**
     * 切换到取消状态
     */
    public void showCancelState() {
        if (!isCanceling) {
            isCanceling = true;
            textViewHint.setText(R.string.recording_canceled);
            textViewHint.setTextColor(Color.parseColor("#F44336"));  // Red
            textViewCancelHint.setVisibility(View.VISIBLE);
            imageViewMic.setColorFilter(Color.parseColor("#F44336"));
            
            LogManager.logD(TAG, "Switched to cancel state");
        }
    }
    
    /**
     * 恢复正常状态
     */
    public void showNormalState() {
        if (isCanceling) {
            isCanceling = false;
            textViewHint.setText(R.string.release_to_send);
            textViewHint.setTextColor(Color.parseColor("#757575"));  // Gray
            textViewCancelHint.setVisibility(View.GONE);
            imageViewMic.setColorFilter(Color.parseColor("#FF5722"));  // Orange
            
            LogManager.logD(TAG, "Switched to normal state");
        }
    }
    
    /**
     * 是否正在显示
     */
    public boolean isShowing() {
        return isShowing;
    }
    
    /**
     * 是否处于取消状态
     */
    public boolean isCanceling() {
        return isCanceling;
    }
}
