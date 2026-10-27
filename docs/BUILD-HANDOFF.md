# 编译交接：StrongholdShell Lab 版（联机实验）

> 给 dsh（电脑端）的编译说明。
>
> **背景**：手机端（aarch64）已完成全部代码与配置，但 `aapt2` 只有 x86_64 版本，
> 无法在手机容器里做资源编译，需要你在电脑上跑最后一步。
>
> **结论：走 Gradle 路线。** 手工展开 aar 不可行（appcompat 1.7.0 有 26+ 个传递依赖，
> 漏一个就失败，且上游一更新全要重算）。

---

## 一、代码状态

仓库：`huajixiaobai/stronghold-shell`
本地路径（手机）：`/sdcard/stronghold-shell`

### 新增文件（3 个）

| 文件 | 行数 | 作用 |
|---|---|---|
| `app/src/main/java/com/stronghold/shell/ServerStore.kt` | 178 | 服务器列表存储 + 地址校验 |
| `app/src/main/java/com/stronghold/shell/WsHook.kt` | 174 | WebSocket hook 脚本生成器 |
| `app/src/main/java/com/stronghold/shell/MultiplayerActivity.kt` | 304 | 多人游戏界面 |

### 修改文件（5 个）

| 文件 | 改动 |
|---|---|
| `MainActivity.kt` | +130 行：接收 `EXTRA_SERVER_ADDRESS`、`shouldInterceptRequest` 注入 HTML、JS 桥 |
| `ImportActivity.kt` | +13 行：多人游戏入口按钮（受 `BuildConfig.ENABLE_MULTIPLAYER` 控制） |
| `AndroidManifest.xml` | 注册 `MultiplayerActivity`；`android:label` 改为 `${appLabel}` |
| `app/build.gradle.kts` | `productFlavors { stable / lab }`；`noCompress += listOf("zip","so")` |
| `settings.gradle.kts` | 加阿里云 Maven 镜像 |

### 已在手机端验证

- `ServerStore.kt` — kotlinc 1.9.24 编译通过，零错误
- `WsHook.kt` — kotlinc 1.9.24 编译通过，零错误
- `processLabDebugManifestForPackage` 通过 → Manifest 占位符、Activity 注册无误
- `checkLabDebugDuplicateClasses` 通过 → 依赖无冲突
- `mergeLabDebugAssets` / `desugarLabDebugFileDependencies` 通过
- **仅 `processLabDebugResources` 因 aapt2 架构不符失败**（x86_64 二进制跑不了 ARM）

---

## 二、构建变体

```kotlin
flavorDimensions += "channel"
productFlavors {
    create("stable") {
        applicationId = "com.stronghold.shell"      // 正式版
        versionName   = "2.0.1"
        versionCode   = 3
        buildConfigField("boolean", "ENABLE_MULTIPLAYER", "false")
        manifestPlaceholders["appLabel"] = "卫戍协议启动器"
    }
    create("lab") {
        applicationId = "com.stronghold.shell.lab"  // 联机实验版
        versionName   = "0.1.0-lab"
        versionCode   = 1
        buildConfigField("boolean", "ENABLE_MULTIPLAYER", "true")
        manifestPlaceholders["appLabel"] = "卫戍协议启动器 Lab"
    }
}
```

**两个 APK 包名不同，可同时安装，互不覆盖。**

⚠️ **flavor 名不能叫 `main`** —— 会和默认 sourceSet 撞车，报
`Multiple entries with same key: main=[] and main=[]`。已改用 `stable`。

---

## 三、编译前必须先补两样东西

`.gitignore` 明确排除了这两项（CI 从别处获取），本地编译必须先准备：

```bash
cd stronghold-shell

# ① libnode.so（60MB，arm64）—— APK 里跑 Node 的核心
bash scripts/fetch_libnode.sh
# 产物应在：app/src/main/jniLibs/arm64-v8a/libnode.so

# ② app_bundle.zip（内置游戏包，可选）
#    只有「没导入任何包」时才会解压它做兜底。有正式包可以不准备。
bash scripts/fetch_bundle.sh
```

### 签名

```bash
# 仓库不含 keystore。最好复用现有正式版的签名（否则无法覆盖升级）
# 目录里的旧 APK 可以查看它用的签名：
keytool -printcert -jarfile old.apk | head -20
```

若确实没有 keystore，Gradle 会回退 debug 签名——**Lab 版包名独立，debug 签名也能装**，
只是以后不能覆盖升级同一包名。

---

## 四、编译命令

### 环境

```
JDK 17
Android SDK: platforms/android-34 + build-tools/34.0.0
Gradle 8.7（国内镜像：https://mirrors.cloud.tencent.com/gradle/gradle-8.7-bin.zip）
```

### 首次准备

```bash
# 1. local.properties（.gitignore 里排除了，必须自己建）
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2. 接受 SDK license（若未接受过）
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
```

### 编译

```bash
export ANDROID_HOME=/path/to/android-sdk

# Lab 版（含联机功能）
./gradlew :app:assembleLabRelease --no-daemon

# 正式版
./gradlew :app:assembleStableRelease --no-daemon
```

产物：

```
app/build/outputs/apk/lab/release/app-lab-release.apk
app/build/outputs/apk/stable/release/app-stable-release.apk
```

### 对齐检查（硬性要求）

```bash
# AGP 8+ 会自动 zipalign，但务必验证：
$ANDROID_HOME/build-tools/34.0.0/zipalign -c -v -p 4 app-lab-release.apk | tail -5
# 期望：Verification succesful（注意 -p 确保 .so 做 4KB 页对齐）
```

### 签名校验

```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --verbose app-lab-release.apk
```

---

## 五、这套改动的技术原理（请勿改动核心逻辑）

### 联机架构

```
┌─ 玩家手机 ─────────────────────────┐
│                                    │
│  WebView（画面）                    │
│      ↓ http://127.0.0.1:3000       │
│  手机内 Node（NodeService）          │
│      └─ 读本地游戏包 + 素材          │
│                                    │
│  【注入脚本 hook window.WebSocket】  │
│      ↓ 只改 /ws 的连接目标           │
└──────────────┬─────────────────────┘
               ↓ 只传房间同步消息（每局几十 KB）
       ┌───────────────┐
       │  公网服务器     │  只做房间协调
       │  不传素材       │
       └───────────────┘
```

### 三个关键设计

**① 与上游解耦**
只认两个运行时特征：`协议 ∈ {ws:, wss:}` + `pathname === '/ws'`。
不引用上游任何变量名、函数名、模块路径。

**② 注入时机用 `shouldInterceptRequest`，不是 `onPageStarted`**
`onPageStarted` 是异步投递的，页面内联脚本可能先跑，导致上游在 hook 生效前
就把 `WebSocket` 取了引用。正确做法：拦截 `http://127.0.0.1:3000/index.html`，
用 `HttpURLConnection` 取回 HTML，把 `<script>` 插到 `<head>` 之后返回。

**③ 存档不丢**
WebView 数据目录不变，`localStorage` / `IndexedDB` 全部保留。

### 依据的上游源码事实

```js
// public/js/net.js:230-232
const WS = this.WS || globalThis.WebSocket;   // ← 运行时读取，不是模块顶层缓存
const url = this.url || defaultWsUrl();        // ← "$ws://$location.host/ws"
let ws = new WS(url);
```

因为 `WebSocket` 是在 `connect()` 时才从 `globalThis` 取的，hook 才有效。

---

## 六、上游更新后的维护

| 上游（sganggs/Stronghold-Protocol）变了什么 | 启动器要改吗 |
|---|---|
| 游戏内容、关卡、干员、素材 | ❌ 不用 |
| `public/js/net.js` 内部实现 | ❌ 不用（只按 `/ws` 路径改写） |
| **WebSocket 路径从 `/ws` 改成别的** | ⚠️ 改 `WsHook.kt` 里 `WS_PATH` 常量 |
| **不再用 `window.WebSocket`** | ⚠️ 极少可能 |

**日常流程完全不变：**

1. 手机端启动器照常「下载最新完整包」（`PackDownloader` 逻辑未改动）
2. 电脑端重新 `./gradlew :app:assembleLabRelease`
3. 新 APK 覆盖安装（同包名、同签名）

**这就回答了用户的顾虑：不需要每次重新做适配，只需要重跑一条命令。**

---

## 七、上线后可以考虑的合并

联机功能跑通、验证稳定后：

```kotlin
// 把 stable flavor 的开关打开即可，代码不用动
create("stable") {
    buildConfigField("boolean", "ENABLE_MULTIPLAYER", "true")  // ← 改这一行
}
```
或者干脆删掉 flavor 区分，只保留 lab 的配置。

---

## 八、手机端已有的产物（供参考）

| 路径 | 说明 |
|---|---|
| `/root/kotlinc` | Kotlin 1.9.24（已验证可编译本项目部分文件） |
| `/opt/gradle-8.7` | Gradle 8.7 已装，但 aapt2 架构不符 |
| `/root/.gradle` | 769MB 依赖缓存（阿里云镜像已拉全，可打包带走复用） |
| `/root/android-sdk` | SDK 34（aapt2/zipalign 是 x86_64，d8/apksigner 是 java 脚本可跑） |