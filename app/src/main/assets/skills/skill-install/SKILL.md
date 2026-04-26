---
name: skill-install
description: "Download, validate and install a new skill package from URL or local zip into the agent skills directory"
---

# Skill Install

Install a new skill from a URL, web page, or local `.zip` file.

## Workflow

### Step 1: Obtain the package

User input may be a **direct zip URL**, a **web page about the skill**, or a **local file path**. Handle each:

**(A) Local zip path**: Use directly, go to Step 2.

**(B) Direct zip URL** (ends with `.zip` or known download link):
```python
import requests, os
url = "<direct_zip_url>"
save_path = os.path.join("<chat_history_folder>", "skill_package.zip")
r = requests.get(url, timeout=120, allow_redirects=True)
with open(save_path, "wb") as f:
    f.write(r.content)
print(f"Downloaded {len(r.content)} bytes to {save_path}")
```

**(C) Web page URL** (skill registry page, GitHub repo, etc.):
The goal is to find and download the `.zip` package. Follow this escalation chain — **do NOT loop; move to the next approach if the current one fails**:

**Approach 1 — Scrape the page (1-2 steps max)**:
1. `web_open` the URL, then `web_get_content`.
2. The result is JSON with `links[]` and `buttons[]`. Check:
   - Any `href` containing `.zip` or `download` or `archive`
   - Any link/button text matching "Download zip", "下载ZIP", "下载", "Code" etc.
3. If a direct `.zip` URL is found → download with `python` + `requests` as in (B). **Done.**

**Approach 2 — Navigate to the files/download tab (1-2 steps max)**:
Many registries (ModelScope, GitHub) show skill intro on the landing page but put the download link on a separate "Files" or "Code" tab.
1. Look at `web_get_content` text for tab labels like "Skill 文件", "Files", "Code".
2. Use `web_execute_js` to click that tab, e.g.: `document.querySelector('a[href*="file"]').click()` or similar.
3. Then `web_get_content` again to find the zip link.
4. If found → download with `python` + `requests`. **Done.**

**Approach 3 — GUI operations with screenshot (2-3 steps max)**:
If DOM scripting fails (SPA pages may render download buttons outside normal DOM), use **GUI mode**:
1. The page should already be open. Use `take_screenshot` to see the actual rendered page.
2. Look for a visible "Download zip" / "下载" button in the screenshot.
3. If found, use `click` at the button coordinates to trigger the browser download.
4. `wait` 5 seconds, then use `python` to check `/sdcard/Download/` for newly downloaded `.zip` files:
   ```python
   import os, glob
   zips = sorted(glob.glob('/sdcard/Download/*.zip'), key=os.path.getmtime, reverse=True)
   print(zips[:3] if zips else "No zip files found")
   ```
5. If a zip is found → proceed to Step 2. **Done.**

**Approach 4 — Ask user**:
If all above approaches fail → `ask_user`: "I couldn't find a downloadable skill package on this page. Please download the .zip file manually and tell me its local path."

**IMPORTANT**: Do NOT repeat the same approach. Move forward through 1→2→3→4. Total attempts for Step 1(C) should not exceed ~8 agent steps.

### Step 2: Inspect zip, check conflict, then extract

**CRITICAL — Target directory**: The skills directory is `{dataRoot}/skills/` where `dataRoot` is typically `/storage/emulated/0/Download/OfflineAIData`.
You MUST derive it from the **skill-install SKILL.md path** shown in Step 0's skill catalog (e.g. `/storage/emulated/0/Download/OfflineAIData/skills/skill-install/SKILL.md` → go up 2 levels → `/storage/emulated/0/Download/OfflineAIData/skills/`).
**Do NOT use `/sdcard/skills/` — that is the wrong location.** New skills must be siblings of `skill-install/` in the same directory.

**Phase 1 — Inspect zip (do NOT extract yet)**:
```python
import zipfile, os
zip_path = "<save_path_or_local_path>"
# MUST use the skill-install SKILL.md path from Step 0 skill catalog to derive skills_dir
this_skill_md = "<path_to_skill-install_SKILL.md>"  # e.g. /storage/emulated/0/Download/OfflineAIData/skills/skill-install/SKILL.md
skills_dir = os.path.dirname(os.path.dirname(this_skill_md))  # -> {dataRoot}/skills/

with zipfile.ZipFile(zip_path, "r") as z:
    names = z.namelist()
    skill_md_entries = [n for n in names if n.endswith("SKILL.md")]
    if not skill_md_entries:
        print("ERROR: No SKILL.md found in zip")
    else:
        entry = skill_md_entries[0]
        parts = entry.split("/")
        if len(parts) == 2:
            structure = "single-level"   # "skill-name/SKILL.md"
            skill_name = parts[0]
        elif len(parts) == 1:
            structure = "flat"           # "SKILL.md" at root
            skill_name = os.path.splitext(os.path.basename(zip_path))[0]
        else:
            structure = "deep"           # "a/b/.../SKILL.md"
            skill_name = parts[0]

        installed_dir = os.path.join(skills_dir, skill_name)
        conflict = os.path.exists(installed_dir)
        print(f"STRUCTURE: {structure}")
        print(f"SKILL_NAME: {skill_name}")
        print(f"INSTALLED_DIR: {installed_dir}")
        print(f"CONFLICT: {conflict}")
```

**Phase 2 — If conflict detected** (`CONFLICT: True`): use `ask_user` to confirm overwrite. If user declines, delete the downloaded zip (if any) and `terminate success`.

**Phase 3 — Extract** (only after conflict is resolved or no conflict):
```python
import zipfile, os
zip_path = "<zip_path>"
skills_dir = "<skills_dir>"
skill_name = "<skill_name>"
structure = "<structure>"

with zipfile.ZipFile(zip_path, "r") as z:
    if structure == "flat":
        target = os.path.join(skills_dir, skill_name)
        os.makedirs(target, exist_ok=True)
        z.extractall(target)
    else:
        z.extractall(skills_dir)

installed_dir = os.path.join(skills_dir, skill_name)
# Verify SKILL.md exists at expected location
for root, dirs, files in os.walk(installed_dir):
    if "SKILL.md" in files:
        print(f"SKILL_MD: {os.path.join(root, 'SKILL.md')}")
        break
else:
    print("ERROR: SKILL.md not found after extraction")
```

Record `skill_name`, `installed_dir`, and SKILL.md path in context.fact.

### Step 3: Read and validate SKILL.md

Use `read_file` on the installed SKILL.md. Check:

1. **Has valid frontmatter** (`name` and `description` between `---` markers).
   - If missing: auto-generate frontmatter using the folder name and first heading as description.
2. **Runtime compatibility**: This agent only supports **Python 3.10** via Chaquopy.

#### Supported pip packages (pre-installed, no network install possible):

| Category | Packages |
|----------|----------|
| Office | `python-docx`, `python-pptx`, `openpyxl` |
| PDF | `pypdf`, `pdfplumber`, `fpdf2` |
| Network | `requests`, `beautifulsoup4` |
| Image | `pillow` |
| Data | `pandas`, `numpy` |
| Compression | `zipfile36` |
| Utilities | `python-dotenv`, `chardet` |

**NOT supported**: Node.js, .NET, Ruby, Java, Go, Rust, shell scripts, or any pip package not listed above.

### Step 4: Decide compatibility

Evaluate every feature/dependency in the skill:

- **(a) Fully compatible**: All features use Python + supported packages above.
  → Proceed to Step 5 directly.

- **(b) Partially compatible**: Some features work, others require unsupported runtime/packages.
  → Use `ask_user` to explain what is supported vs not, ask whether to install the supported subset.
  → If user agrees: proceed to Step 5 to optimize SKILL.md in place.
  → If user declines: delete and clean up (Step 5 abort path).

- **(c) Not compatible**: Core functionality requires unsupported runtime or packages.
  → Delete and clean up (Step 5 abort path).

### Step 5: Finalize or abort

**Install path** (cases a, b-agree):
1. Use `edit_lines` / `write_file` to optimize the installed SKILL.md:
   - Ensure frontmatter has `name` and `description`
   - Simplify verbose descriptions to be concise and action-oriented
   - Remove references to unsupported runtimes/packages (for partial installs)
   - Keep code examples that use supported packages
2. Delete the downloaded zip if it was downloaded in Step 1:
   ```json
   {"action": "delete_file", "path": "<chat_history_folder>/skill_package.zip"}
   ```
3. `terminate success` with summary: skill name, what it does, any removed features.

**Abort path** (cases b-decline, c):
1. Delete the installed skill folder:
   ```json
   {"action": "delete_file", "path": "<installed_dir>", "recursive": true}
   ```
2. Delete the downloaded zip if it was downloaded in Step 1:
   ```json
   {"action": "delete_file", "path": "<chat_history_folder>/skill_package.zip"}
   ```
3. `terminate success` explaining why the skill was not installed.

**Note**: The newly installed skill takes effect on the **next agent task** (skills are scanned at task start).

## Example Prompts

- "Install this skill: https://example.com/my-skill.zip"
- "Import skill from /sdcard/Download/chart-skill.zip"
- "Install the skill from this page: https://github.com/user/repo"
- "Install the skill package I downloaded"
