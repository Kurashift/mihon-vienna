#!/usr/bin/env python3
"""生成「上游吸收甄别表」：把上游自分叉点之后的每条提交，逐条对照本仓库现状。

判定依据是「上游这条提交改动**之前**的文件版本，与本仓库当前版本是否一致」，
而不是「这条提交有没有碰我改过的文件」——后者只是冲突的弱信号。

用法::

    python scripts/upstream_intake.py              # 重新生成 docs/upstream-intake.md
    python scripts/upstream_intake.py --check      # 只打印统计，不写文件
    python scripts/upstream_intake.py --details    # 逐条提交附上每个文件的状态

需要本地已有 upstream remote::

    git remote set-branches upstream main
    git fetch upstream main
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

# 分叉点：上游 2026-08-08 的 v0.20.4 之后第 4 天。用树差最小化扫出来的，
# 本仓库 main 的初始提交与该提交的差异文件数在此处最小（265）。
FORK_BASE = "2506b049642af2211c1ef81e7369f752363f655d"

UPSTREAM_REF = "refs/remotes/upstream/main"
FORK_REF = "main"

# 已归入路线的提交。未列出的提交保持"未分配"，等待逐条圈定。
BATCHES: list[tuple[str, str, list[str]]] = [
    ("P1", "数据正确性", ["40f552981", "88d4071cc", "38e93086c", "61cd786e7"]),
    ("P2", "ComicInfo 日期（只读）", ["a7179805a", "1e142c425"]),
    ("P3a", "编译提速", ["12fbb13df", "427e10584", "debab6c26"]),
    ("P3b", "编译目标与 R 类", ["c69b14c97", "802958b1e", "2c979d2d3", "2b9cb4ca5", "0de91a9c5"]),
    ("P3c", "R8 放开 shrink", ["06d612811"]),
    ("P4a", "下载文件夹命名", ["a5d584eeb", "6a329386c"]),
    (
        "P4b",
        "下载调度与队列",
        [
            "730e43fd1",
            "6490c41e6",
            "01d302feb",
            "9a6dea804",
            "fb8e640d6",
            "f82a7a68f",
            "611578313",
            "f2464a1ba",
            "f7da5c856",
        ],
    ),
    ("P5a", "阅读器边界", ["b5856135c", "35bd0a4b3", "51b61a36e", "c371d6331", "1f85a5a8d"]),
    ("P5b", "并行页加载", ["3e5d593e2"]),
    ("P6", "网络与 WebView", ["5f63c983d", "7ae858e60", "5a5ff22c3", "ac249e366", "a01867dc3", "649fc83ff"]),
    ("P7", "备份与迁移", ["771ba33a3", "9dbb7148c", "4b48a84ec"]),
    ("P8", "设置与更新", ["970b12f1b", "fc20410ce", "d58130a5f"]),
    (
        "P9",
        "结构性改造",
        [
            "b2015d1ef",
            "b69594011",
            "a80027ff3",
            "5c3ae37d5",
            "ec614bb4c",
            "93cc07511",
            "2d1d2e4ca",
            "44c1e1915",
            "e21414b22",
            "986f46c09",
            "f8fff318b",
            "a4abb6292",
            "306befb21",
            "c67a33f3d",
            "c46317f09",
            "260e31cd2",
            "5fadea61e",
        ],
    ),
]

# 已逐条核实过的判定。没有列出的提交 = 还没甄别。
VERDICTS: dict[str, str] = {
    "be44a81fb": "已有等价实现，跳过",
    "1e142c425": "不适用（你的日期逻辑已重写）",
    "a7179805a": "可移植，只做读侧",
    "40f552981": "可移植（4 行，列名要换）",
    "88d4071cc": "可移植",
    "38e93086c": "可移植（搬进 AppModule）",
    "61cd786e7": "可移植",
    "abb126ad2": "可移植（10 行，可搭车任一批）",
    "d3f10772f": "可移植但不建议（与本地 tab 重复）",
    "f9c5fa901": "需重写（Injekt 版）",
    "093841105": "需重写（ExtensionLoader 已领先 173 行）",
    "06d612811": "需真机验证后再合",
}

BATCH_OF: dict[str, str] = {}
for _bid, _title, _shas in BATCHES:
    for _sha in _shas:
        BATCH_OF[_sha] = _bid

# 已逐条核实过的提交：上游做什么、你现在的状况、结论。结论里的每一条都打开过 diff 与你的文件对照。
VERDICT_NOTES: list[tuple[str, str, str, str]] = [
    (
        "be44a81fb",
        "一个压缩包读不出时，其余章节照常列出",
        "`LocalSource.buildChapterEntry` 已有同样的 try/catch，注释写的理由也一致",
        "已有等价实现，跳过",
    ),
    (
        "1e142c425",
        "ComicInfo 的年份按 2 位补零、月份被钳到 31 导致日期解析失败",
        "你的日期逻辑整体重写过，没有 `padStart` / `coerceIn` 这两个调用",
        "不适用；它暴露的问题由 P2 覆盖",
    ),
    (
        "a7179805a",
        "读写 ComicInfo 的 Year / Month / Day，作为章节日期",
        "`ComicInfo.kt` 与你逐字节一致（无这三个字段）；`setChapterDetailsFromComicInfoFile` 只读 title / number / translator",
        "可移植，只做读侧",
    ),
    (
        "40f552981",
        "空标题不再把所有书架条目匹配成「重复」",
        "`mangas.sq` 的 `getDuplicateLibraryManga` 没有空标题 guard，bug 在",
        "可移植（4 行，列名要换成 `title` / `favorite`）",
    ),
    (
        "88d4071cc",
        "启动 rescan 不被磁盘上的下载索引覆盖",
        "`DownloadCache.kt` 用 `File` 而非 `AtomicFile`，初始化与 renew 之间没有顺序保证",
        "可移植",
    ),
    (
        "38e93086c",
        "SQLite 连接池按 Room 的做法配置：1 写 4 读、WAL、每个连接 3 秒 busy_timeout",
        "只有 `isForeignKeyConstraintsEnabled = true`，单连接、默认 journal、无 busy_timeout",
        "可移植（驱动 API 已具备，位置在 `AppModule.kt`）",
    ),
    (
        "61cd786e7",
        "WAL 在所有设备上开启，低内存机降到 1 个读连接",
        "同上",
        "可移植",
    ),
    (
        "abb126ad2",
        "三星 Android 9–11 上 `reportFullyDrawn()` 抛 SecurityException 导致启动崩溃",
        "`MainActivity` 已分叉，但这 10 行插得进去",
        "可移植，可搭车任一批",
    ),
    (
        "d3f10772f",
        "全局搜索把本地源也算进来",
        "`getEnabledSources` 只按语言过滤，本地源被排除",
        "可移植但不建议：你已有独立的本地 tab，会变成两个入口",
    ),
    (
        "f9c5fa901",
        "app scope 里未捕获的异常只记日志、不崩 App",
        "你没有 `AppBindings.kt` / `core:metro`，这段在 Metro 的绑定容器里",
        "需重写：在 `AppModule.kt` 给 `CoroutineScope` 加 handler",
    ),
    (
        "093841105",
        "只从签名密钥匹配的仓库安装与更新扩展",
        "`ExtensionLoader` 上游已领先 173 行",
        "需重写",
    ),
    (
        "06d612811",
        "放开 R8 shrink，只保 Injekt 的 TypeReference 与 Signature 属性",
        "现在全保 `eu.kanade.**` / `tachiyomi.**` / `mihon.**`",
        "需真机跑完整流程再合；`dev.mihon.injekt.**` 要额外 keep",
    ),
]

# 这些路径每个 fork 都会改，放进冲突热点表只会淹没真正的热点。
HOTSPOT_NOISE = ("CHANGELOG.md", "i18n/", "fastlane/", "docs/", "gradle/libs.versions.toml")


def git(*args: str, check: bool = True) -> str:
    proc = subprocess.run(
        ["git", *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if check and proc.returncode != 0:
        raise SystemExit(f"git {' '.join(args)} 失败：{proc.stderr.strip()}")
    return proc.stdout


@dataclass
class Entry:
    status: str
    path: str
    parent_path: str
    kind: str = ""
    detail: str = ""


@dataclass
class Commit:
    sha: str
    date: str
    subject: str
    entries: list[Entry] = field(default_factory=list)


def read_log(base: str, upstream: str) -> list[Commit]:
    out = git(
        "log",
        "--reverse",
        "--format=@@%H%x09%ad%x09%s",
        "--date=short",
        "--name-status",
        "-M",
        f"{base}..{upstream}",
    )
    commits: list[Commit] = []
    current: Commit | None = None
    for line in out.splitlines():
        if line.startswith("@@"):
            sha, date, subject = line[2:].split("\t", 2)
            current = Commit(sha, date, subject)
            commits.append(current)
        elif line.strip() and current is not None:
            parts = line.split("\t")
            status = parts[0]
            if status.startswith(("R", "C")) and len(parts) >= 3:
                current.entries.append(Entry(status, parts[2], parts[1]))
            elif len(parts) >= 2:
                current.entries.append(Entry(status, parts[1], parts[1]))
    return commits


def tree_blobs(ref: str) -> dict[str, str]:
    blobs: dict[str, str] = {}
    for line in git("ls-tree", "-r", ref).splitlines():
        meta, path = line.split("\t", 1)
        parts = meta.split()
        if len(parts) >= 3 and parts[1] == "blob":
            blobs[path] = parts[2]
    return blobs


def batch_check(specs: list[str]) -> dict[str, str | None]:
    if not specs:
        return {}
    proc = subprocess.run(
        ["git", "cat-file", "--batch-check"],
        input="\n".join(specs) + "\n",
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    result: dict[str, str | None] = {}
    for spec, line in zip(specs, proc.stdout.splitlines()):
        if line.endswith(" missing"):
            result[spec] = None
        else:
            result[spec] = line.split()[0]
    return result


class NumStat:
    def __init__(self) -> None:
        self._cache: dict[tuple[str, str], tuple[int, int]] = {}

    def __call__(self, before: str, after: str) -> tuple[int, int]:
        key = (before, after)
        cached = self._cache.get(key)
        if cached is not None:
            return cached
        added = deleted = 0
        for line in git("diff", "--numstat", before, after, check=False).splitlines():
            parts = line.split("\t")
            if len(parts) >= 3:
                if parts[0].isdigit():
                    added += int(parts[0])
                if parts[1].isdigit():
                    deleted += int(parts[1])
        self._cache[key] = (added, deleted)
        return added, deleted


def classify(subject: str) -> str:
    if subject.startswith("Translations update"):
        return "翻译"
    if subject.startswith("Release "):
        return "发版"
    if subject.startswith("Update dependency") or subject.startswith("Update gradle/actions"):
        return "依赖升级"
    if subject.startswith("Update ") and " to v" in subject:
        return "依赖升级"
    if subject.startswith("Bump "):
        return "依赖升级"
    return "功能/修复"


def shape(counts: dict[str, int]) -> str:
    if counts.get("你没有此文件") or counts.get("两边同名新文件"):
        return "重写"
    if counts.get("分叉"):
        return "对 hunk"
    if counts.get("一致") or counts.get("上游新增"):
        return "直搬"
    return "—"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default=FORK_BASE, help="分叉点提交")
    parser.add_argument("--upstream", default=UPSTREAM_REF, help="上游引用")
    parser.add_argument("--fork", default=FORK_REF, help="本仓库参照引用")
    parser.add_argument("--output", default="docs/upstream-intake.md", help="输出文件")
    parser.add_argument("--check", action="store_true", help="只打印统计，不写文件")
    parser.add_argument("--details", action="store_true", help="额外输出每条提交的逐文件状态")
    args = parser.parse_args()

    root = Path(git("rev-parse", "--show-toplevel").strip())

    base_sha = git("rev-parse", args.base).strip()
    upstream_sha = git("rev-parse", args.upstream).strip()
    fork_sha = git("rev-parse", args.fork).strip()

    commits = read_log(base_sha, upstream_sha)
    fork_blobs = tree_blobs(fork_sha)
    fork_changed = set(git("diff", "--name-only", base_sha, fork_sha).splitlines())

    specs: list[str] = []
    for commit in commits:
        for entry in commit.entries:
            specs.append(f"{commit.sha}^:{entry.parent_path}")
    parents = batch_check(specs)

    numstat = NumStat()
    rows: list[dict[str, object]] = []

    for commit in commits:
        counts: dict[str, int] = {}
        overlap = 0
        for entry in commit.entries:
            parent_blob = parents.get(f"{commit.sha}^:{entry.parent_path}")
            fork_blob = fork_blobs.get(entry.path)
            if entry.path in fork_changed:
                overlap += 1
            if parent_blob is None and fork_blob is None:
                entry.kind = "上游新增"
            elif parent_blob is None:
                entry.kind = "两边同名新文件"
            elif fork_blob is None:
                entry.kind = "你没有此文件"
            elif parent_blob == fork_blob:
                entry.kind = "一致"
            else:
                added, deleted = numstat(fork_blob, parent_blob)
                entry.kind = "分叉"
                entry.detail = f"+{added} −{deleted}"
            counts[entry.kind] = counts.get(entry.kind, 0) + 1

        short = commit.sha[:9]
        rows.append(
            {
                "sha": short,
                "date": commit.date,
                "subject": commit.subject,
                "kind": classify(commit.subject),
                "files": len(commit.entries),
                "overlap": overlap,
                "counts": counts,
                "shape": shape(counts),
                "batch": BATCH_OF.get(short, ""),
                "verdict": VERDICTS.get(short, ""),
                "entries": commit.entries,
            }
        )

    by_kind: dict[str, int] = {}
    for row in rows:
        by_kind[row["kind"]] = by_kind.get(row["kind"], 0) + 1  # type: ignore[index]

    upstream_changed = set(git("diff", "--name-only", base_sha, upstream_sha).splitlines())
    both = upstream_changed & fork_changed

    if args.check:
        print(f"分叉点      {base_sha[:9]}")
        print(f"上游        {upstream_sha[:9]}  共 {len(rows)} 条提交")
        print(f"本仓库      {fork_sha[:9]}")
        print(f"上游改动 {len(upstream_changed)} 个文件，本仓库改动 {len(fork_changed)} 个，双方都改过 {len(both)} 个")
        for kind, count in sorted(by_kind.items(), key=lambda kv: -kv[1]):
            print(f"  {kind:<8} {count}")
        unassigned = [r for r in rows if not r["batch"] and r["kind"] == "功能/修复"]
        print(f"未分配的功能/修复提交：{len(unassigned)}")
        return 0

    out: list[str] = []
    write = out.append

    write("# 上游吸收甄别表")
    write("")
    write("> 由 `scripts/upstream_intake.py` 生成，不要手改。改了判定请改脚本里的 `VERDICTS` / `BATCHES` 再重跑。")
    write("")
    write("## 怎么重跑")
    write("")
    write("```")
    write("git remote set-branches upstream main")
    write("git fetch upstream main")
    write("python scripts/upstream_intake.py")
    write("```")
    write("")
    write("上游前进之后重跑一次，表就刷新到最新；`VERDICTS` 里的判定按提交哈希保存，不会因为新提交而错位。")
    write("")
    write("## 基线")
    write("")
    write("| 项 | 值 |")
    write("| --- | --- |")
    write(f"| 分叉点 | `{base_sha[:9]}` |")
    write(f"| 上游 | `{upstream_sha[:9]}` |")
    write(f"| 本仓库参照 | `{fork_sha[:9]}` |")
    write(f"| 上游自分叉点之后的提交 | {len(rows)} |")
    write(f"| 上游改动文件 | {len(upstream_changed)} |")
    write(f"| 本仓库改动文件 | {len(fork_changed)} |")
    write(f"| 双方都改过 | {len(both)} |")
    write("")
    write("## 分类")
    write("")
    write("| 类型 | 条数 |")
    write("| --- | --- |")
    for kind, count in sorted(by_kind.items(), key=lambda kv: -kv[1]):
        write(f"| {kind} | {count} |")
    write("")
    write("「依赖升级」包含 `Bump …` 开头的提交，其中有几条其实是编译目标（`Bump compileSdk to 37.x`），")
    write("已经按批次归到 P3b，不看这一列的分类。")
    write("")
    write("## 怎么读这张表")
    write("")
    write("- **移植形态**：由「上游这条提交改动之前的文件版本」与本仓库当前版本的比对得出。")
    write("  - `直搬` —— 所有触及文件都一致，或本来就是上游新增文件。")
    write("  - `对 hunk` —— 有文件已经分叉，要逐块对。")
    write("  - `重写` —— 有你这边根本不存在的文件，只能照着实现一遍。")
    write("- **重叠**：这条提交触及的文件里，有多少是你也改过的。它只是冲突的弱信号，")
    write("  真正决定成本的是「移植形态」。两者常常不一致：一条提交可能重叠 0 但形态是「重写」。")
    write("- **批次**：已归入路线图的提交。空白表示还没分配。")
    write("")
    write("`gradle/libs.versions.toml` 和 `CHANGELOG.md` 这两个文件你改过，所以凡触及它们的提交都会显示「对 hunk」，")
    write("这不是真的冲突：依赖升级改的是版本号那一行，CHANGELOG 是你自己的段落。")
    write("")
    write("## 已甄别结论")
    write("")
    write("下面每一条都打开过上游 diff 和本仓库对应文件逐一对照过。没列在这里的提交还没甄别。")
    write("")
    write("| 提交 | 上游做什么 | 你现在的状况 | 结论 |")
    write("| --- | --- | --- | --- |")
    for sha, what, yours, verdict in VERDICT_NOTES:
        write(f"| `{sha}` | {what} | {yours} | {verdict} |")
    write("")
    write("## 逐条")
    write("")
    write("| # | 提交 | 日期 | 类型 | 主题 | 文件 | 重叠 | 移植形态 | 批次 | 判定 |")
    write("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for index, row in enumerate(rows, start=1):
        subject = str(row["subject"]).replace("|", "\\|")
        write(
            f"| {index} | `{row['sha']}` | {row['date']} | {row['kind']} | {subject} "
            f"| {row['files']} | {row['overlap']} | {row['shape']} | {row['batch']} | {row['verdict']} |"
        )
    write("")

    write("## 批次汇总")
    write("")
    write("| 批次 | 内容 | 提交 | 触及文件 | 与你重叠 |")
    write("| --- | --- | --- | --- | --- |")
    for bid, title, shas in BATCHES:
        files: set[str] = set()
        for sha in shas:
            row = next((r for r in rows if r["sha"] == sha), None)
            if row is None:
                continue
            for entry in row["entries"]:  # type: ignore[union-attr]
                files.add(entry.path)
        overlap = len(files & fork_changed)
        write(f"| {bid} | {title} | {len(shas)} | {len(files)} | {overlap} |")
    write("")

    write("## 冲突热点")
    write("")
    write("双方都改过、且本仓库改动量最大的文件。改这些地方之前先想清楚要不要动。")
    write("")
    fork_numstat: dict[str, int] = {}
    for line in git("diff", "--numstat", base_sha, fork_sha).splitlines():
        parts = line.split("\t")
        if len(parts) >= 3:
            fork_numstat[parts[2]] = (int(parts[0]) if parts[0].isdigit() else 0) + (
                int(parts[1]) if parts[1].isdigit() else 0
            )
    upstream_numstat: dict[str, int] = {}
    for line in git("diff", "--numstat", base_sha, upstream_sha).splitlines():
        parts = line.split("\t")
        if len(parts) >= 3:
            upstream_numstat[parts[2]] = (int(parts[0]) if parts[0].isdigit() else 0) + (
                int(parts[1]) if parts[1].isdigit() else 0
            )
    hotspots = sorted(
        (path for path in both if not path.startswith(HOTSPOT_NOISE)),
        # 行数相同的按路径排，否则 set 的迭代顺序会让每次输出不一致
        key=lambda path: (-(fork_numstat.get(path, 0) + upstream_numstat.get(path, 0)), path),
    )[:25]
    write("已排除 `CHANGELOG.md`、`i18n/`、`fastlane/`、`docs/`、`gradle/libs.versions.toml`：")
    write("这几处每个 fork 都会改，放进来会盖住真正的热点。")
    write("")
    write("| 文件 | 上游改动行 | 本仓库改动行 |")
    write("| --- | --- | --- |")
    for path in hotspots:
        write(f"| `{path}` | {upstream_numstat.get(path, 0)} | {fork_numstat.get(path, 0)} |")
    write("")

    if args.details:
        write("## 逐文件状态")
        write("")
        for row in rows:
            write(f"### `{row['sha']}` {row['subject']}")
            write("")
            for entry in row["entries"]:  # type: ignore[union-attr]
                detail = f" {entry.detail}" if entry.detail else ""
                write(f"- {entry.kind}{detail} — `{entry.path}`")
            write("")

    target = root / args.output
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("\n".join(out) + "\n", encoding="utf-8", newline="\n")
    print(f"已写入 {target.relative_to(root)}：{len(rows)} 条提交，{len(both)} 个共同改动文件")
    return 0


if __name__ == "__main__":
    sys.exit(main())
