# OfflineAI - Offline-First On-Device AI Assistant

> When the net is gone, the mind stays on.

OfflineAI is an offline-first Android on-device AI assistant. It focuses on providing **reliable question answering, multimodal reasoning, and local retrieval-augmented generation (RAG)** in environments where the network is slow, unstable, or completely unavailable, and where data privacy is critical.

All core capabilities – large language models, vision-language models, speech recognition, speech synthesis, vector search, knowledge graph RAG, and image generation – are executed **entirely on device**.

---

## 1. Overview

OfflineAI turns a laptop or Android device into a **portable AI workspace** for:

- Working in remote or low-connectivity environments.
- Operating in high-security scenarios where data must not leave the device.
- Managing and querying private documents as a personal knowledge base.

Key goals:

- **Offline-first**: Core workflows continue to work without any network connection.
- **Multimodal**: Text, images, and audio are processed locally.
- **Explainable retrieval**: Vector RAG is enhanced with a knowledge graph to make context and entities visible and debuggable.
- **Usable in the field**: Simple UI flows for building knowledge bases, asking questions, taking quick notes, and reviewing results.

---

## 2. Core Features

- **AI chat & conversation management**  
  Multiple conversations can be created, saved, loaded, and switched. Each conversation keeps its own history and settings.

- **Speech input & output (ASR/TTS)**  
  Local automatic speech recognition converts speech to text, and text-to-speech makes responses audible, enabling hands-free interaction.

- **Multimodal reasoning**  
  Supports vision-language models for image understanding and reasoning, enabling use cases such as reading diagrams, inspecting photos, or explaining on-site pictures.

- **Offline RAG (Vector + Graph)**  
  Combines vector retrieval with a document-level knowledge graph:
  - Vector RAG retrieves semantically relevant chunks.
  - Graph RAG expands around key entities and hubs to enrich context.
  - A dedicated graph viewer helps inspect entities, relations, and hub behavior.

- **Local image generation (Diffusion)**  
  Runs diffusion-based text-to-image models on device for illustration and visualization.

- **Knowledge bases & notes**  
  - Build multiple knowledge bases from local files.
  - Use “quick notes” as a lightweight way to capture knowledge and immediately make it searchable.
  - Manage notes and query them through the same RAG pipeline.

- **Model & parameter control UI**  
  A settings area exposes key parameters (model selection, RAG depth, backend choice, max tokens, temperature, etc.) to balance quality, latency, and resource usage on different devices.

- **Claw Agent (on-device autonomous agent)**
  An on-device ReAct-style agent that can drive the phone itself: tap/swipe/type, read/write files, run Python scripts, browse web pages, query/insert the knowledge base, show results in a floating window, and schedule recurring jobs. Extended via installable **skills** (each with a `SKILL.md`), guided by user-written **agent_user** prompts, and can persist task experience into a dedicated **AgentKB** knowledge base. See Section 3 and the User Guide for details.

---

## 3. Claw Agent (On-Device Autonomous Agent)

Claw is OfflineAI's agent mode. It runs the same local (or optional remote) LLM in a tool-calling loop against the phone itself — no cloud agent service, no data egress.

### 3.1 How It Works (ReAct Loop)

Each step the agent receives a screenshot (when needed), the last action result, a compact memory, and a catalog of currently available skills. It emits one or more actions in JSON, the executor runs them, and the loop continues until the model emits `terminate`. Two-layer memory:

- **`context.fact`** — append-only, long-term facts (resolved paths, user confirmations, key coordinates, business IDs). Survives the whole task.
- **`context.text`** — per-step scratchpad (current screen, last error, next plan). Overwritten each step.
- **`data_memory`** — KV store for larger business payloads; only keys are shown every step, values fetched on demand or referenced via `{{key}}` in `terminate`.

### 3.2 Action Space (High Level)

Grouped roughly into:

- **UI automation** — click / long_press / double_click / type / swipe / drag / system_button / open (launch app) / get_app_list.
- **File / text** — create_file / read_file / write_file / read_lines / edit_lines / grep / rename_file / copy_file / delete_file / list_dir / mkdir / search_files.
- **Python** — `python` (argv-only, like `subprocess.run`), `python_status`, `python_kill`. Single instance, sync by default with background fallback on timeout.
- **Web** — web_open / web_get_content (DOM + text) / web_execute_js. All share cookies and session with a persistent WebView.
- **Knowledge base** — kb_insert / kb_delete against AgentKB; the task itself can also query RAG at task start.
- **Scheduling** — schedule_get / schedule_set against the 4 scheduled-task slots (master switch stays user-controlled).
- **User interaction** — ask_user (optionally with a `url` to pop a WebView for login/verification), show_output (Markdown + size: small/medium/large in floating window), terminate (final Markdown result + optional file attachments).

### 3.3 Experience Library (AgentKB)

When "Experience Summary" is enabled, after a successful task the model is asked to summarize what worked and emit KB actions that write the distilled experience into a reserved knowledge base called **AgentKB**. At the start of the next task, AgentKB is queried once and its top-K hits are injected as background, so the agent progressively gets better at recurring workflows without any offline training.

### 3.4 agent_user Prompts (per-scenario user briefs)

Under `{dataRoot}/agent_user/` each `.txt` file is a user-written prompt preset. Format:

```
# comment
@once  one-shot line injected only at Step 0
@step  line injected every step
@once { multi-line block for Step 0 only }
@step { multi-line block for every step }
bare line -> treated as @step
```

You can switch preset from the UI dropdown. Scheduled tasks pick a preset per slot. The app ships `common_agent.txt` and a few examples (e.g. THS trading, coffee ordering). Built-in assets in `app/src/main/assets/agent_user/` are only copied when the file doesn't exist yet, so edits on device are preserved.

### 3.5 Python Support (Chaquopy)

Python 3.10 is embedded via Chaquopy. Scripts run in-process with argv semantics — no shell, no pipes. Pre-installed third-party packages cover the common skill needs:

- Office: `python-docx`, `python-pptx`, `openpyxl`
- PDF: `pypdf`, `pdfplumber`, `fpdf2`
- Data / imaging: `pandas`, `numpy`, `pillow`, `chardet`
- Network: `requests`, `beautifulsoup4`
- Utils: `zipfile36`, `python-dotenv`

`${SKILL_DIR}` and `${WORKSPACE}` placeholders in any action's string fields are auto-resolved to the real skills dir and the default agent workspace.

### 3.6 Skills (pluggable, SKILL.md-driven)

A skill is a folder under `{dataRoot}/skills/<name>/` with at least a `SKILL.md` (YAML frontmatter: `name`, `description`) plus optional `scripts/`, templates and assets. On task start the app scans all installed skills and injects a compact catalog into Step 0. The agent is required to `read_file` the relevant `SKILL.md` before calling its scripts.

Built-in skills shipped with the app include: `docx-editor`, `xlsx-editor`, `pptx-editor`, `pdf`, `stockquant`, `slack-gif-creator`, `ths-trade`, and a meta-skill **`skill-install`** that teaches the agent itself how to download + validate + unpack a new skill zip into the skills directory.

To install a new skill, put the `.zip` on the device (or give the agent a URL) and ask `Claw Agent` to install it — the `skill-install` skill handles the rest. Manual install is equally simple: unzip into `{dataRoot}/skills/<skill-name>/`, making sure `SKILL.md` sits at the top.

### 3.7 Scheduled Tasks (4 slots + master switch)

`UnifiedForegroundService` runs an `AlarmManager`-driven 1-minute heartbeat. There are 4 independent task slots; each slot has its own enabled flag, weekdays (1..7), time window (`HH:MM`–`HH:MM`, cross-midnight supported), interval, and an `agent_user` preset (or explicit prompt). A single **master switch** on the Settings page gates the whole system; the agent itself can only touch per-slot config via `schedule_set`, never the master switch.

When a slot fires with the screen ON, the agent is launched directly into the floating window; with the screen OFF, a high-priority reminder notification is posted so the user can unlock and continue. After a scheduled task completes, the floating window auto-hides.

---

## 4. System Architecture (High Level)

At a high level, OfflineAI consists of:

- **Android app (UI & orchestration)**  
  - Chat interface, knowledge base builder, knowledge graph viewer, notes, and settings.  
  - Orchestrates RAG workflows, local and optional remote models, and conversation history.

- **On-device inference engines**  
  - LLM / VLM / TTS / ASR / Diffusion powered by **MNN** and dedicated JNI bridges.  
  - Streaming text generation with KV cache and low-memory optimization.

- **Knowledge & retrieval layer**  
  - Document chunking, embeddings, vector store.  
  - Entity extraction, graph construction, and hub analysis.  
  - Hybrid vector + graph RAG query pipeline.

- **Configuration & storage**  
  - Local configuration files for API endpoints, model paths, and runtime parameters.  
  - Local storage for conversations, RAG indices, graphs, and notes.

---

## 5. Repository Layout

The repository is organized roughly as follows:

- `app/` – Main Android application module
  - UI screens: chat, knowledge base builder, graph viewer, model/parameter settings.
  - RAG orchestration, history management, and integration with local/remote models.

- `libs/mnn/` – Upstream MNN source tree (submodule)

- `libs/mnn-lib/` – MNN core build
  - Builds shared `libMNN.so` with LLM, vision, TTS, and audio backends enabled.

- `libs/mnn-jni/` – LLM / multimodal JNI bindings
  - Java API for on-device LLM/VLM inference (see its own `README.md` for details).

- `libs/mnn-tts-jni/` – Text-to-speech JNI bindings

- `offline-ai-apk/` – Packaged APKs and release metadata

- `SPEC.md` – Project design and implementation notes

Other Gradle, wrapper, and configuration files support building the Android project.

---

## 6. On-Device Models & Modalities

OfflineAI is designed to work with a set of local models, typically stored under a user-configurable data root (for example, `/sdcard/Download/OfflineAIData` on Android).  
Typical model categories include:

- **LLM** – Local language models for chat and RAG answering.
- **VLM** – Vision-language models for image understanding.
- **Embedding models** – For vector indexing and retrieval.
- **Rerankers** – For refining candidate documents.
- **ASR models** – Speech-to-text for local voice input.
- **TTS models** – Text-to-speech for local voice output.
- **Diffusion models** – Text-to-image generation.

The app provides a **model download & configuration** experience so that default models can be fetched and wired up more easily.

---

## 7. RAG Workflow (Vector + Graph)

The RAG pipeline in OfflineAI combines vector retrieval with graph-based expansion to improve recall and interpretability:

1. **Vector RAG construction**  
   User documents are chunked, embedded, and stored in a local vector store.

2. **Vector RAG query**  
   A user question is embedded and used to retrieve top-K candidate chunks.

3. **Graph enhancement**  
   - Extract seed entities from the question and top chunks.  
   - Apply hub filtering to control noisy high-degree entities.  
   - Expand the graph 1–2 hops around relevant entities.  
   - Combine signals from vector similarity, graph co-occurrence, and seed overlap.

4. **Context building**  
   The final selected context is assembled and sent to the local (or optional remote) LLM along with the user question.

5. **Result inspection**  
   A knowledge graph viewer shows entities, relations, and hubs so users can understand why certain documents were retrieved.

---

## 8. Privacy & Data Handling

OfflineAI is designed with a **local-only** mindset:

- All inference for LLM/VLM/RAG/ASR/TTS/diffusion can be run entirely on device.
- Documents, embeddings, graphs, and notes are stored locally.
- Custom dictionaries and entity normalization tables can be configured to adapt to specific domains without sending data to external services.

Remote models may optionally be configured via API keys and endpoints, but the core workflows do **not** require a network connection.

---

## 9. Getting Started (Build & Run)

### 9.1 Prerequisites

- Android development environment (Android Studio / Gradle).
- A device or emulator with sufficient memory and GPU/NNAPI support for your chosen models.

### 9.2 Build the App

From the project root:

```bash
# Build release APK (example, using signing password key)
./gradlew :app:assembleRelease -PKEYPSWD=abc-1234
```

The resulting APK can be found under `app/build/outputs/apk/` and installed on a device.

### 9.3 Prepare Data Root & Models

1. Choose a data root directory on the device, for example:

   ```
   /sdcard/Download/OfflineAIData
   ```

2. Use the app's model download and configuration UI to fetch and register:
   - LLM / VLM models.
   - Embedding and rerank models.
   - ASR / TTS models.
   - Diffusion models.

3. Create one or more knowledge bases and add documents via the UI.

### 9.4 Use the App

- Start the app and open the **RAG QA** screen to ask questions against a chosen knowledge base.
- Use the **Knowledge Base Builder** to ingest more documents.
- Use the **Graph Viewer** to inspect entities, relations, and hubs.
- Use the **Notes** feature to capture quick knowledge snippets that immediately join the searchable corpus.
- Explore **multimodal** features (image understanding, local image generation) as configured.

---

## 10. Local Inference Engine

The on-device LLM/VLM engine is implemented via the MNN runtime and exposed through the `libs/mnn-jni` module.

For details on configuration options, supported backends, and performance tuning, see:

- `libs/mnn-jni/README.md`

---

## 11. Status & Roadmap

OfflineAI is an evolving project. Planned directions include (non-exhaustive):

- Further optimization of model loading, KV cache, and mixed-precision execution on edge devices.
- Additional monitoring and debug tooling for RAG pipelines (retrieval traces, graph statistics).
- More flexible model/plugin configuration for different hardware tiers.

---

## 12. License & Acknowledgements

This project uses and builds upon several open-source components, including but not limited to:

- [MNN](https://github.com/alibaba/MNN)
- Models and datasets from the open-source community

Please refer to individual components for their respective licenses.
