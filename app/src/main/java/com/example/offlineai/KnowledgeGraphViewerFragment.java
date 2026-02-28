package com.example.offlineai;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Knowledge Graph Viewer Fragment
 * 
 * Displays knowledge graph statistics and relationships in Markdown format.
 */
public class KnowledgeGraphViewerFragment extends Fragment {
    
    private static final String TAG = "OfflineAI_GraphViewer";
    
    // UI Components
    private Spinner spinnerKnowledgeBase;
    private SeekBar seekBarTopN;
    private TextView textViewTopNValue;
    private Button buttonShare;
    private WebView webViewGraph;
    
    // Data
    private List<String> knowledgeBaseNames = new ArrayList<>();
    private String currentKbName = null;
    private int currentTopN = 50; // Default value
    
    // Pending payload for WebView rendering
    private String pendingGraphPayloadJson = null;
    private boolean webViewLoaded = false;
    
    // Background executor
    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    
    // Agent Web Operations Support
    private BroadcastReceiver agentWebReceiver;
    private String lastPageContent = "";

    private synchronized ExecutorService getExecutor() {
        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            // Lazily create a new single-thread executor when needed
            executor = Executors.newSingleThreadExecutor();
        }
        return executor;
    }
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_knowledge_graph_viewer, container, false);
        
        // Initialize UI components
        initializeViews(view);
        
        // Load knowledge bases
        loadKnowledgeBases();
        
        return view;
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // Register BroadcastReceiver for Agent Web operations
        registerAgentWebReceiver();
        
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
                        android.util.Log.w("KnowledgeGraphViewerFragment", "[MENU] Fragment not attached, ignoring back button");
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
    
    private void initializeViews(View view) {
        spinnerKnowledgeBase = view.findViewById(R.id.spinnerKnowledgeBase);
        seekBarTopN = view.findViewById(R.id.seekBarTopN);
        textViewTopNValue = view.findViewById(R.id.textViewTopNValue);
        buttonShare = view.findViewById(R.id.buttonShare);
        webViewGraph = view.findViewById(R.id.webViewGraph);

        // Configure WebView for local HTML report
        WebSettings webSettings = webViewGraph.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);

        webViewGraph.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                webViewLoaded = true;
                LogManager.logI(TAG, "[WEBVIEW] onPageFinished url=" + url +
                        ", hasPendingPayload=" + (pendingGraphPayloadJson != null));

                if (pendingGraphPayloadJson != null) {
                    String js = "renderGraph(" + pendingGraphPayloadJson + ")";
                    String preview = js.length() > 200 ? js.substring(0, 200) + "..." : js;
                    LogManager.logI(TAG, "[WEBVIEW] Evaluating JS: " + preview);
                    view.evaluateJavascript(js, value ->
                            LogManager.logI(TAG, "[WEBVIEW] renderGraph() completed with result: " + value)
                    );
                }
            }
        });

        webViewGraph.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                LogManager.logI(TAG, "[WEBVIEW_CONSOLE] " + consoleMessage.message() +
                        " @" + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber());
                return true;
            }
        });
        
        // Add JavaScript Interface for Agent operations
        webViewGraph.addJavascriptInterface(new WebAppInterface(), "AgentBridge");

        webViewLoaded = false;
        webViewGraph.loadUrl("file:///android_asset/knowledge_graph.html");

        // Setup SeekBar (range: 10-500, default: 50)
        seekBarTopN.setMax(490); // 500 - 10
        seekBarTopN.setProgress(40); // 50 - 10
        textViewTopNValue.setText("50");
        
        seekBarTopN.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentTopN = progress + 10; // Convert to actual value (10-500)
                textViewTopNValue.setText(String.valueOf(currentTopN));
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                // No action needed
            }
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Immediately refresh graph when user releases SeekBar
                if (currentKbName != null) {
                    loadAndDisplayGraph();
                }
            }
        });
        
        // Setup knowledge base spinner
        spinnerKnowledgeBase.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position > 0) { // Skip hint item
                    currentKbName = knowledgeBaseNames.get(position - 1);
                    loadAndDisplayGraph();
                } else {
                    currentKbName = null;
                    pendingGraphPayloadJson = null;
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                currentKbName = null;
                pendingGraphPayloadJson = null;
            }
        });
        
        // Setup share button
        buttonShare.setOnClickListener(v -> shareGraph());
    }
    
    private void loadKnowledgeBases() {
        getExecutor().execute(() -> {
            try {
                String dataRootPath = ConfigManager.getDataRootPath(requireContext());
                String kbRootPath = dataRootPath + "/knowledge_bases";
                
                if (kbRootPath.isEmpty()) {
                    mainHandler.post(() -> {
                        Toast.makeText(requireContext(), 
                            getString(R.string.graph_viewer_no_kb_selected), 
                            Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                
                File kbRoot = new File(kbRootPath);
                if (!kbRoot.exists() || !kbRoot.isDirectory()) {
                    mainHandler.post(() -> {
                        Toast.makeText(requireContext(), 
                            getString(R.string.graph_viewer_empty), 
                            Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                
                // Find all knowledge bases
                knowledgeBaseNames.clear();
                File[] kbDirs = kbRoot.listFiles(File::isDirectory);
                if (kbDirs != null) {
                    for (File kbDir : kbDirs) {
                        // Check if it has a knowledge_graph.db file
                        File graphDb = new File(kbDir, "knowledge_graph.db");
                        if (graphDb.exists()) {
                            knowledgeBaseNames.add(kbDir.getName());
                        }
                    }
                }
                
                // Update UI on main thread
                mainHandler.post(() -> {
                    List<String> spinnerItems = new ArrayList<>();
                    spinnerItems.add(getString(R.string.graph_viewer_kb_hint));
                    spinnerItems.addAll(knowledgeBaseNames);
                    
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(
                        requireContext(),
                        android.R.layout.simple_spinner_item,
                        spinnerItems
                    );
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spinnerKnowledgeBase.setAdapter(adapter);
                });
                
            } catch (Exception e) {
                LogManager.logE(TAG, "Load knowledge bases failed", e);
                mainHandler.post(() -> {
                    Toast.makeText(requireContext(), 
                        getString(R.string.graph_viewer_load_error, e.getMessage()), 
                        Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
    
    private void loadAndDisplayGraph() {
        if (currentKbName == null) {
            return;
        }

        LogManager.logI(TAG, "[GRAPH] loadAndDisplayGraph() called, kb=" + currentKbName +
                ", topN=" + currentTopN);

        mainHandler.post(() -> {
            Toast.makeText(requireContext(),
                    getString(R.string.graph_viewer_loading),
                    Toast.LENGTH_SHORT).show();
        });

        getExecutor().execute(() -> {
            try {
                String dataRootPath = ConfigManager.getDataRootPath(requireContext());
                String kbRootPath = dataRootPath + "/knowledge_bases";
                File kbDir = new File(kbRootPath, currentKbName);
                File graphDbFile = new File(kbDir, "knowledge_graph.db");

                if (!graphDbFile.exists()) {
                    mainHandler.post(() -> {
                        Toast.makeText(requireContext(),
                                getString(R.string.graph_viewer_empty),
                                Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                KnowledgeGraphDatabase graphDb = new KnowledgeGraphDatabase(
                        requireContext(),
                        graphDbFile.getAbsolutePath(),
                        currentKbName
                );

                KnowledgeGraphExporter exporter = new KnowledgeGraphExporter(
                        requireContext(),
                        graphDb
                );

                KnowledgeGraphExporter.GraphStats stats = exporter.getGraphStats();
                List<KnowledgeGraphExporter.EntityInfo> topEntities = exporter.getTopEntities(currentTopN);
                List<KnowledgeGraphExporter.EdgeInfo> topEdges = exporter.getTopEdges(currentTopN);

                LogManager.logI(TAG, "[GRAPH] Stats for kb=" + currentKbName +
                        ": chunks=" + stats.totalChunks +
                        ", entities=" + stats.totalEntities +
                        ", edges=" + stats.totalEdges);
                LogManager.logI(TAG, "[GRAPH] Top entities size=" + topEntities.size() +
                        ", top edges size=" + topEdges.size());

                if (stats.totalEntities == 0 || topEntities.isEmpty()) {
                    LogManager.logI(TAG, "[GRAPH] No entities found for knowledge base: " + currentKbName);
                }
                if (topEdges.isEmpty()) {
                    LogManager.logI(TAG, "[GRAPH] No edges found for knowledge base: " + currentKbName);
                }

                KnowledgeGraphDatabase.DatabaseMetadata metadata = graphDb.getMetadata();
                int hubThreshold = metadata.getHubThreshold();
                String runtimeHubs = metadata.getRuntimeHubEntities();

                graphDb.close();

                final int hubThresholdFinal = hubThreshold;
                final String runtimeHubsFinal = runtimeHubs;

                JSONObject payload = buildGraphPayload(currentKbName, currentTopN, stats,
                        topEntities, topEdges, hubThresholdFinal, runtimeHubsFinal);
                final String payloadJson = payload.toString();
                LogManager.logI(TAG, "[GRAPH] Payload JSON length=" + payloadJson.length());

                mainHandler.post(() -> {
                    pendingGraphPayloadJson = payloadJson;
                    LogManager.logI(TAG, "[GRAPH] Posting payload to WebView, webViewLoaded=" + webViewLoaded);
                    if (webViewGraph != null && webViewLoaded) {
                        String js = "renderGraph(" + payloadJson + ")";
                        String preview = js.length() > 200 ? js.substring(0, 200) + "..." : js;
                        LogManager.logI(TAG, "[WEBVIEW] Evaluating JS from main thread: " + preview);
                        webViewGraph.evaluateJavascript(js, value ->
                                LogManager.logI(TAG, "[WEBVIEW] renderGraph() (from main thread) completed with result: " + value)
                        );
                    }
                });

            } catch (Exception e) {
                LogManager.logE(TAG, "Load and display graph failed", e);
                mainHandler.post(() -> {
                    Toast.makeText(requireContext(),
                            getString(R.string.graph_viewer_load_error, e.getMessage()),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private JSONObject buildGraphPayload(String kbName,
                                         int topN,
                                         KnowledgeGraphExporter.GraphStats stats,
                                         List<KnowledgeGraphExporter.EntityInfo> entities,
                                         List<KnowledgeGraphExporter.EdgeInfo> edges,
                                         int hubThreshold,
                                         String runtimeHubEntities) throws Exception {
        JSONObject root = new JSONObject();
        root.put("kbName", kbName);
        root.put("topN", topN);

        JSONObject statsJson = new JSONObject();
        statsJson.put("totalChunks", stats.totalChunks);
        statsJson.put("totalEntities", stats.totalEntities);
        statsJson.put("totalEdges", stats.totalEdges);
        statsJson.put("avgEntitiesPerChunk", stats.avgEntitiesPerChunk);
        root.put("stats", statsJson);

        Map<String, JSONObject> nodeMap = new HashMap<>();

        for (KnowledgeGraphExporter.EntityInfo entity : entities) {
            JSONObject node = nodeMap.get(entity.text);
            if (node == null) {
                node = new JSONObject();
                node.put("id", entity.text);
                node.put("name", entity.text);
                node.put("degree", 0);
                node.put("frequency", entity.frequency);
                nodeMap.put(entity.text, node);
            } else {
                if (!node.has("frequency")) {
                    node.put("frequency", entity.frequency);
                }
            }
        }

        JSONArray edgesArray = new JSONArray();
        JSONArray relationsArray = new JSONArray();
        for (KnowledgeGraphExporter.EdgeInfo edge : edges) {
            String from = edge.fromEntity;
            String to = edge.toEntity;

            JSONObject fromNode = nodeMap.get(from);
            if (fromNode == null) {
                fromNode = new JSONObject();
                fromNode.put("id", from);
                fromNode.put("name", from);
                fromNode.put("degree", 0);
                fromNode.put("frequency", 0);
                nodeMap.put(from, fromNode);
            }
            fromNode.put("degree", fromNode.optInt("degree", 0) + 1);

            JSONObject toNode = nodeMap.get(to);
            if (toNode == null) {
                toNode = new JSONObject();
                toNode.put("id", to);
                toNode.put("name", to);
                toNode.put("degree", 0);
                toNode.put("frequency", 0);
                nodeMap.put(to, toNode);
            }
            toNode.put("degree", toNode.optInt("degree", 0) + 1);

            JSONObject edgeJson = new JSONObject();
            edgeJson.put("source", from);
            edgeJson.put("target", to);
            edgeJson.put("weight", edge.weight);
            edgesArray.put(edgeJson);

            JSONObject relationJson = new JSONObject();
            relationJson.put("a", from);
            relationJson.put("b", to);
            relationJson.put("count", edge.weight);
            relationsArray.put(relationJson);
        }

        JSONArray nodesArray = new JSONArray();
        for (JSONObject node : nodeMap.values()) {
            nodesArray.put(node);
        }

        root.put("nodes", nodesArray);
        root.put("edges", edgesArray);
        root.put("relations", relationsArray);
        root.put("hubThreshold", hubThreshold);
        root.put("runtimeHubEntities", runtimeHubEntities != null ? runtimeHubEntities : "");

        return root;
    }
    
    private void shareGraph() {
        if (currentKbName == null) {
            Toast.makeText(requireContext(), 
                getString(R.string.graph_viewer_no_kb_selected), 
                Toast.LENGTH_SHORT).show();
            return;
        }
        
        getExecutor().execute(() -> {
            try {
                String dataRootPath = ConfigManager.getDataRootPath(requireContext());
                String kbRootPath = dataRootPath + "/knowledge_bases";
                File kbDir = new File(kbRootPath, currentKbName);
                File graphDbFile = new File(kbDir, "knowledge_graph.db");
                
                if (!graphDbFile.exists()) {
                    mainHandler.post(() -> {
                        Toast.makeText(requireContext(), 
                            getString(R.string.graph_viewer_empty), 
                            Toast.LENGTH_SHORT).show();
                    });
                    return;
                }
                
                // Generate Markdown
                KnowledgeGraphDatabase graphDb = new KnowledgeGraphDatabase(
                    requireContext(), 
                    graphDbFile.getAbsolutePath(), 
                    currentKbName
                );
                
                KnowledgeGraphExporter exporter = new KnowledgeGraphExporter(
                    requireContext(), 
                    graphDb
                );
                
                String markdown = exporter.exportToMarkdown(currentKbName, currentTopN);
                
                graphDb.close();
                
                // Share via system share dialog
                mainHandler.post(() -> {
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("text/plain");
                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, 
                        getString(R.string.graph_viewer_share_subject, currentKbName));
                    shareIntent.putExtra(Intent.EXTRA_TEXT, markdown);
                    
                    startActivity(Intent.createChooser(shareIntent, 
                        getString(R.string.graph_viewer_share_title)));
                });
                
            } catch (Exception e) {
                LogManager.logE(TAG, "Share graph failed", e);
                mainHandler.post(() -> {
                    Toast.makeText(requireContext(), 
                        getString(R.string.graph_viewer_load_error, e.getMessage()), 
                        Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
    
    
    @Override
    public void onResume() {
        super.onResume();
        // Show back button and set title in ActionBar
        if (getActivity() != null && ((androidx.appcompat.app.AppCompatActivity) getActivity()).getSupportActionBar() != null) {
            ((androidx.appcompat.app.AppCompatActivity) getActivity()).getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            ((androidx.appcompat.app.AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.graph_viewer_title);
        }
    }
    
    @Override
    public void onPause() {
        super.onPause();
        // Title restoration is handled in MenuProvider to avoid multiple calls
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        
        // Unregister BroadcastReceiver
        if (agentWebReceiver != null && getContext() != null) {
            try {
                getContext().unregisterReceiver(agentWebReceiver);
            } catch (Exception e) {
                LogManager.logE(TAG, "Failed to unregister receiver", e);
            }
            agentWebReceiver = null;
        }
        
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
        executor = null;
    }
    
    // ============================================================================
    // Agent Web Operations Support
    // ============================================================================
    
    /**
     * JavaScript Interface for Agent to get page content
     */
    private class WebAppInterface {
        @JavascriptInterface
        public void setPageContent(String content) {
            lastPageContent = content;
            LogManager.logI(TAG, "[AGENT_WEB] Page content captured: " + content.length() + " chars");
            
            // Send broadcast back to Agent with content
            Intent intent = new Intent("com.example.offlineai.AGENT_WEB_CONTENT_RESULT");
            intent.putExtra("content", content);
            intent.setPackage(requireContext().getPackageName());
            requireContext().sendBroadcast(intent);
        }
    }
    
    /**
     * Register BroadcastReceiver to listen for Agent Web operations
     */
    private void registerAgentWebReceiver() {
        if (getContext() == null) return;
        
        agentWebReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                
                LogManager.logI(TAG, "[AGENT_WEB] Received broadcast: " + action);
                
                switch (action) {
                    case "com.example.offlineai.AGENT_WEB_OPEN":
                        String url = intent.getStringExtra("url");
                        if (url != null) {
                            handleAgentWebOpen(url);
                        }
                        break;
                        
                    case "com.example.offlineai.AGENT_WEB_GET_CONTENT":
                        handleAgentWebGetContent();
                        break;
                        
                    case "com.example.offlineai.AGENT_WEB_EXECUTE_JS":
                        String script = intent.getStringExtra("script");
                        if (script != null) {
                            handleAgentWebExecuteJs(script);
                        }
                        break;
                }
            }
        };
        
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.example.offlineai.AGENT_WEB_OPEN");
        filter.addAction("com.example.offlineai.AGENT_WEB_GET_CONTENT");
        filter.addAction("com.example.offlineai.AGENT_WEB_EXECUTE_JS");
        getContext().registerReceiver(agentWebReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        
        LogManager.logI(TAG, "[AGENT_WEB] BroadcastReceiver registered");
    }
    
    /**
     * Handle Agent request to open URL
     */
    private void handleAgentWebOpen(String url) {
        LogManager.logI(TAG, "[AGENT_WEB] Opening URL: " + url);
        mainHandler.post(() -> {
            if (webViewGraph != null) {
                webViewGraph.loadUrl(url);
                Toast.makeText(requireContext(), 
                    "Agent: Opening " + url, 
                    Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    /**
     * Handle Agent request to get page content
     */
    private void handleAgentWebGetContent() {
        LogManager.logI(TAG, "[AGENT_WEB] Getting page content");
        mainHandler.post(() -> {
            if (webViewGraph != null) {
                // Inject JavaScript to extract page content
                String js = "(function() {" +
                    "  var title = document.title;" +
                    "  var url = window.location.href;" +
                    "  var bodyText = document.body.innerText;" +
                    "  var links = Array.from(document.querySelectorAll('a')).map(a => ({text: a.innerText, href: a.href}));" +
                    "  var buttons = Array.from(document.querySelectorAll('button, input[type=button], input[type=submit]')).map(b => b.innerText || b.value);" +
                    "  var inputs = Array.from(document.querySelectorAll('input[type=text], input[type=email], input[type=password], textarea')).map(i => ({type: i.type, name: i.name, placeholder: i.placeholder}));" +
                    "  var result = JSON.stringify({" +
                    "    title: title," +
                    "    url: url," +
                    "    text: bodyText.substring(0, 5000)," +
                    "    links: links.slice(0, 20)," +
                    "    buttons: buttons.slice(0, 10)," +
                    "    inputs: inputs.slice(0, 10)" +
                    "  });" +
                    "  AgentBridge.setPageContent(result);" +
                    "})();";
                
                webViewGraph.evaluateJavascript(js, value -> 
                    LogManager.logI(TAG, "[AGENT_WEB] Content extraction completed")
                );
            }
        });
    }
    
    /**
     * Handle Agent request to execute JavaScript
     */
    private void handleAgentWebExecuteJs(String script) {
        LogManager.logI(TAG, "[AGENT_WEB] Executing JS: " + script.substring(0, Math.min(100, script.length())));
        mainHandler.post(() -> {
            if (webViewGraph != null) {
                webViewGraph.evaluateJavascript(script, value -> {
                    LogManager.logI(TAG, "[AGENT_WEB] JS execution completed, result: " + value);
                    
                    // Send result back to Agent
                    Intent intent = new Intent("com.example.offlineai.AGENT_WEB_JS_RESULT");
                    intent.putExtra("result", value != null ? value : "null");
                    intent.setPackage(requireContext().getPackageName());
                    requireContext().sendBroadcast(intent);
                });
            }
        });
    }
}
