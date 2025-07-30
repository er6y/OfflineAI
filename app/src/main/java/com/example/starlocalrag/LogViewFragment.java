package com.example.starlocalrag;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.navigation.Navigation;

import java.io.File;

import com.example.starlocalrag.LogManager;
import com.example.starlocalrag.ConfigManager;

/**
 * 日志查看Fragment
 */
public class LogViewFragment extends Fragment {
    
    // 提示文本常量
    // Toast消息将使用字符串资源
    // 使用字符串资源替代硬编码字符串
    // private static final String SHARE_CHOOSER_TITLE = "分享日志";
    // private static final String COMMENT_REFRESH_LOG = "刷新日志内容";
    // private static final String COMMENT_COPY_ALL = "复制全部文本";
    // private static final String COMMENT_CLEAR_LOG = "清空日志";
    // private static final String COMMENT_SHARE_LOG = "分享日志";

    private TextView textViewLog;
    private View scrollbarTrack;
    private View scrollbarThumb;
    private ImageButton buttonScrollUp;
    private ImageButton buttonScrollDown;
    private LogManager logManager;
    private Handler mainHandler = new Handler(Looper.getMainLooper()); // 主线程Handler
    private float lastTouchY; // 记录上次触摸的Y坐标
    private int trackHeight; // 滚动条轨道高度
    private int textViewHeight; // 文本视图高度
    private int textTotalHeight; // 文本总高度
    private boolean isDragging = false; // 是否正在拖动滑块

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_log_view, container, false);
        textViewLog = view.findViewById(R.id.textViewLog);
        scrollbarTrack = view.findViewById(R.id.scrollbarTrack);
        scrollbarThumb = view.findViewById(R.id.scrollbarThumb);
        buttonScrollUp = view.findViewById(R.id.buttonScrollUp);
        buttonScrollDown = view.findViewById(R.id.buttonScrollDown);
        
        // 设置TextView为可选择状态
        textViewLog.setTextIsSelectable(true);
        
        // 获取LogManager实例
        logManager = LogManager.getInstance(requireContext());
        
        // 应用全局字体大小
        applyGlobalTextSize();
        
        // 设置滚动条相关事件处理
        setupScrollbarControls();
        
        // 加载日志内容
        loadLogContent();
        
        return view;
    }
    
    /**
     * 设置滚动条控件的事件处理
     */
    private void setupScrollbarControls() {
        // 设置滑块拖动事件
        scrollbarThumb.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    // 记录初始触摸位置
                    lastTouchY = event.getRawY();
                    trackHeight = scrollbarTrack.getHeight();
                    textViewHeight = textViewLog.getHeight();
                    if (textViewLog.getLayout() != null) {
                        textTotalHeight = textViewLog.getLayout().getHeight();
                    }
                    isDragging = true;
                    return true;
                
                case MotionEvent.ACTION_MOVE:
                    if (isDragging) {
                        // 计算移动距离
                        float deltaY = event.getRawY() - lastTouchY;
                        lastTouchY = event.getRawY();
                        
                        // 计算滑块新位置
                        float newY = v.getY() + deltaY;
                        
                        // 限制滑块在轨道范围内
                        if (newY < 0) {
                            newY = 0;
                        } else if (newY > trackHeight - v.getHeight()) {
                            newY = trackHeight - v.getHeight();
                        }
                        
                        // 更新滑块位置
                        v.setY(newY);
                        
                        // 计算滚动比例
                        float scrollRatio = newY / (trackHeight - v.getHeight());
                        
                        // 计算文本应该滚动的位置
                        int maxScroll = Math.max(0, textTotalHeight - textViewHeight);
                        int scrollY = (int) (scrollRatio * maxScroll);
                        
                        // 滚动文本
                        textViewLog.scrollTo(0, scrollY);
                        
                        // 更新滚动按钮状态
                        updateScrollButtonsState();
                        return true;
                    }
                    break;
                
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isDragging = false;
                    return true;
            }
            return false;
        });
        
        // 设置轨道点击事件
        scrollbarTrack.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                trackHeight = scrollbarTrack.getHeight();
                textViewHeight = textViewLog.getHeight();
                if (textViewLog.getLayout() != null) {
                    textTotalHeight = textViewLog.getLayout().getHeight();
                }
                
                // 计算点击位置
                float clickY = event.getY();
                
                // 计算滑块应该在的位置
                float thumbHeight = scrollbarThumb.getHeight();
                float newThumbY = clickY - (thumbHeight / 2);
                
                // 限制滑块在轨道范围内
                if (newThumbY < 0) {
                    newThumbY = 0;
                } else if (newThumbY > trackHeight - thumbHeight) {
                    newThumbY = trackHeight - thumbHeight;
                }
                
                // 更新滑块位置
                scrollbarThumb.setY(newThumbY);
                
                // 计算滚动比例
                float scrollRatio = newThumbY / (trackHeight - thumbHeight);
                
                // 计算文本应该滚动的位置
                int maxScroll = Math.max(0, textTotalHeight - textViewHeight);
                int scrollY = (int) (scrollRatio * maxScroll);
                
                // 滚动文本
                textViewLog.scrollTo(0, scrollY);
                
                // 更新滚动按钮状态
                updateScrollButtonsState();
                return true;
            }
            return false;
        });
        
        // 设置向上按钮点击事件
        buttonScrollUp.setOnClickListener(v -> {
            // 向上滚动一页
            int currentScroll = textViewLog.getScrollY();
            textViewLog.scrollTo(0, Math.max(0, currentScroll - textViewHeight));
            updateScrollbarThumbPosition();
            updateScrollButtonsState();
        });
        
        // 设置向下按钮点击事件
        buttonScrollDown.setOnClickListener(v -> {
            // 向下滚动一页
            int currentScroll = textViewLog.getScrollY();
            if (textViewLog.getLayout() != null) {
                int maxScroll = textViewLog.getLayout().getHeight() - textViewHeight;
                textViewLog.scrollTo(0, Math.min(maxScroll, currentScroll + textViewHeight));
                updateScrollbarThumbPosition();
                updateScrollButtonsState();
            }
        });
        
        // 监听文本滚动事件，更新滑块位置
        textViewLog.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (!isDragging) {
                updateScrollbarThumbPosition();
                updateScrollButtonsState();
            }
        });
        
        // 初始化滚动条状态
        updateScrollbarVisibility();
        updateScrollButtonsState();
    }
    
    /**
     * 更新滚动条滑块位置
     */
    private void updateScrollbarThumbPosition() {
        if (textViewLog.getLayout() == null) return;
        
        int scrollY = textViewLog.getScrollY();
        int textHeight = textViewLog.getLayout().getHeight();
        int viewHeight = textViewLog.getHeight();
        
        // 如果文本高度小于视图高度，隐藏滑块
        if (textHeight <= viewHeight) {
            scrollbarThumb.setVisibility(View.INVISIBLE);
            return;
        } else {
            scrollbarThumb.setVisibility(View.VISIBLE);
        }
        
        // 计算滚动比例
        float scrollRatio = (float) scrollY / (textHeight - viewHeight);
        
        // 计算滑块应该在的位置
        int trackHeight = scrollbarTrack.getHeight();
        int thumbHeight = scrollbarThumb.getHeight();
        float newThumbY = scrollRatio * (trackHeight - thumbHeight);
        
        // 更新滑块位置
        scrollbarThumb.setY(newThumbY);
    }
    
    /**
     * 更新滚动条可见性
     */
    private void updateScrollbarVisibility() {
        if (textViewLog.getLayout() == null) return;
        
        int textHeight = textViewLog.getLayout().getHeight();
        int viewHeight = textViewLog.getHeight();
        
        // 如果文本高度小于或等于视图高度，隐藏滚动条组件
        if (textHeight <= viewHeight) {
            scrollbarTrack.setVisibility(View.INVISIBLE);
            scrollbarThumb.setVisibility(View.INVISIBLE);
            buttonScrollUp.setVisibility(View.INVISIBLE);
            buttonScrollDown.setVisibility(View.INVISIBLE);
        } else {
            scrollbarTrack.setVisibility(View.VISIBLE);
            scrollbarThumb.setVisibility(View.VISIBLE);
            buttonScrollUp.setVisibility(View.VISIBLE);
            buttonScrollDown.setVisibility(View.VISIBLE);
            
            // 调整滑块高度比例
            adjustThumbHeight(textHeight, viewHeight);
        }
    }
    
    /**
     * 根据内容比例调整滑块高度
     */
    private void adjustThumbHeight(int textHeight, int viewHeight) {
        if (textHeight <= 0 || viewHeight <= 0) return;
        
        // 计算滑块高度比例（视图高度/文本总高度）
        float ratio = (float) viewHeight / textHeight;
        
        // 限制最小比例，确保滑块不会太小
        ratio = Math.max(ratio, 0.1f);
        
        // 计算滑块高度
        int trackHeight = scrollbarTrack.getHeight();
        int thumbHeight = (int) (trackHeight * ratio);
        
        // 设置最小高度
        thumbHeight = Math.max(thumbHeight, dpToPx(20));
        
        // 更新滑块高度
        ViewGroup.LayoutParams params = scrollbarThumb.getLayoutParams();
        params.height = thumbHeight;
        scrollbarThumb.setLayoutParams(params);
    }
    
    /**
     * 更新滚动按钮状态
     */
    private void updateScrollButtonsState() {
        if (textViewLog.getLayout() == null) return;
        
        int scrollY = textViewLog.getScrollY();
        int textHeight = textViewLog.getLayout().getHeight();
        int viewHeight = textViewLog.getHeight();
        
        // 如果已经滚动到顶部，禁用向上按钮
        buttonScrollUp.setEnabled(scrollY > 0);
        buttonScrollUp.setAlpha(scrollY > 0 ? 1.0f : 0.5f);
        
        // 如果已经滚动到底部，禁用向下按钮
        boolean canScrollDown = scrollY < (textHeight - viewHeight);
        buttonScrollDown.setEnabled(canScrollDown);
        buttonScrollDown.setAlpha(canScrollDown ? 1.0f : 0.5f);
    }
    
    /**
     * 将dp转换为像素
     */
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }


    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // 添加菜单提供者
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.log_view_menu, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                return handleMenuItemSelected(menuItem);
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }
    
    /**
     * 处理菜单项选择
     */
    private boolean handleMenuItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        
        if (id == R.id.action_refresh) {
            // 刷新日志内容
            loadLogContent();
            return true;
        } else if (id == R.id.action_select_all) {
            // 全选文本
            if (textViewLog != null) {
                if (textViewLog.getText() != null) {
                    Selection.selectAll((Spannable) textViewLog.getText());
                    Toast.makeText(requireContext(), getString(R.string.toast_text_selected), Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        } else if (id == R.id.action_copy) {
            // 复制全部文本
            if (textViewLog != null) {
                ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText(getString(R.string.clipboard_log_content), textViewLog.getText());
                clipboard.setPrimaryClip(clip);
                Toast.makeText(requireContext(), getString(R.string.toast_log_copied), Toast.LENGTH_SHORT).show();
            }
            return true;
        } else if (id == R.id.action_clear_log) {
            // 清空日志
            clearLog();
            return true;
        } else if (id == R.id.action_share_log) {
            // 分享日志
            shareLog();
            return true;
        } else if (id == R.id.action_close) {
            // 关闭页面 - 使用Navigation组件
            if (getActivity() != null) {
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
            }
            return true;
        }
        
        return false;
    }
    
    /**
     * 加载日志内容
     */
    private void loadLogContent() {
        if (logManager != null && textViewLog != null) {
            // 显示加载指示器或临时文本
            textViewLog.setText("正在加载日志...");
            
            // 在后台线程中读取日志文件
            new Thread(() -> {
                final String logContent = logManager.readLogFile();
                
                // 在主线程中更新UI
                mainHandler.post(() -> {
                    if (textViewLog != null && isAdded()) {
                        textViewLog.setText(logContent);
                        
                        // 滚动到底部
                        if (textViewLog.getLayout() != null) {
                            int scrollAmount = textViewLog.getLayout().getLineTop(textViewLog.getLineCount()) - textViewLog.getHeight();
                            if (scrollAmount > 0) {
                                textViewLog.scrollTo(0, scrollAmount);
                            } else {
                                textViewLog.scrollTo(0, 0);
                            }
                        }
                        
                        // 更新滚动条状态
                        updateScrollbarVisibility();
                        updateScrollbarThumbPosition();
                        updateScrollButtonsState();
                    }
                });
            }).start();
        }
    }
    
    /**
     * 清空日志
     */
    private void clearLog() {
        if (logManager != null) {
            logManager.clearLogFile();
            loadLogContent(); // 重新加载日志内容（此时应为空）
            Toast.makeText(requireContext(), getString(R.string.toast_log_cleared), Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 分享日志
     */
    private void shareLog() {
        if (logManager != null) {
            try {
                File logFile = new File(requireContext().getFilesDir(), LogManager.LOG_FILE_NAME);
                if (!logFile.exists()) {
                    Toast.makeText(requireContext(), getString(R.string.toast_log_file_not_exist), Toast.LENGTH_SHORT).show();
                    return;
                }
                
                // 使用FileProvider分享文件
                Uri fileUri = FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().getPackageName() + ".fileprovider",
                    logFile
                );
                
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                
                startActivity(Intent.createChooser(shareIntent, getString(R.string.menu_share_logs)));
            } catch (Exception e) {
                Toast.makeText(requireContext(), getString(R.string.toast_share_failed) + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    /**
     * 应用全局字体大小设置
     */
    private void applyGlobalTextSize() {
        if (textViewLog != null) {
            float fontSize = ConfigManager.getGlobalTextSize(requireContext());
            textViewLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
            LogManager.logD("LogViewFragment", "已应用全局字体大小: " + fontSize + "sp");
        }
    }
    
    @Override
    public void onResume() {
        super.onResume();
        // 在页面恢复时重新应用字体大小，以便在设置页面修改后能够立即生效
        applyGlobalTextSize();
    }
}
