# StrongholdShell —— 卫戍协议：盟约 · 安卓版（自建壳）

> ## 🎮 游戏本体来自上游项目
> ### 👉 https://github.com/sganggs/Stronghold-Protocol
> **本项目只提供「安卓壳」（把游戏装进手机的容器），游戏内容完全来自上面这个上游项目。**
> 想看游戏源码、提游戏相关的 issue、了解玩法与开发，请去上游仓库。
>
> 本仓库 = 上游游戏 + 我们自写的安卓壳（WebView + 内嵌 Node）。

自建的极简 Android 壳：**WebView + 内嵌 Node 服务器**，把上游
[`sganggs/Stronghold-Protocol`](https://github.com/sganggs/Stronghold-Protocol)
这类「Node + 前端」的浏览器游戏装进手机，**离线单机可玩**，也能当房主让同一 Wi-Fi 的朋友连进来。

- 🎮 **游戏本体**：<https://github.com/sganggs/Stronghold-Protocol>（上游，**先看这个**）
- 📦 **安卓安装包**：见本仓库 [Releases](../../releases/latest)，文件名 `Stronghold-Protocol-v<版本>-android.apk`
- 🔧 **壳源码完全自写**（7 个 Kotlin 文件 + 构建脚本），**不含游戏素材**
- 🔁 **上游更新时可一键同步重建**（见下方「跟上游更新」）

## ⚠️ 声明

> 本项目是玩家自制的**非官方同人作品**，与上海鹰角网络科技有限公司（Hypergryph）、Yostar
> 及其关联方**没有任何关系**，未获其授权或认可。
>
> 《明日方舟》及「卫戍协议」相关的名称、角色、美术、音乐、音效、文本与数据等素材，
> 版权归原权利人所有，**不适用**本项目的许可证；许可证只覆盖本项目自己编写的**壳代码**。
>
> 仅供学习交流与个人非商业使用，**严禁任何形式的盈利**。仓库源码**不包含**游戏素材；
> Releases 中的安装包为方便玩家附带了素材（由构建时从上游下载，不入库）。
> 权利人如认为本项目侵犯其权益，请通过 Issue 联系，**立即删除**相关内容。

## 这是什么 / 不是什么

| | |
|---|---|
| ✅ 是 | 一个把你自己的 Node 游戏装进手机的 **Android 壳** |
| ✅ 是 | 手机上**开箱即玩**的 APK（内嵌 Node，无需电脑、无需联网） |
| ❌ 不是 | 游戏本身。游戏内容来自上游项目，壳只是「容器」 |
| ❌ 不是 | 上游的 PR。本项目是**独立仓库**（非 Fork），与上游无关联 |

## 快速开始

### 方式一：装现成 APK（推荐）

1. 到 [Releases](../../releases/latest) 下载最新版：
   - `Stronghold-Protocol-v<版本>-android.apk` —— 完整包（约 850 MB）
   - 或用分片 `...part-00 ~ part-09`（每片 <95 MB，方便手机端下载），下完后按顺序合并：
     ```bash
     cat Stronghold-Protocol-v0.2.5-android.apk.part-* > game.apk
     ```
2. 手机上安装（需允许「安装未知应用」）。要求 **Android 7.0（API 24）及以上、arm64-v8a**，
   磁盘留出 1 GB 以上。
3. 首次启动会解压游戏资源（几十秒），之后正常进入游戏。

> 校验：Release 说明里会给出安装包的 **SHA-256**，可自行核对。

### 方式二：自己从源码构建

因为壳依赖 60 MB 的 `libnode.so` 和上游游戏包（都不入库），构建流程由脚本自动准备：

```bash
bash scripts/fetch_libnode.sh    # 准备 libnode.so + libc++_shared.so → jniLibs
bash scripts/fetch_bundle.sh     # 下载上游游戏包 → assets/app_bundle.zip
./gradlew :app:assembleRelease   # 出包：app/build/outputs/apk/release/
```

**没有本地 JDK/Android SDK？** 直接推到 GitHub，用 Actions 云端编译：
仓库 → Actions → 「Build APK」→ Run workflow，几分钟后到 Releases 拿包。

## 跟上游更新

上游（`sganggs/Stronghold-Protocol`）每出新版，本项目需要**重新构建**才能用上新内容。
一句话触发：

```bash
export GH_TOKEN=ghp_xxxx                        # 你的 GitHub token
bash scripts/sync-upstream.sh                    # 只看上游有没有新版
bash scripts/sync-upstream.sh --build            # 有新版就触发云端重建
```

脚本会：查上游最新版本 → 和本地打包版本对比 → 不同则触发 Actions 构建新 APK。

> **注意**：这是「仓库代码的同步」，**不是手机 App 自动更新**。
> 构建完的新 APK 仍需你**手动下载安装**（Android 不允许 App 自己给自己升级）。

## 结构

```
app/src/main/java/com/stronghold/shell/
  MainActivity.kt     WebView + 启动流程 + 沉浸式全屏
  NodeService.kt      前台服务：进程内启动 Node
  NodeRunner.kt       JNA 直调 libnode.so 的封装
  AndroidBridge.kt    JS↔Kotlin 桥（注入名 AndroidNative）
  AssetHelper.kt      解压 assets/app_bundle.zip → 私有目录
  FileLogger.kt       落盘日志
  NetworkUtils.kt     局域网 IP
.github/workflows/build.yml     GitHub Actions 云构建（产出正式命名 APK + Release）
scripts/fetch_libnode.sh        准备 libnode.so / libc++_shared.so
scripts/fetch_bundle.sh         下载上游包 → assets/app_bundle.zip
scripts/sync-upstream.sh        上游更新时一键同步重建
prebuilt/arm64-v8a/libc++_shared.so   入库的 C++ 运行时（见下方排错第 5 条）
```

## 工作机制

```
首启：AssetHelper 解压 assets/app_bundle.zip → filesDir/bundle
  ↓
NodeService：前台服务，用 JNA 直调 libnode.so
  ↓
JNA: NativeLibrary.getInstance("node").getFunction("_ZN4node5StartEiPPc")
     → node::Start(argc, argv)   （argv = ["node", <bundle>/server/index.js]）
  ↓
Node 监听 :3000，健康检查 /healthz
  ↓
MainActivity：轮询 /healthz 健康后 WebView 加载 index.html
  ↓
前端通过 window.AndroidNative.reportClientState({booted:true}) 回报
```

### 为什么用 JNA 而不是 NDK 编译
JNA 直接拿 `libnode.so` 导出的 **C++ 符号** `_ZN4node5StartEiPPc`
（即 `node::Start(int, char**)`），无需自己写 JNI 胶水、无需 NDK 编译。
只需一个 Maven 依赖 `net.java.dev.jna:jna`。

## 云构建排错备忘（实测踩坑）

1. **不要用 `android-actions/setup-android@v3`**：新版 ubuntu runner 已预装 SDK，
   该 action 会执行已废弃的 `sdkmanager tools` 而报 `Failed to find package 'tools'`。
   直接用 runner 的 `ANDROID_HOME` 即可。
2. **必须提供 `mipmap/ic_launcher`**：manifest 引用了它，缺了会
   `AAPT: error: resource mipmap/ic_launcher not found`。
3. `gradle-wrapper.jar` / `gradlew` 未提交时，workflow 会用 runner 全局
   `gradle wrapper --gradle-version 8.7` 现场生成。
4. Gradle 8.7 + AGP 8.5.2 需 JDK 17（workflow 已配 temurin 17）。
5. **必须打包 `libc++_shared.so`**（关键！）：`libnode.so` 依赖 C++ 运行时
   `libc++_shared.so`。若 APK 里只有 `libnode.so` 而没有它，运行时会报
   `dlopen failed: library "libc++_shared.so" not found`，Node 起不来、WebView 一直白屏。
   - nodejs-mobile 的 release 包里**不含**这个 .so，runner 的 NDK 里也常常找不到。
   - 因此本仓库把 `prebuilt/arm64-v8a/libc++_shared.so`（约 6.7 MB）**直接入库**，
     `scripts/fetch_libnode.sh` 会优先从这里复制到 `jniLibs`。
   - 可复用来源：任意 nodejs-mobile 安卓 App 的 `lib/arm64-v8a/libc++_shared.so`。
6. **入口脚本名**：上游入口是 `server/index.js`（不是 `index.js`/`main.js`）。
   `NodeService.kt` 用候选列表 `["server/index.js","index.js","main.js"]` 逐个探测。
7. **工作目录**：Node 启动前必须 `chdir` 到 bundle 目录（用 JNA 调 libc 的 `chdir`），
   `System.setProperty("user.dir")` 无效。
8. **targetSdk 34 的 .so 需要页对齐**：不要用「手工往 zip 追加 .so」的方式改包
   （会破坏对齐，安装时报 `INSTALL_FAILED_INVALID_APK: Failed to extract native libraries`）。
   交给 Gradle/AGP 构建，它会自动做 16KB 对齐。
9. **沉浸式全屏**：系统栏用 `WindowInsetsControllerCompat` 隐藏，
   并在 `onWindowFocusChanged` 里重复隐藏；systemBarsBehavior 设为
   `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`（下滑临时唤出）。

## 版本标记

`assets/bundle.sha256` 存的是「版本标记字符串」（不是真哈希）。
改这个字符串即可触发壳重新解压 `app_bundle.zip`。构建脚本会把上游版本号写在这里，
workflow 读它来命名 Release 与 APK。

## 合规红线

- 上游 README 声明素材版权归鹰角，禁止再分发。
- 因此：**含素材的 APK 不能发上游 PR**；能发 PR 的只有**本壳源码 + 构建脚本**。
- 本项目为**独立仓库**，不向任何上游仓库提交 PR，仅供自用分发。
- 自用打包请自行承担素材合规责任。
