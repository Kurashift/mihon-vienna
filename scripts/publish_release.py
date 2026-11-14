"""发布 GitHub Release：校验版本一致性，创建或更新 Release，写正文，上传 APK 与校验和。

用法：
    python scripts/publish_release.py v2.4.0 arm64-v8a armeabi-v7a x86_64 universal
    python scripts/publish_release.py --check v2.4.0 arm64-v8a    # 只校验，不联网

校验（任何一项对不上就退出，发生在联网之前）：
- tag 与 app/build.gradle.kts 的 versionName 一致
- CHANGELOG.md 第一个版本标题就是 tag（最新一版必须排在最前）
- docs/release-post-<tag>.md 存在
- 工作区干净、当前在 main、tag 指向 HEAD
- output-metadata.json 里每个元素的 versionCode / versionName 都是目标版本
- 每个请求的 ABI 都有 MihonVienna-<versionName>-<abi>.apk

上传：
- APK 按 tag 对应的版本精确取，不按目录里「最大的那个」取——产物目录会累积历次
  构建，按最大版本取会把留在目录里的诊断包传上去
- 顺带生成并上传 SHA256SUMS，手动下载的用户可以自证文件完整
- 同名旧附件先删再传，整条命令幂等，可重复运行
- Token 从 secrets.properties 读取，绝不写死在脚本里，也不入库

完整发布流程见 docs/release-process.md。
"""

from __future__ import annotations

import hashlib
import json
import pathlib
import re
import subprocess
import sys

import requests

ROOT = pathlib.Path(__file__).resolve().parent.parent
REPO = "Kurashift/mihon-vienna"
API = "https://api.github.com"
APK_DIR = ROOT / "app" / "build" / "outputs" / "apk" / "vienna"
GRADLE = ROOT / "app" / "build.gradle.kts"
CHANGELOG = ROOT / "CHANGELOG.md"
METADATA = APK_DIR / "output-metadata.json"

APK_MIME = "application/vnd.android.package-archive"
SUMS_NAME = "SHA256SUMS"

ALL_ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "universal")


def fail(message: str) -> None:
    sys.exit(f"发布校验失败：{message}")


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def gradle_version() -> tuple[int, str]:
    text = GRADLE.read_text(encoding="utf-8")
    code = re.search(r"^\s*versionCode\s*=\s*(\d+)", text, re.M)
    name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', text, re.M)
    if not code or not name:
        fail("app/build.gradle.kts 里读不到 versionCode / versionName")
    return int(code.group(1)), name.group(1)


def changelog_latest() -> str:
    for line in CHANGELOG.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^## \[(v\d+\.\d+\.\d+)\]", line)
        if match:
            return match.group(1)
    fail("CHANGELOG.md 里没有版本标题")


def preflight(tag: str, abis: list[str]) -> str:
    """Check everything that can be checked locally. Returns the version name."""
    if not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
        fail(f"tag {tag} 不是 vX.Y.Z 形式")

    version_code, version_name = gradle_version()
    if tag != f"v{version_name}":
        fail(f"tag {tag} 与 app/build.gradle.kts 的 versionName {version_name} 不一致")

    latest = changelog_latest()
    if latest != tag:
        fail(f"CHANGELOG.md 第一个版本标题是 {latest}，不是 {tag}：最新一版必须排在最前")

    if not (ROOT / "docs" / f"release-post-{tag}.md").exists():
        fail(f"缺少发布正文 docs/release-post-{tag}.md")

    if git("status", "--porcelain"):
        fail("工作区有未提交改动，先提交或清理再发版")

    branch = git("rev-parse", "--abbrev-ref", "HEAD")
    if branch != "main":
        fail(f"当前分支是 {branch}，发版必须站在 main 上")

    if git("rev-parse", f"{tag}^{{commit}}") != git("rev-parse", "HEAD"):
        fail(f"tag {tag} 不在 HEAD 上：先合并 PR、切回 main、再在 main 上打 tag")

    if not METADATA.exists():
        fail(f"缺少 {METADATA.relative_to(ROOT)}：先执行 gradlew :app:assembleVienna")

    elements = json.loads(METADATA.read_text(encoding="utf-8"))["elements"]
    for element in elements:
        actual = (element.get("versionCode"), element.get("versionName"))
        if actual != (version_code, version_name):
            fail(
                f"{element.get('outputFile')} 是 {actual[1]}/{actual[0]}，"
                f"不是 {version_name}/{version_code}：重新执行 gradlew :app:assembleVienna"
            )

    for abi in abis:
        if not (APK_DIR / f"MihonVienna-{version_name}-{abi}.apk").exists():
            fail(f"找不到 MihonVienna-{version_name}-{abi}.apk")

    missing = [abi for abi in ALL_ABIS if abi not in abis]
    if missing:
        print(f"  注意：本次不传 {'、'.join(missing)}，Release 上会缺这几个架构")

    return version_name


def read_token() -> str:
    secrets = ROOT / "secrets.properties"
    if not secrets.exists():
        sys.exit(f"缺少 {secrets.name}：请创建它并写入 githubToken=<你的 PAT>")

    for line in secrets.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        if key.strip() == "githubToken":
            return value.strip()

    sys.exit("secrets.properties 里没有 githubToken")


def read_body(tag: str) -> str:
    post = ROOT / "docs" / f"release-post-{tag}.md"
    if not post.exists():
        sys.exit(f"缺少发布日志 {post}")

    lines = post.read_text(encoding="utf-8").splitlines()
    # 去掉首个一级标题，Release 页面本身会显示版本号
    for index, line in enumerate(lines):
        if line.startswith("# "):
            del lines[index]
            break
    return "\n".join(lines).strip() + "\n"


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def upload(upload_url: str, auth: dict[str, str], existing: dict[str, int], path: pathlib.Path) -> None:
    name = path.name
    if name in existing:
        requests.delete(
            f"{API}/repos/{REPO}/releases/assets/{existing[name]}",
            headers=auth,
            timeout=60,
        ).raise_for_status()
        print(f"  已删除旧附件 {name}")

    with path.open("rb") as handle:
        response = requests.post(
            upload_url,
            headers={**auth, "Content-Type": APK_MIME},
            params={"name": name},
            data=handle,
            timeout=600,
        )
    response.raise_for_status()
    print(f"  已上传 {name} ({path.stat().st_size / 1e6:.1f} MB)")


def main() -> None:
    args = sys.argv[1:]
    check_only = "--check" in args
    args = [arg for arg in args if arg != "--check"]

    if len(args) < 2:
        sys.exit(__doc__)

    tag, abis = args[0], args[1:]
    version_name = preflight(tag, abis)

    apks = [APK_DIR / f"MihonVienna-{version_name}-{abi}.apk" for abi in abis]
    if check_only:
        print(f"校验通过：{tag}")
        for apk in apks:
            print(f"  {apk.name}  {sha256(apk)[:16]}…")
        return

    token = read_token()
    auth = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"}

    release = requests.get(f"{API}/repos/{REPO}/releases/tags/{tag}", headers=auth, timeout=60)
    if release.status_code == 200:
        release = release.json()
        print(f"Release {tag} 已存在：{release['html_url']}")
    else:
        # A new version's Release does not exist yet, so create it instead of failing: the tag
        # is already pushed by this point, and a release page nobody created is the common case.
        response = requests.post(
            f"{API}/repos/{REPO}/releases",
            headers=auth,
            json={
                "tag_name": tag,
                "target_commitish": "main",
                "name": f"Mihon Vienna {tag.lstrip('v')}",
                "body": read_body(tag),
                "draft": False,
                "prerelease": False,
            },
            timeout=120,
        )
        response.raise_for_status()
        release = response.json()
        print(f"已创建 Release {tag}: {release['html_url']}")

    response = requests.patch(
        f"{API}/repos/{REPO}/releases/{release['id']}",
        headers=auth,
        json={"body": read_body(tag)},
        timeout=60,
    )
    response.raise_for_status()
    print("  说明正文已更新")

    # Re-read so the asset list reflects the freshly created release too.
    release = requests.get(
        f"{API}/repos/{REPO}/releases/tags/{tag}", headers=auth, timeout=60
    ).json()
    existing = {asset["name"]: asset["id"] for asset in release["assets"]}
    upload_url = release["upload_url"].split("{")[0]

    for apk in apks:
        upload(upload_url, auth, existing, apk)

    sums = APK_DIR / SUMS_NAME
    sums.write_text(
        "".join(f"{sha256(apk)}  {apk.name}\n" for apk in apks),
        encoding="utf-8",
    )
    upload(upload_url, auth, existing, sums)

    print("完成")


if __name__ == "__main__":
    main()
