# nx2androidX — Agent 指南

> 本文件是本仓库对 AI agent 的**权威上下文**。`CLAUDE.md` 只指向这里，不重复维护内容。
>
> **一句话：本 fork 只做 Android 端，产品是手机 app（APK）。** 看到仓库里的 PC / iOS 相关目录，
> 不要因此以为项目要往那个方向做。

## 1. 项目身份

**nx2androidX 的产品只有一个：Android 手机 app（APK）。**

- 血统：`shirai91/nx2android`（桌面工具，在 PC 上移植游戏、产出数据包）→ 本 fork 把移植搬进手机 app，
  移植、游戏运行、数据包管理都在手机上完成，没有电脑在中间；引擎来自 `seedds/nxnative`（Apple/iOS 路线），
  fork 在 `nxnative/`。
- 不存在"PC 版 nx2androidX"这个产品，也没有 PC 版路线图。

仓库里出现 PC / iOS 相关目录，**不等于**项目要往那里做：

| 位置 | 是什么 | 本 fork 的地位 |
|---|---|---|
| `android/` | Gradle + Kotlin 的手机 app | **产品本体** |
| `nxnative/dm/android/` | Android 运行时（Vulkan 后端、Horizon 宿主、输入、覆盖层、暂停菜单） | **产品本体** |
| `nxnative/dm/games/<title ID>/` | 游戏档案；Android 侧的关键是 `android.tsv` | **产品本体** |
| `porter/` | 移植流水线本体（Python） | 活跃代码：玩家桌面工具与手机 app 共用同一套 pipeline |
| `gui/` | 桌面壳（PySide/Tk） | 玩家向工具，不是开发重心 |
| `packaging/` | 玩家桌面 zip + `android-apk/` 出 APK | 桌面 zip 是玩家分发物；**APK 才是本 fork 的产物** |
| `nxnative/`（其余） | 上游引擎 fork | 运行时在 `dm/android/`；`nxnative/AGENTS.md` 与 `nxnative/docs/` 讲的是上游的 Apple/iOS 路线，不是本 fork 的方向 |
| `nxnative/dm/ios/` | 上游 iOS 侧代码 | 上游遗产，本 fork 不动 |

## 2. 问"怎么运行 / 打包 / 发布 / 安装"时，一律指 Android

玩家手机里装的是 **APK**；移植、数据包、游戏运行全在手机上完成。

- ✅ 构建 APK：`bash packaging/android-apk/build_apk.sh [debug|release]`
  （需要 gh CLI 对私有 release `NixPort/nixport-rootfs` 的读权限；下载缓存在 `packaging/android-apk/.cache`，不入库）
- ✅ CI：`.github/workflows/android-apk.yml` —— **只有打 tag 才构建**（`workflow_dispatch` 手动出构建产物；推分支不构建）。tag 一推，构建就开始，等 release 建好后自动把 APK 附上去
- ✅ 装机与真机调试：adb，设备信息在 `devtools/device.json`；方法见 `.claude/skills/switch-android-port`
- ✅ 宿主/着色器改动后的重编译：`nxnative/dm/android/build.py`
- ❌ 不要用"下载 `nx2android-<version>-windows-x64.zip`、运行桌面版"来回答 Android 上的问题
- ❌ 不要把 `python packaging/build_bundle.py`（玩家桌面 zip）当作本 fork 的"打包/发布"
- ❌ 不要引用上游的 iOS / IPA / Metal 流程（`nxnative/AGENTS.md`、`nxnative/docs/agents/`）当作本 fork 的做法

README 的 "For players" 一节讲桌面工具，那仍是真实存在的玩家分发物 —— 它只是不是本 fork 的开发对象。

## 3. 发布（唯一真相）

本线的版本号是它承载的**上游基版本**：`porter/__init__.py` 持有基版本（`0.MINOR.PATCH`，当前 `0.3.0`）；
发布 tag 是基版本加上本线的预发布阶段：`v<base>-alphaNN`（如 `v0.3.0-alpha02`），或正式版 `v<base>`。

- 逐步操作见 [nx2android-release skill](.claude/skills/nx2android-release/SKILL.md)：发版的命令顺序、构建只看 tag、
  产物怎么核、alpha 怎么变成当前版本、这些坑

- 预发布用 GitHub **pre-release**（`--prerelease`）；app 的更新检查读 "Latest"，所以正式版玩家不会被推上 alpha。
  **GitHub 不允许预发布成为 "Latest"**（API：drafts and prereleases cannot be set as latest）：要让某个 alpha
  成为当前版本，就去掉它的预发布标记（`gh release edit <tag> --prerelease=false`）——tag 名字里的 `alpha` 不变，
  之后的预发布不会自动抢走这个位置。现状：`v0.3.0-alpha06` 是 Latest
- **构建只由 tag 触发**：推分支什么都不构建。tag 一推，构建就开始（产物从那个 tag 的提交来），
  它会等 release 建好后把 APK 附上去（`gh release create` 紧跟着 tag 就行）。**要重新构建就把 tag 重推一次**
  （`git tag -f` + `git push --force`），构建跟着新 tag 走
- **把一个已发布的 release 退回草稿再发布会抹掉它的预发布标记**：发布那一步
  （`--draft=false`）不带 `--prerelease=true`，release 就变成正式版、成了 "Latest"，正式版玩家会被推上 alpha。
  这条命令要写成 `gh release edit <tag> --draft=true --prerelease=true` 再
  `gh release edit <tag> --draft=false --prerelease=true`；改完核对 `gh release view <tag> --json isPrerelease`
  （守卫也会拦不带标记的 `--draft`）
- 步骤：提交 `chore(release): version <x.y.z>` → 打附注 tag `v<base>-alphaNN` → push 两者 →
  `gh release create v<base>-alphaNN --verify-tag --prerelease --notes-file <notes> --repo zFitness/nx2androidX`，**不带 zip**
- **notes 里要有一段 `Community ports`**，点名本版本涉及的社区贡献（谁移植了哪个游戏、谁修了什么）。
  名单用 `python3 devtools/release_contributors.py` 取（上个 tag 之后合并的 PR 与作者），粘进 notes；
  游戏级别的署名同时落在 `docs/PORT_CHECKLIST.md` 的那一行（`ported by @handle`）
- app 的 `versionName` 在 `android/app/build.gradle.kts`（当前 `0.3.0-alpha05`），与 tag 去掉 `v` 一致
- **一次发布只带手机的 APK，别无其它**。桌面 workflow（`release-windows.yml` / `release-macos.yml`）不发版，
  只在需要时手动触发（Actions → 该 workflow → Run workflow），产物留在该次运行的 artifact 里
- 每个 `gh` 命令都要带 `--repo zFitness/nx2androidX`（本机 gh 默认指向另一个仓库）
- hook `.claude/hooks/release_guard.py`（`.claude/settings.json`）会拦截不合规的 `gh release create|upload|edit`
- **发布不重测旧游戏**：只检查本次新增或修复的游戏；旧游戏后来暴露的问题留给后续补丁版本
- 升级上游基版本时版本号跟着走（`0.4.0` → `v0.4.0-alpha01`）

## 4. 硬约束

- **游戏文件只放 `.gamedump/<App>/`**（主 checkout 内，git 忽略；`<App>` 是 APK 的名字，如 `HadesII`）：
  下载的 NSP / NSZ / XCI 用**硬链接**放进来，禁止复制，禁止写进下载目录；porter 自己解 NSZ / XCZ。
  调试产物（pass dump、帧截图、录屏、端口输出）同样只放这里。做完（移植已提交、测试 app 已卸载）就删掉。
  删之前先确认每个 dump 文件有 2 个以上硬链接（只有一个链接 = 那是唯一副本，先问用户）。
  **worktree 里的 `.gamedump` 同样处理**，不要在会话的临时目录里做移植。
- **会话结束前**：如果这次建过 `.gamedump` 目录，或在任何地方写了超过 1 GB，说明还剩哪些目录、为什么留着。
- **`prod.keys` 与游戏文件不进仓库、不进聊天**；只打印 key 的名字，不打印值。
- **worktree 纪律**：`tools/`（NDK、.NET SDK、platform-tools、prod.keys）只在主 checkout，被 git 忽略，
  porter 的配置按绝对路径指向它。**绝不要在 worktree 里给 `tools/` 建任何链接**（软链也一样）：
  递归删除 worktree 会穿透链接把主 checkout 的真文件删掉 —— 2026-10-31 就这样删光了 NDK、.NET SDK 和 prod.keys。
  删 worktree 前先列出里面的链接，逐个单独删链接本身（**不要带 `-r` / `/s`**），再删 worktree。
- **分支合并后**清理 worktree 并删掉已合并的分支（`git branch -d`）。
- **一个核心改动只影响它真正修的游戏**；通用规则优先于游戏专属规则。
- **移植类改动必须有真机验证**（真机上的实况 / 截图 / 帧率数据），推理和推断不算验证。
- **提交只在被要求时做**，用 checkout 自己的 git 身份；提交信息走 Conventional Commits（英文、小写），
  由 `.githooks/commit-msg` 校验。

## 5. 开发机：macOS

- **硬链接**：`ln <src> <dst>`（必须同一卷；APFS 允许）。查链接数：`ls -l <file>`（第二列）或 `stat -f %l <file>`。
- **没有 junction**；软链是 `ln -s`，危害相同（递归删除会穿透）。查链接：`find <worktree> -maxdepth 2 -type l`；
  删链接本身：`rm <link>`（不带 `-r`；Windows 上是 `cmd /c rmdir <link>`，同样不带 `/s`）。
- **adb**：`tools/platform-tools/adb`（不是 `adb.exe`）；Windows Git Bash 的 `MSYS_NO_PATHCONV` 那条在 macOS 不适用。
- **玩家配置**：`~/Library/Application Support/nx2android/config.json`（`NX2A_HOME` 可覆盖），
  里面是工具链路径、prod.keys、输出目录、设备。
- **Android SDK**（如果有）：`~/Library/Android/sdk`。
- **Python**：`python3`；输出非 ASCII 内容时设 `PYTHONUTF8=1`（porter 内部已设）。
- **zsh 的 heredoc** 会展开变量和反斜杠：需要原样文本时用 `<<'EOF'`（加引号），否则写一个临时 `.py` 文件。
- 工具链缺失时：`python -m porter tools --download`（约 1 GB）。

## 6. 语言

- 对外产物一律英文：README、docs、代码注释、日志与报错、提交信息、脚本输出
- 尚未实现或明显不对的日志行以 `[RED] ` 开头
- 需要匹配游戏自带的非 ASCII 文本时，写转义（`"\xE8\xB5\xB7"`），不写字符本身
- 需求与任务记录用中文

## 7. 深入指南

| 要做的事 | 看这里 |
|---|---|
| 加游戏档案 / 改共享核心（宿主、Vulkan、着色器、输入、覆盖层） | `.claude/skills/nx2android-dev` |
| 真机排障（闪烁、崩溃、掉帧、压测、实况验证） | `.claude/skills/switch-android-port` |
| 推给上游（开 PR、rebase、回 review） | `.claude/skills/nx2android-upstream-pr` |
| 同步上游（三线拓扑的操作步骤、三张清单、合并的坑） | `../openspec-hub/nx2android/my-docs/上游同步.md`、`../openspec-hub/nx2android/my-docs/踩坑/` |
| 分层与契约、目录职责 | `docs/ARCHITECTURE.md` |
| 从仓库运行、构建、测试、提交信息 | `docs/DEVELOPMENT_GUIDE.md` |
| 游戏进度清单 | `docs/PORT_CHECKLIST.md`、`docs/PORT_STATUS.md` |
| 上游引擎（Apple 路线）的档案格式与调查手册 | `nxnative/AGENTS.md`、`nxnative/docs/agents/` |
