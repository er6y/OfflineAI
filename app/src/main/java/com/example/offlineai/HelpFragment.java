package com.example.offlineai;

import android.content.Context;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Objects;

import io.noties.markwon.Markwon;
import io.noties.markwon.linkify.LinkifyPlugin;

/**
 * 帮助页面Fragment，用于显示应用使用说明
 */
public class HelpFragment extends Fragment {

    private static final String TAG = "HelpFragment";
    private LogManager logManager;
    private TextView helpTextView;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logManager = LogManager.getInstance(requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_help, container, false);
        helpTextView = view.findViewById(R.id.helpTextView);
        
        // 加载帮助文档
        loadHelpDocument();
        
        return view;
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Setup menu provider for close button (new API)
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull android.view.Menu menu, @NonNull android.view.MenuInflater menuInflater) {
                // No additional menu items needed
            }
            
            @Override
            public boolean onMenuItemSelected(@NonNull android.view.MenuItem menuItem) {
                if (menuItem.getItemId() == android.R.id.home) {
                    // CRITICAL: Check if fragment is still attached before accessing Activity
                    if (!isAdded() || getActivity() == null) {
                        android.util.Log.w("HelpFragment", "[MENU] Fragment not attached, ignoring back button");
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

    /**
     * 加载帮助文档
     */
    private void loadHelpDocument() {
        try {
            // 从assets目录读取帮助文档
            InputStream inputStream = requireContext().getAssets().open("USER_GUIDE.md");
            String markdownContent = readFromInputStream(inputStream);
            
            // 使用Markwon渲染Markdown内容，添加等宽字体支持
            final Markwon markwon = Markwon.builder(requireContext())
                    .usePlugin(LinkifyPlugin.create())
                    // 添加自定义插件，确保代码块和表格使用等宽字体
                    .usePlugin(new io.noties.markwon.AbstractMarkwonPlugin() {
                        @Override
                        public void configureTheme(@NonNull io.noties.markwon.core.MarkwonTheme.Builder builder) {
                            // 设置代码块使用等宽字体
                            builder
                                .codeTypeface(android.graphics.Typeface.MONOSPACE)
                                .codeBlockTypeface(android.graphics.Typeface.MONOSPACE)
                                // 设置代码块背景色，提高可读性
                                .codeBlockBackgroundColor(android.graphics.Color.parseColor("#f5f5f5"))
                                .codeBackgroundColor(android.graphics.Color.parseColor("#f0f0f0"))
                                // 增加代码块内边距
                                .codeBlockMargin(16);
                        }
                    })
                    .build();
            
            markwon.setMarkdown(helpTextView, markdownContent);
            
            // 启用链接点击
            helpTextView.setMovementMethod(LinkMovementMethod.getInstance());
            
        } catch (IOException e) {
            logManager.e(TAG, "加载帮助文档失败: " + e.getMessage());
            helpTextView.setText("无法加载帮助文档，请确保应用正确安装。");
        }
    }

    /**
     * 从输入流读取文本内容
     */
    private String readFromInputStream(InputStream inputStream) throws IOException {
        StringBuilder resultStringBuilder = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = br.readLine()) != null) {
                resultStringBuilder.append(line).append("\n");
            }
        }
        return resultStringBuilder.toString();
    }

    /**
     * 当Fragment可见时，应用保存的字体大小设置和显示返回按钮
     */
    @Override
    public void onResume() {
        super.onResume();
        // Show back button and set title in ActionBar
        if (getActivity() != null && ((androidx.appcompat.app.AppCompatActivity) getActivity()).getSupportActionBar() != null) {
            ((androidx.appcompat.app.AppCompatActivity) getActivity()).getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            ((androidx.appcompat.app.AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.title_help);
        }
        // 应用保存的字体大小设置
        float textSize = ConfigManager.getGlobalTextSize(requireContext());
        helpTextView.setTextSize(textSize);
    }
    
    @Override
    public void onPause() {
        super.onPause();
        // Title restoration is handled in MenuProvider to avoid multiple calls
    }
}

