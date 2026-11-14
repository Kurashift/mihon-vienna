# 上游基线

分叉自 [mihonapp/mihon](https://github.com/mihonapp/mihon)。这份文档记录分叉点，以及为什么需要一条命令才能让 `git merge-base` 有值。

## 结论

| | |
|---|---|
| 分叉根提交 | `45d428d89`（2026-08-16，`Initial commit`，1384 文件 / 233,935 行） |
| 推定上游基线 | `3cb9883d6`（2026-08-08，`Make MigrateSourceViewModel collect sources only while subscribed`） |
| 基线之后上游又走了 | 353 个提交 |
| 从基线到当前 HEAD | 619 文件，+75,818 / −5,583 行（新增 318 / 修改 276 / 删除 24） |

## 为什么没有共同祖先

仓库的第一次提交是一次性导入的完整工作树，不是从上游 clone 出来的。所以 `git merge-base HEAD upstream/main` 一直返回空，`git merge upstream/main` 会以「无关历史」拒绝。

解法是把根提交嫁接到推定基线之上：

```bash
git remote add upstream https://github.com/mihonapp/mihon.git   # 已有则跳过
git fetch upstream
git replace --graft 45d428d89 3cb9883d6
git merge-base HEAD upstream/main     # 返回 3cb9883d6...
```

`git replace` 只写本地 `refs/replace/`，不随 push 走，也不会改写任何历史。每个新 clone 都要重跑一次上面这条 graft。

撤销：`git replace -d 45d428d89`。

## 基线是怎么定的

在 2026-08-01 到 2026-08-17 之间的 66 个上游提交里，逐个算「与 `45d428d89` 的差异行数」，取最小的那个：

```bash
for c in $(git rev-list --since=2026-08-01 --until=2026-08-17 upstream/main); do
  echo "$(git diff --shortstat "$c" 45d428d89 | tail -1) $c"
done | sort -n | head
```

最小的是 `3cb9883d6`（22,186 行 / 255 文件），相邻提交都在 22.2k 以上且单调变大。

**这是估出来的，不是血缘记录。** 如果将来合并时出现「上游整片文件被当成新增」的冲突，说明基线偏早或偏晚，换相邻提交重试，先 `git replace -d` 再 `git replace --graft 45d428d89 <另一个>`。

## 将来合并上游时

- 先确认 replace ref 还在：`git replace -l` 应该列出 `45d428d89`
- `git merge upstream/main` 只会重放分叉自己的改动；冲突集中在被改过的 276 个上游文件
- 改动最密集的是 `app/`（422 个文件），其次 `domain/`（36）、`data/`（33）、`i18n/`（29）
- 在分支上做，不要在 `main` 上直接合；合并后跑 `./gradlew spotlessCheck testDebugUnitTest verifySqlDelightMigration`
- 上游改过的文件里有一部分是格式化与依赖升级的噪音，按模块分批处理比一次合完容易定位

## 不要做的事

- 不要为了「让历史看起来连续」去改写分叉历史（`git filter-repo` / rebase 全部提交）。那会让所有已发布的 tag 失效，而收益只是让 `git log` 好看一点
- 不要在没确认 replace ref 的情况下直接 `git merge upstream/main`，会得到「refusing to merge unrelated histories」
