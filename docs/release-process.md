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
- 文案必须来自实际改动，不要照抄会话记录。中文文风按 `docs/writing-style.md`：写具体数字与前后对照，不要替读者下判断、不要空引导句、不要拔高。要卸载重装、会动用户数据、修数据损坏这三类改动写在最前面。
- 发布正文固定带两节：**「本版未验证」**（哪些机型 / ROM / 文件格式 / 库规模没实测）和**「升级与降级」**。后者至少写清：本版第一次启动会在 `files/backup/` 留一份迁移前的数据库快照（保留最近 3 份），以及**降级不保证**——旧版本打不开新 schema，要退回上一版只能卸载重装，本地库与进度会丢，先用备份导出。
- 写完检查 `zh-rCN` 和 `zh-rTW` 的 `strings.xml` 是否同步了新增文案，否则用户会看到未翻译的英文。

## 3. 构建与校验

发布包由 CI 打：push tag 之后 `.github/workflows/release.yml` 会 `assembleVienna` 并验签。本地只在想先自己装一版看效果时才构建：

```powershell
.\gradlew :app:assembleVienna
```

产物在 `app/build/outputs/apk/vienna/`。发之前核对：

- `app/build/outputs/apk/vienna/output-metadata.json` 里每个元素的 `versionCode` / `versionName` 是不是目标版本
- 各 APK 的修改时间是不是本次构建

`vienna` 目录会累积历次产物，不核对容易把旧包传上去。不要为了省事删 build 目录。

这两项发布脚本也会再查一遍，但它是发版那一刻才查的；构建完先看一眼，比等到打 tag 之后才发现对不上省事。

ABI 选择：`arm64-v8a`（手机/平板）、`x86_64`（模拟器）、`armeabi-v7a`（老旧 32 位）、`universal`（全架构，体积最大，前三个都不合适时才用）。

### 签名身份

发之前对一次指纹，确认这四个包是这把钥匙签的：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs `
  app\build\outputs\apk\vienna\MihonVienna-<versionName>-arm64-v8a.apk
```

对上的两行：

- `Signer #1 certificate DN: CN=Mihon Personal Build, OU=Personal, O=Personal, L=Local, ST=Local, C=CN`
- `Signer #1 certificate SHA-256 digest: fe8823116beff06892c1d877c0b331d03f6df0aaf8227df0ae7d383cda2b96a0`

指纹对不上就不要传。装出去之后换不回来：新密钥签的包无法覆盖安装，老用户只能卸载重装，本地库和进度一起丢。

密钥本身在仓库外：`D:\DATA\keys\mihon-release.keystore`（别名 `mihon`，密码在仓库根的 `keystore.properties`）。文件丢失无法从 APK 反推，只能换新密钥；`D:\DATA\keys\backup\` 有一份校验过哈希的副本，建议另外放一份到云盘或移动硬盘，与密码分开存放。

## 4. 提交、PR 与推送

`main` 开了分支保护：改动只能经 PR 合并，且 `Build & Test App` 检查通过；直接 `git push origin main` 会被 GitHub 拒绝。tag 不受分支保护限制，照常推送。

先建分支，不要在 `main` 上直接提交：

```powershell
git switch -c release-vX.Y.Z
```

暂存后先确认清单里没有 `secrets.properties`、构建日志、截图等不该入库的文件。

```powershell
git add CHANGELOG.md docs/release-post-vX.Y.Z.md app/build.gradle.kts app/src i18n source-local
```

中文提交信息要用文件传，**不要用命令行传中文**——PowerShell 对中文参数会编码损坏：

```powershell
git commit -F <UTF-8 编码的提交信息文件>
git push origin release-vX.Y.Z
```

在 GitHub 上开 PR（base 选 `main`），等 `Build & Test App` 绿了再合并，合并后删掉该分支，本地同步：

```powershell
git switch main
git pull --ff-only
```

tag 打在合并后的 `main` 上，再校验它指向的提交：

```powershell
git tag -a vX.Y.Z -m "vX.Y.Z"
git rev-parse vX.Y.Z^{commit}
```

注意 `git ls-remote --tags` 和 `git rev-parse vX.Y.Z` 返回的是 **tag object**，不是 commit。判断是否一致必须用 `^{commit}` 或 `git log -1 <sha>`，否则会误判成远端与本地不一致。

```powershell
git push origin vX.Y.Z
```

## 5. 打 tag，CI 构建并发布

合并后切回 main、拉最新、打 tag、推送：

```powershell
git switch main
git pull --ff-only
git tag -a vX.Y.Z -m "vX.Y.Z"
git push origin vX.Y.Z
```

push tag 触发 `.github/workflows/release.yml`，它按顺序做四件事：

1. **构建前校验**：tag 与 `versionName` 一致、`docs/release-post-<tag>.md` 存在、tag 指向的提交在 `main` 上。任何一项不符直接失败，不构建
2. `./gradlew :app:assembleVienna`，出四个 ABI 加 `universal`，签名用仓库 Secrets 里的密钥（`STORE_FILE_BASE64` / `STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`）
3. **验签**：`apksigner verify --print-certs` 比对第 3 节记的指纹，对不上就不发布
4. 生成 `SHA256SUMS`，`gh release create` 建 Release，正文取 `docs/release-post-<tag>.md`（剥掉一级标题）

最后一步会把附件清单打印出来（`gh release view --json assets`），回读核对名称与大小。

Release 已存在时 workflow 失败而不是覆盖——要重发先在网页上删掉那个 Release。

### 手动补救

`scripts/publish_release.py` 留着，用于 CI 失败后手动补传，或者只想改正文的时候：

```powershell
python scripts/publish_release.py --check vX.Y.Z arm64-v8a armeabi-v7a x86_64 universal
python scripts/publish_release.py vX.Y.Z arm64-v8a armeabi-v7a x86_64 universal
```

它要求本机已经有对应版本的 APK（先 `assembleVienna`），校验项与 workflow 一致。日常发版不要用它——产物要来自 CI，本机打的那份没有构建记录可查。

---

## 补充说明

- **密钥绝不入库**：`secrets.properties` 已在 `.gitignore`。一旦误提交，立刻在 GitHub Settings 吊销并重新签发，不要只删文件。
- `git push` 时 GitHub 提示仓库已迁移到 `https://github.com/Kurashift/mihon-vienna.git`（旧地址会自动重定向）。
- 发布是公开行为，正式交付绝不能保留 `isDebuggable = true`；临时诊断插桩用完后必须删除。
