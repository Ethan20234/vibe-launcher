# 基线：v1.5.4-poster（versionCode 34）

**这个 tag 之后的所有改动，都必须以它为基准。** 不要拿别的构建版本往回拼。

| 项 | 值 |
|---|---|
| 版本 | `versionCode 34` / `versionName 1.5.4-poster` |
| git tag | `v1.5.4-poster` |
| 在用的安装包 | `/home/admin/tv-apk/vibe-launcher-1.5.4-poster.apk` |
| 设备 | 海信 192.168.68.49:5555（adb server 端口 5038） |

## 不可动的外观约定

首排磁贴 = **App 自带横幅满幅铺满（A 版）**。某个 App 的横幅是"小 logo 挂在大深色板上"时，**也照样铺满**，
不要再引入 `isSparseArtwork()` 那种"稀疏图改成图标+底板"的判定——2026-11 试过一次，用户当场否掉。

## 本版改的三处（海报，不是外观）

1. `Backdrops.collect()`：卡片图全部不合格时，再用榜单兜底卡片试一次（判据是**产出为空**，不是**输入为空**）。
2. `Backdrops.iconStill()`：最终兜底，用 App 图标+名字画整屏，断网也不会留空磁贴。
3. `MainActivity.refreshPools`：首排包列表为空时 3 秒重试，新装/移入首排的 App 立刻出图。

## 构建

```bash
export JAVA_HOME=/home/admin/build/jdk17 ANDROID_HOME=/home/admin/build/android-sdk
export PATH=$JAVA_HOME/bin:$PATH
./gradlew assembleDebug --console=plain
# 产物 app/build/outputs/apk/debug/app-debug.apk
```

## 备份约定

每次出可用版本：APK 与 `git bundle --all` 一起传夸克网盘（沿用 `vibe-launcher-<version>.apk` /
`vibe-launcher-<YYYYMMDD-HHMM>.bundle` 命名），仓库推到 fork `Ethan20234/vibe-launcher`。