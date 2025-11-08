package com.example.offlineai;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public class TextEditorFragment extends Fragment {
    private static final String TAG = "TextEditorFragment";

    private TextView textViewFilePath;
    private TextView textViewStatus;
    private EditText editTextContent;
    private Button buttonBrowseFile;
    private Button buttonSaveFile;

    private ActivityResultLauncher<Intent> openDocumentLauncher;

    private Uri currentDocumentUri;
    private String currentFileDisplayName = "";
    private boolean hasUnsavedChanges = false;
    private boolean ignoreTextChanges = false;
    private String lastSavedContent = "";
    private String stopwordsDirPath = "";

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        openDocumentLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        Uri uri = data.getData();
                        if (uri == null) {
                            Toast.makeText(requireContext(), R.string.toast_cannot_get_selected_path, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        int takeFlags = data.getFlags()
                                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        try {
                            requireContext().getContentResolver().takePersistableUriPermission(uri, takeFlags);
                        } catch (Exception e) {
                            LogManager.logW(TAG, "[TEXT_EDITOR] Cannot take persistable URI permission: " + e.getMessage());
                        }
                        handleDocumentPicked(uri);
                    }
                }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_text_editor, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        textViewFilePath = view.findViewById(R.id.textViewFilePath);
        textViewStatus = view.findViewById(R.id.textViewStatus);
        editTextContent = view.findViewById(R.id.editTextContent);
        buttonBrowseFile = view.findViewById(R.id.buttonBrowseFile);
        buttonSaveFile = view.findViewById(R.id.buttonSaveFile);

        buttonBrowseFile.setOnClickListener(v -> openDocumentPicker());
        buttonSaveFile.setOnClickListener(v -> saveCurrentDocument());

        if (editTextContent != null) {
            editTextContent.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    // no-op
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    // no-op
                }

                @Override
                public void afterTextChanged(Editable s) {
                    if (ignoreTextChanges) {
                        return;
                    }
                    String current = s != null ? s.toString() : "";
                    hasUnsavedChanges = !current.equals(lastSavedContent);
                }
            });
        }

        try {
            ConfigManager.ensureDefaultStopwordsExample(requireContext());
            stopwordsDirPath = ConfigManager.getStopwordsDirectoryPath(requireContext());
        } catch (Exception e) {
            LogManager.logE(TAG, "[TEXT_EDITOR] Failed to initialize stopwords directory: " + e.getMessage(), e);
            stopwordsDirPath = "";
        }

        // Initialize label without preset path; actual full path is only shown after user selects a file
        textViewFilePath.setText(getString(R.string.label_text_editor_files));

        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                // no fragment-specific menu items
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                if (menuItem.getItemId() == android.R.id.home) {
                    handleBackNavigation();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackNavigation();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), callback);

        applyGlobalTextSize();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof AppCompatActivity) {
            AppCompatActivity activity = (AppCompatActivity) getActivity();
            if (activity.getSupportActionBar() != null) {
                activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                activity.getSupportActionBar().setTitle(R.string.title_text_editor);
            }
        }
        applyGlobalTextSize();
    }

    private void openDocumentPicker() {
        if (openDocumentLauncher == null) {
            Toast.makeText(requireContext(), R.string.error_file_read, Toast.LENGTH_SHORT).show();
            return;
        }

        if (hasUnsavedChanges && currentDocumentUri != null) {
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.dialog_title_warning)
                    .setMessage(R.string.dialog_message_unsaved_changes)
                    .setPositiveButton(R.string.common_save, (dialog, which) -> {
                        if (saveCurrentDocument()) {
                            launchDocumentPicker();
                        }
                    })
                    .setNegativeButton(R.string.common_cancel, null)
                    .show();
        } else {
            launchDocumentPicker();
        }
    }

    private void launchDocumentPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = new String[]{"application/json", "text/plain"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        openDocumentLauncher.launch(intent);
    }

    private void handleDocumentPicked(Uri uri) {
        currentDocumentUri = uri;
        currentFileDisplayName = getDisplayNameFromUri(uri);
        String displayPath;
        if (stopwordsDirPath != null && !stopwordsDirPath.isEmpty()) {
            // Display logical full path under stopwords directory
            displayPath = stopwordsDirPath + "/" + currentFileDisplayName;
        } else {
            // Fallback: show filename only when directory path is unavailable
            displayPath = currentFileDisplayName;
        }
        textViewFilePath.setText(getString(R.string.label_text_editor_files) + " " + displayPath);
        loadDocumentContent(uri);
    }

    private String getDisplayNameFromUri(Uri uri) {
        String fileName = "";
        try {
            Cursor cursor = requireContext().getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex);
                }
                cursor.close();
            }
        } catch (Exception e) {
            LogManager.logE(TAG, "[TEXT_EDITOR] Failed to get display name: " + e.getMessage(), e);
        }
        if (fileName == null || fileName.isEmpty()) {
            fileName = uri.getLastPathSegment();
        }
        return fileName != null ? fileName : "";
    }

    private void loadDocumentContent(Uri uri) {
        if (getContext() == null) {
            return;
        }
        try (InputStream inputStream = requireContext().getContentResolver().openInputStream(uri);
             InputStreamReader isr = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(isr)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            String content = sb.toString();
            ignoreTextChanges = true;
            editTextContent.setText(content);
            ignoreTextChanges = false;
            lastSavedContent = content;
            hasUnsavedChanges = false;
            textViewStatus.setText(getString(R.string.text_editor_load_success, currentFileDisplayName));
            LogManager.logD(TAG, "[TEXT_EDITOR] Loaded document: " + uri);
        } catch (Exception e) {
            LogManager.logE(TAG, "[TEXT_EDITOR] Failed to read document: " + e.getMessage(), e);
            Toast.makeText(requireContext(), R.string.error_file_read, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean saveCurrentDocument() {
        if (getContext() == null) {
            return false;
        }
        if (currentDocumentUri == null) {
            Toast.makeText(requireContext(), R.string.text_editor_invalid_selection, Toast.LENGTH_SHORT).show();
            return false;
        }
        String content = editTextContent != null ? editTextContent.getText().toString() : "";
        try (OutputStream outputStream = requireContext().getContentResolver().openOutputStream(currentDocumentUri);
             OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
            writer.write(content != null ? content : "");
            writer.flush();
            lastSavedContent = content != null ? content : "";
            hasUnsavedChanges = false;
            textViewStatus.setText(getString(R.string.text_editor_save_success, currentFileDisplayName));
            Toast.makeText(requireContext(), R.string.text_editor_save_success_toast, Toast.LENGTH_SHORT).show();
            LogManager.logD(TAG, "[TEXT_EDITOR] Saved document: " + currentDocumentUri);
            return true;
        } catch (Exception e) {
            LogManager.logE(TAG, "[TEXT_EDITOR] Failed to save document: " + e.getMessage(), e);
            textViewStatus.setText(getString(R.string.text_editor_save_failed, currentFileDisplayName));
            Toast.makeText(requireContext(), R.string.text_editor_save_failed_toast, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void handleBackNavigation() {
        if (!hasUnsavedChanges || currentDocumentUri == null) {
            navigateBackToMain();
            return;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.dialog_title_warning)
                .setMessage(R.string.dialog_message_unsaved_changes)
                .setPositiveButton(R.string.common_save, (dialog, which) -> {
                    if (saveCurrentDocument()) {
                        navigateBackToMain();
                    }
                })
                .setNegativeButton(R.string.common_cancel, null)
                .show();
    }

    private void navigateBackToMain() {
        if (!isAdded() || getActivity() == null) {
            LogManager.logW(TAG, "[TEXT_EDITOR] Fragment not attached, cannot navigate back");
            return;
        }
        AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            activity.getSupportActionBar().setTitle(R.string.app_name);
        }
        activity.findViewById(R.id.container).setVisibility(View.GONE);
        activity.findViewById(R.id.viewPager).setVisibility(View.VISIBLE);

        androidx.fragment.app.FragmentManager fm = activity.getSupportFragmentManager();
        while (fm.getBackStackEntryCount() > 0) {
            fm.popBackStackImmediate();
        }
    }

    private void applyGlobalTextSize() {
        if (getContext() == null) {
            return;
        }
        float fontSize = ConfigManager.getGlobalTextSize(requireContext());
        if (editTextContent != null) {
            editTextContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        }
    }
}
