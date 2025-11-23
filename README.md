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

---

## 3. System Architecture (High Level)

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

## 4. Repository Layout

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

## 5. On-Device Models & Modalities

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

## 6. RAG Workflow (Vector + Graph)

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

## 7. Privacy & Data Handling

OfflineAI is designed with a **local-only** mindset:

- All inference for LLM/VLM/RAG/ASR/TTS/diffusion can be run entirely on device.
- Documents, embeddings, graphs, and notes are stored locally.
- Custom dictionaries and entity normalization tables can be configured to adapt to specific domains without sending data to external services.

Remote models may optionally be configured via API keys and endpoints, but the core workflows do **not** require a network connection.

---

## 8. Getting Started (Build & Run)

### 8.1 Prerequisites

- Android development environment (Android Studio / Gradle).
- A device or emulator with sufficient memory and GPU/NNAPI support for your chosen models.

### 8.2 Build the App

From the project root:

```bash
# Build release APK (example, using signing password key)
./gradlew :app:assembleRelease -PKEYPSWD=abc-1234
```

The resulting APK can be found under `app/build/outputs/apk/` and installed on a device.

### 8.3 Prepare Data Root & Models

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

### 8.4 Use the App

- Start the app and open the **RAG QA** screen to ask questions against a chosen knowledge base.
- Use the **Knowledge Base Builder** to ingest more documents.
- Use the **Graph Viewer** to inspect entities, relations, and hubs.
- Use the **Notes** feature to capture quick knowledge snippets that immediately join the searchable corpus.
- Explore **multimodal** features (image understanding, local image generation) as configured.

---

## 9. Local Inference Engine

The on-device LLM/VLM engine is implemented via the MNN runtime and exposed through the `libs/mnn-jni` module.

For details on configuration options, supported backends, and performance tuning, see:

- `libs/mnn-jni/README.md`

---

## 10. Status & Roadmap

OfflineAI is an evolving project. Planned directions include (non-exhaustive):

- Further optimization of model loading, KV cache, and mixed-precision execution on edge devices.
- Additional monitoring and debug tooling for RAG pipelines (retrieval traces, graph statistics).
- More flexible model/plugin configuration for different hardware tiers.

---

## 11. License & Acknowledgements

This project uses and builds upon several open-source components, including but not limited to:

- [MNN](https://github.com/alibaba/MNN)
- Models and datasets from the open-source community

Please refer to individual components for their respective licenses.
