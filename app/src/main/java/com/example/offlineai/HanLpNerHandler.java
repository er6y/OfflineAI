package com.example.offlineai;

import com.hankcs.hanlp.dictionary.CustomDictionary;
import com.hankcs.hanlp.seg.common.Term;
import com.hankcs.hanlp.tokenizer.StandardTokenizer;  // Portable版自带，无需额外模型

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * HanLP NER Handler
 * Uses HanLP for Named Entity Recognition, completely replacing LLM NER
 */
public class HanLpNerHandler {
    private static final String TAG = "HanLpNerHandler";
    
    private ExecutorService mExecutor;
    private static final int THREAD_COUNT = 2;
    
    // Dictionary status fields for logging/UI
    private boolean dictionaryLoaded = false;
    private String dictionaryErrorMessage = null;
    private String dictionaryPath = null;
    private int loadedWordCount = 0;
    
    public HanLpNerHandler(String dictPath) {
        mExecutor = Executors.newFixedThreadPool(THREAD_COUNT);
        
        this.dictionaryPath = dictPath;
        if (dictPath != null && !dictPath.isEmpty()) {
            loadDictionary(dictPath);
        } else {
            dictionaryLoaded = false;
            dictionaryErrorMessage = null;
        }
        
        if (dictionaryLoaded) {
            LogManager.logI(TAG, "HanLP NER initialized with dictionary: " + dictPath +
                " (loaded " + loadedWordCount + " words)");
        } else if (dictPath != null && !dictPath.isEmpty()) {
            LogManager.logW(TAG, "HanLP NER initialized without custom dictionary (failed to load: " + dictPath + ")");
        } else {
            LogManager.logI(TAG, "HanLP NER initialized with internal dictionary only (no custom dictionary)");
        }
    }
    
    private void loadDictionary(String dictPath) {
        try {
            File dictFile = new File(dictPath);
            if (!dictFile.exists()) {
                dictionaryLoaded = false;
                dictionaryErrorMessage = "Dictionary not found";
                LogManager.logW(TAG, "Dictionary not found: " + dictPath);
                return;
            }
            
            String jsonContent = readFile(dictFile);
            if (jsonContent == null) {
                dictionaryLoaded = false;
                dictionaryErrorMessage = "Dictionary file is empty";
                LogManager.logE(TAG, "Dictionary file is empty: " + dictPath);
                return;
            }
            
            String trimmed = jsonContent.trim();
            if (trimmed.isEmpty()) {
                dictionaryLoaded = false;
                dictionaryErrorMessage = "Dictionary file is empty";
                LogManager.logE(TAG, "Dictionary file is empty after trim: " + dictPath);
                return;
            }
            
            JSONArray entries;
            if (trimmed.startsWith("[")) {
                // Top-level JSON array
                entries = new JSONArray(trimmed);
            } else {
                // Top-level object, expect an "entries" array
                JSONObject json = new JSONObject(trimmed);
                if (json.has("entries")) {
                    entries = json.getJSONArray("entries");
                } else {
                    throw new org.json.JSONException("Dictionary JSON must have an 'entries' array or be a JSON array");
                }
            }
            
            int count = 0;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                String word = entry.getString("word");
                String nature = entry.optString("nature", "nz");
                int frequency = entry.optInt("frequency", 10000);
                
                CustomDictionary.add(word, nature + " " + frequency);
                count++;
                
                JSONArray aliases = entry.optJSONArray("aliases");
                if (aliases != null) {
                    for (int j = 0; j < aliases.length(); j++) {
                        String alias = aliases.getString(j);
                        CustomDictionary.add(alias, nature + " " + frequency);
                        count++;
                    }
                }
            }
            
            loadedWordCount = count;
            dictionaryLoaded = true;
            dictionaryErrorMessage = null;
            LogManager.logI(TAG, "Loaded " + count + " words from dictionary");
            
        } catch (Exception e) {
            dictionaryLoaded = false;
            dictionaryErrorMessage = e.getMessage();
            LogManager.logE(TAG, "Failed to load dictionary: " + dictPath, e);
        }
    }
    
    public List<NerResult> extractEntitiesBatch(List<String> texts) {
        List<Future<NerResult>> futures = new ArrayList<>();
        
        for (String text : texts) {
            futures.add(mExecutor.submit(() -> extractEntities(text)));
        }
        
        List<NerResult> results = new ArrayList<>();
        for (Future<NerResult> future : futures) {
            try {
                results.add(future.get());
            } catch (Exception e) {
                LogManager.logE(TAG, "Extraction failed", e);
                results.add(new NerResult("Extraction failed: " + e.getMessage()));
            }
        }
        
        return results;
    }
    
    public NerResult extractEntities(String text) {
        long start = System.currentTimeMillis();
        
        try {
            List<NerResult.Entity> entities = new ArrayList<>();
            
            // Use StandardTokenizer (Portable版自带，无需额外模型)
            // NLPTokenizer需要data.zip模型包（50MB+），不适合Android
            List<Term> terms = StandardTokenizer.segment(text);
            
            for (Term term : terms) {
                String word = term.word;
                String nature = term.nature.toString();
                
                // 过滤掉无意义的词性
                if (shouldSkip(nature, word)) {
                    continue;
                }
                
                entities.add(new NerResult.Entity(word, nature));
            }
            
            long elapsed = System.currentTimeMillis() - start;
            LogManager.logI(TAG, String.format("Extracted %d entities in %dms (using StandardTokenizer)", 
                entities.size(), elapsed));
            
            return new NerResult(entities);
            
        } catch (Exception e) {
            LogManager.logE(TAG, "Extraction error", e);
            return new NerResult("Error: " + e.getMessage());
        }
    }
    
    private boolean shouldSkip(String nature, String word) {
        if (nature.equals("w")) return true;
        if (nature.equals("u") || nature.equals("c") || nature.equals("p")) return true;
        if (nature.equals("d") || nature.equals("e")) return true;
        if (word.length() == 1) return true;
        return false;
    }
    
    public void release() {
        if (mExecutor != null) {
            mExecutor.shutdown();
            LogManager.logI(TAG, "HanLP NER Handler released");
        }
    }
    
    public boolean isDictionaryLoaded() {
        return dictionaryLoaded;
    }
    
    public String getDictionaryErrorMessage() {
        return dictionaryErrorMessage;
    }
    
    public int getLoadedWordCount() {
        return loadedWordCount;
    }
    
    public String getDictionaryPath() {
        return dictionaryPath;
    }
    
    private String readFile(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
    
    public static class NerResult {
        private List<Entity> entities;
        private String error;
        
        public NerResult(List<Entity> entities) {
            this.entities = entities;
        }
        
        public NerResult(String error) {
            this.error = error;
            this.entities = new ArrayList<>();
        }
        
        public boolean isSuccess() {
            return error == null;
        }
        
        public List<Entity> getEntities() {
            return entities;
        }
        
        public String getError() {
            return error;
        }
        
        public static class Entity {
            public final String text;
            public final String type;
            public final int start;
            public final int end;
            public final float confidence;
            
            public Entity(String text, String type) {
                this(text, type, -1, -1, 1.0f);
            }
            
            public Entity(String text, String type, int start, int end) {
                this(text, type, start, end, 1.0f);
            }
            
            public Entity(String text, String type, int start, int end, float confidence) {
                this.text = text;
                this.type = type;
                this.start = start;
                this.end = end;
                this.confidence = confidence;
            }
            
            public String getText() { return text; }
            public String getType() { return type; }
            public int getStart() { return start; }
            public int getEnd() { return end; }
            public float getConfidence() { return confidence; }
        }
    }
}
