# 发布流程

发版按这个顺序走，不要跳步。版本号以 `v2.2.1` 为例。

配套脚本：`scripts/publish_release.py`（创建/更新 Release、写正文、传附件）。
唯一的交付构建类型是 `vienna`，其它变体不要发布。

---

## 1. 升版本

`app/build.gradle.kts`：

- `versionCode` +1（200 → 201）
- `versionName` 改为目标版本（`2.2.0` → `2.2.1`）

两者必须同时改。`versionCode` 决定覆盖升级顺序，`versionName` 决定 APK 文件名和 Release 标题。

## 2. 写文案

- `CHANGELOG.md` 顶部新增 `## [vX.Y.Z] - YYYY-MM-DD`，按 Keep a Changelog 格式分 `Added` / `Improved` / `Fixed` / `Other`，底部保留 `## [Unreleased]` 链接区。
- `docs/release-post-vX.Y.Z.md` 写发布正文，沿用上一版排版（标题 + 引用 + 分节 + 下载表格）。一级标题脚本会自动剥掉，因为 Release 页面自带版本号。
- 文案必须来自实际改动，不要照抄会话记录。写完检查 `zh-rCN` 和 `zh-rTW` 的 `strings.xml` 是否同步了新增文案，否则用户会看到未翻译的英文。

## 3. 构建与校验

```powershell
.\gradlew :app:assembleVienna
```

产物在 `app/build/outputs/apk/vienna/`。发之前核对：

- `app/build/outputs/apk/vienna/output-metadata.json` 里每个元素的 `versionCode` / `versionName` 是不是目标版本
- 各 APK 的修改时间是不是本次构建

`vienna` 目录会累积历次产物，不核对容易把旧包传上去。不要为了省事删 build 目录。

ABI 选择：`arm64-v8a`（手机/平板）、`x86_64`（模拟器）、`armeabi-v7a`（老旧 32 位）、`universal`（全架构，体积最大，前三个都不合适时才用）。

## 4. 提交与推送

暂存后先确认清单里没有 `secrets.properties`、构建日志、截图等不该入库的文件。

```powershell
git add CHANGELOG.md docs/release-post-vX.Y.Z.md app/build.gradle.kts app/src i18n source-local
```

中文提交信息要用文件传，**不要用命令行传中文**——PowerShell 对中文参数会编码损坏：

```powershell
git commit -F <UTF-8 编码的提交信息文件>
git tag -a vX.Y.Z -m "vX.Y.Z"
```

打完 tag 校验它指向的提交：

```powershell
git rev-parse vX.Y.Z^{commit}
```

注意 `git ls-remote --tags` 和 `git rev-parse vX.Y.Z` 返回的是 **tag object**，不是 commit。判断是否一致必须用 `^{commit}` 或 `git log -1 <sha>`，否则会误判成远端与本地不一致。

```powershell
git push origin main
git push origin vX.Y.Z
```

## 5. 发布

```powershell
python scripts/publish_release.py vX.Y.Z arm64-v8a armeabi-v7a x86_64 universal
```

脚本行为：

- Token 从 `secrets.properties` 读，不写死在脚本里，也不入库
- Release 不存在时自动创建（非 draft、非 prerelease，`target_commitish` 为 `main`），已存在则只更新正文
- 正文取 `docs/release-post-vX.Y.Z.md`
- APK 按**语义版本号**排序取最新版。字符串排序会把 `2.10.0` 排在 `2.2.1` 前面，到双位数小版本时会误传旧包
- 同名旧附件先删再传，整条命令幂等，可重复运行

发布前确认 Release 上没有上一次遗留的旧命名附件；发布后回读 API 核对附件清单（名称、大小、`state` 应为 `uploaded`）。

本机没有 GitHub CLI（`gh` 命令不存在），Release 只能走脚本或网页端。

---

## 补充说明

- **密钥绝不入库**：`secrets.properties` 已在 `.gitignore`。一旦误提交，立刻在 GitHub Settings 吊销并重新签发，不要只删文件。
- `git push` 时 GitHub 提示仓库已迁移到 `https://github.com/Kurashift/mihon-vienna.git`（旧地址会自动重定向）。
- 发布是公开行为，正式交付绝不能保留 `isDebuggable = true`；临时诊断插桩用完后必须删除。
