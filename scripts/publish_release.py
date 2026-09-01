"""发布 GitHub Release：更新说明正文，并上传指定 APK 附件。

用法：
    python scripts/publish_release.py v2.2.0 arm64-v8a x86_64

- 正文取自 docs/release-post-<tag>.md（去掉一级标题，GitHub 会自己显示标题）
- APK 取自 app/build/outputs/apk/vienna/MihonVienna-<versionName>-<abi>.apk
- 同名的旧附件会先删除再上传，重复运行是幂等的
- Token 从 secrets.properties 读取，绝不写死在脚本里，也不入库
"""

from __future__ import annotations

import pathlib
import sys

import requests

ROOT = pathlib.Path(__file__).resolve().parent.parent
REPO = "Kurashift/mihon-vienna"
API = "https://api.github.com"
APK_DIR = ROOT / "app" / "build" / "outputs" / "apk" / "vienna"

# abi -> 上传时的 Content-Type 与展示名后缀
APK_MIME = "application/vnd.android.package-archive"


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


def main() -> None:
    if len(sys.argv) < 3:
        sys.exit(__doc__)

    tag, abis = sys.argv[1], sys.argv[2:]
    token = read_token()
    auth = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"}

    release = requests.get(f"{API}/repos/{REPO}/releases/tags/{tag}", headers=auth, timeout=60)
    if release.status_code != 200:
        sys.exit(f"找不到 Release {tag}（HTTP {release.status_code}）：{release.text[:300]}")
    release = release.json()
    print(f"Release {tag}: {release['html_url']}")

    response = requests.patch(
        f"{API}/repos/{REPO}/releases/{release['id']}",
        headers=auth,
        json={"body": read_body(tag)},
        timeout=60,
    )
    response.raise_for_status()
    print("  说明正文已更新")

    existing = {asset["name"]: asset["id"] for asset in release["assets"]}
    upload_url = release["upload_url"].split("{")[0]

    for abi in abis:
        candidates = sorted(APK_DIR.glob(f"MihonVienna-*-{abi}.apk"))
        if not candidates:
            sys.exit(f"找不到 {abi} 的 APK，请先执行 gradlew :app:assembleVienna")
        apk = candidates[-1]

        if apk.name in existing:
            requests.delete(
                f"{API}/repos/{REPO}/releases/assets/{existing[apk.name]}",
                headers=auth,
                timeout=60,
            ).raise_for_status()
            print(f"  已删除旧附件 {apk.name}")

        with apk.open("rb") as handle:
            upload = requests.post(
                upload_url,
                headers={**auth, "Content-Type": APK_MIME},
                params={"name": apk.name},
                data=handle,
                timeout=600,
            )
        upload.raise_for_status()
        print(f"  已上传 {apk.name} ({apk.stat().st_size / 1e6:.1f} MB)")

    print("完成")


if __name__ == "__main__":
    main()
