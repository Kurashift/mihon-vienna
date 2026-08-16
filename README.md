# Mihon Personal

基于 [Mihon](https://github.com/mihonapp/mihon) 的个人定制版漫画阅读器（Android）。

> 本项目是个人自用/二改项目，不是 Mihon 官方发布版。源码基于 Apache-2.0 协议发布，详见 [LICENSE](LICENSE)。

## 特性

在 Mihon 基础上增加/调整的个人功能包括：

- 音频/有声内容支持
- 本地源优先的构建变体（`localFirst`）
- 阅读器增强：随机阅读历史、阅读进度会话、滑动手势、Webtoon 选择等
- 浏览页增强：刷新章节、最近阅读、日期分组等
- 历史/搜索增强：搜索历史、一键清除历史等
- 更多个人设置项（我的列表、章节标志等）

具体变更可查看提交历史与 [CHANGELOG](CHANGELOG.md)。

## 构建

需要 JDK 17+ 和 Android SDK。

```bash
# 普通个人版
./gradlew app:assembleRelease

# 本地源优先版
./gradlew app:assembleLocalFirst
```

## 免责声明

本项目仅用于个人学习和使用，与 Mihon 官方无关。所有来源代码版权归各自作者所有。
