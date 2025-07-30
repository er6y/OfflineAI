# Git LFS 使用指南 | Git LFS Usage Guide

## 中文

### 概述

本项目已配置 Git LFS (Large File Storage) 来处理大文件，特别是：
- APK 文件（如 StarLocalRAG_release20250709230825.apk，170.92 MB）
- JNI 库文件（app/src/main/jniLibs/ 目录下的 .so 文件，50-55MB）

### 已配置的文件类型

在 `.gitattributes` 文件中，以下文件类型已配置为使用 LFS：

```
*.apk     # APK 安装包
*.so      # JNI 共享库
*.aar     # Android 库文件
*.jar     # Java 归档文件
*.gguf    # GGUF 模型文件
*.onnx    # ONNX 模型文件
*.bin     # 二进制模型文件
```

### 如何添加大文件

#### 1. 确保 LFS 已安装和初始化
```bash
# 安装 Git LFS（如果尚未安装）
git lfs install
```

#### 2. 添加大文件
```bash
# 正常添加文件，LFS 会自动处理
git add your-large-file.apk
git add app/src/main/jniLibs/arm64-v8a/libllamacpp_jni.so

# 提交更改
git commit -m "Add large files via LFS"

# 推送到远程仓库
git push origin your-branch
```

#### 3. 验证文件是否使用 LFS
```bash
# 查看 LFS 跟踪的文件
git lfs ls-files

# 查看 LFS 状态
git lfs status
```

### 克隆包含 LFS 文件的仓库

```bash
# 克隆仓库并下载 LFS 文件
git clone https://github.com/er6y/StarLocalRAG.git
cd StarLocalRAG
git lfs pull
```

### 注意事项

1. **存储配额**：GitHub LFS 有存储和带宽限制
   - 免费账户：1GB 存储，1GB/月 带宽
   - 付费账户：可购买额外配额

2. **下载性能**：LFS 文件在首次克隆时不会自动下载，需要运行 `git lfs pull`

3. **协作开发**：团队成员需要安装 Git LFS 才能正常处理大文件

---

## English

### Overview

This project has been configured with Git LFS (Large File Storage) to handle large files, specifically:
- APK files (e.g., StarLocalRAG_release20250709230825.apk, 170.92 MB)
- JNI library files (.so files in app/src/main/jniLibs/ directory, 50-55MB)

### Configured File Types

In the `.gitattributes` file, the following file types are configured to use LFS:

```
*.apk     # APK installation packages
*.so      # JNI shared libraries
*.aar     # Android library files
*.jar     # Java archive files
*.gguf    # GGUF model files
*.onnx    # ONNX model files
*.bin     # Binary model files
```

### How to Add Large Files

#### 1. Ensure LFS is Installed and Initialized
```bash
# Install Git LFS (if not already installed)
git lfs install
```

#### 2. Add Large Files
```bash
# Add files normally, LFS will handle them automatically
git add your-large-file.apk
git add app/src/main/jniLibs/arm64-v8a/libllamacpp_jni.so

# Commit changes
git commit -m "Add large files via LFS"

# Push to remote repository
git push origin your-branch
```

#### 3. Verify Files are Using LFS
```bash
# View LFS tracked files
git lfs ls-files

# Check LFS status
git lfs status
```

### Cloning Repository with LFS Files

```bash
# Clone repository and download LFS files
git clone https://github.com/er6y/StarLocalRAG.git
cd StarLocalRAG
git lfs pull
```

### Important Notes

1. **Storage Quota**: GitHub LFS has storage and bandwidth limits
   - Free accounts: 1GB storage, 1GB/month bandwidth
   - Paid accounts: Can purchase additional quota

2. **Download Performance**: LFS files are not automatically downloaded on first clone, need to run `git lfs pull`

3. **Team Collaboration**: Team members need to install Git LFS to properly handle large files

### Troubleshooting

If you encounter issues with large files:

```bash
# Check LFS configuration
git lfs env

# Manually track additional file patterns
git lfs track "*.your-extension"

# Migrate existing files to LFS
git lfs migrate import --include="*.apk,*.so"
```