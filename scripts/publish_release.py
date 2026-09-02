"""发布 GitHub Release：创建或更新 Release，写说明正文，并上传指定 APK 附件。

用法：
    python scripts/publish_release.py v2.2.1 arm64-v8a armeabi-v7a x86_64 universal

- Release 不存在时自动创建（非 draft、非 prerelease），已存在则只更新正文，可重复运行
- 正文取自 docs/release-post-<tag>.md（去掉一级标题，GitHub 会自己显示标题）
- APK 取自 app/build/outputs/apk/vienna/MihonVienna-<versionName>-<abi>.apk，
  按语义版本号排序取最新版，不会因 2.10.0 < 2.2.1 的字符串比较而误传旧包
- 同名的旧附件会先删除再上传，重复运行是幂等的
- Token 从 secrets.properties 读取，绝不写死在脚本里，也不入库

完整发布流程见 docs/release-process.md。
"""

from __future__ import annotations

import pathlib
import re
import sys

import requests

ROOT = pathlib.Path(__file__).resolve().parent.parent
REPO = "Kurashift/mihon-vienna"
API = "https://api.github.com"
APK_DIR = ROOT / "app" / "build" / "outputs" / "apk" / "vienna"

APK_MIME = "application/vnd.android.package-archive"

# 文件名里的版本号，如 MihonVienna-2.2.1-arm64-v8a.apk -> (2, 2, 1)
APK_VERSION = re.compile(r"MihonVienna-(\d+)\.(\d+)\.(\d+)-")


def apk_sort_key(apk: pathlib.Path) -> tuple[int, int, int]:
    """Sort APKs by semantic version rather than by file name.

    A plain string sort would rank 2.10.0 below 2.2.1 ('1' < '2'), which quietly uploads
    the older build the moment the minor version reaches two digits.
    """
    match = APK_VERSION.search(apk.name)
    return (0, 0, 0) if match is None else tuple(map(int, match.groups()))


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

    for abi in abis:
        candidates = sorted(
            APK_DIR.glob(f"MihonVienna-*-{abi}.apk"),
            key=apk_sort_key,
        )
        if not candidates:
            sys.exit(f"找不到 {abi} 的 APK，请先执行 gradlew :app:assembleVienna")
        apk = candidates[-1]
        print(f"  {abi}: {apk.name}")

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
