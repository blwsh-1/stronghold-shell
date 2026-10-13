# StrongholdShell —— 自建安卓壳

> 一个**从零自写**的极简 Android 壳：WebView + 内嵌 Node 服务器（nodejs-mobile）。
> 用来承载 `sganggs/Stronghold-Protocol` 这类「Node + 前端」的离线游戏。
>
> ✅ 壳源码完全属于自己，可自由发布（不含游戏素材时）。
> ❌ 不要把游戏素材（美术/音频）提交到本仓库，也不要发到上游 PR。

## 结构

```
app/src/main/java/com/stronghold/shell/
  MainActivity.kt     WebView + 启动流程 + 自愈
  NodeService.kt      前台服务：进程内启动 Node
  NodeRunner.kt       JNA 直调 libnode.so 的封装
  AndroidBridge.kt    JS↔Kotlin 桥（注入名 AndroidNative）
  AssetHelper.kt      解压 assets/app_bundle.zip → 私有目录
  FileLogger.kt       落盘日志
  NetworkUtils.kt     局域网 IP
.github/workflows/build.yml   GitHub Actions 云构建
scripts/fetch_bundle.sh       下载上游包 → assets/app_bundle.zip
```

## 工作机制

```
首启：AssetHelper 解压 assets/app_bundle.zip → filesDir/bundle
  ↓
NodeService：前台服务，用 JNA 直调 libnode.so
  ↓
JNA: NativeLibrary.getInstance("node").getFunction("_ZN4node5StartEiPPc")
     → node::Start(argc, argv)
  ↓
Node 监听 127.0.0.1:3000，健康检查 /healthz
  ↓
MainActivity：轮询 /healthz 健康后 WebView 加载 index.html
  ↓
前端通过 window.AndroidNative.reportClientState({booted:true}) 回报
```

### 为什么用 JNA 而不是 NDK 编译
复刻现有壳的做法：JNA 直接拿 `libnode.so` 导出的 **C++ 符号** `_ZN4node5StartEiPPc`
（即 `node::Start(int, char**)`），无需自己写 JNI 胶水、无需 NDK 编译。
只需一个 Maven 依赖 `net.java.dev.jna:jna`。

## 云构建（无需本地 JDK/SDK）

1. 把本目录推到你的 GitHub 仓库
2. push 或手动触发 Actions → 自动产出 `stronghold-shell-apk` artifact
3. （可选）填 `scripts/fetch_bundle.sh` 的逻辑会自动拉最新上游包打进 APK

## 云构建排错备忘（实测踩坑）

1. **不要用 `android-actions/setup-android@v3`**：新版 ubuntu runner 已预装 SDK，
   该 action 会执行已废弃的 `sdkmanager tools` 而报 `Failed to find package 'tools'`。
   直接用 runner 的 `ANDROID_HOME` 即可。
2. **必须提供 `mipmap/ic_launcher`**：manifest 引用了它，缺了会
   `AAPT: error: resource mipmap/ic_launcher not found`。本仓库带 5 套密度 PNG。
3. `gradle-wrapper.jar` / `gradlew` 未提交时，workflow 会用 runner 全局
   `gradle wrapper --gradle-version 8.7` 现场生成。
4. Gradle 8.7 + AGP 8.5.2 需 JDK 17（workflow 已配 temurin 17）。

## 版本标记

`assets/bundle.sha256` 存的是「版本标记字符串」（不是真哈希）。
改这个字符串即可触发壳重新解压 `app_bundle.zip`。

## 合规红线

- 上游 README 声明素材版权归鹰角，禁止再分发。
- 因此：**含素材的 APK 不能发上游 PR**；能发 PR 的只有**本壳源码 + 构建脚本**。
- 自用打包请自行承担素材合规责任。
