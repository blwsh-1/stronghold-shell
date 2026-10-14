# 阶段6：Forge + mod 支持 · 实测结论与启动器改造设计

> 更新：2026-10-14 ｜ 维护：蓝色大肥鱼 🐟
> 结论来源：容器内对 Stronghold-Protocol-Forge v0.18.3 的**完整实测**（非推测）。

---

## 一、实测结论（决定性）

### 1.1 Forge 在手机上能跑
| 项 | 结果 |
|---|---|
| Forge 版本 | 0.18.3（head `2303e44`） |
| 全量大小 | 1787 文件 / 69MB（raw.githubusercontent.com 直连，83 秒下完） |
| 依赖安装 | `npm install --omit=dev` → 92 包 / 19 秒 / **零原生模块编译** |
| 启动 | `PORT=3999 node server/index.js` → `/healthz` 200、`GET /` 200（7481B） |
| 与上游关系 | 根目录结构与上游一致（`server/` `public/` `packs/` `shared/`）+ 自带 `workshop/` |

**结论：Forge 就是「上游 + workshop 装载器」，启动器无需为它写第二套逻辑。**

### 1.2 mod 加载机制（已对照验证，闭环）
工坊包 `mod.zip` 放法：解压到 `<repo>/workshop/<mod-id>/`，重启即生效。

**对照实验 A（装 mod）**：
```json
GET /healthz → {..., "mods":"bac3713a96bf...", "modPacks":1}
GET /mod/healthz-ext.json → HTTP 200
GET /mod/client-build.json → HTTP 200
```

**对照实验 B（移除 mod）**：
```json
GET /healthz → {..., 无 mods / modPacks 字段}
GET /mod/healthz-ext.json → HTTP 404
```

**→ 机制确认：mod 存在 = 被加载 = 其声明路由激活；mod 不存在 = 完全无痕。**
启动器只要会把 zip 解压进 `workshop/<id>/`，mod 功能就成立。

### 1.3 Forge 工坊包（mod.zip）内部格式
```
mod.zip
├── pack.json        # 清单：id / name / version / game / api / combat
│                    #   client.panels[] { slot:"root.guide", module, styles, order }
│                    #   routes[] { path:"/mod/xxx.json", file, cache }
├── README.md
├── data/*.json      # mod 自带数据
├── ui/panel.js|css  # 前端面板
└── test/*.mjs
```
> 关键：`mod.zip` 内是**扁平的包体**，解压到 `workshop/<id>/` 后直接被 `pack.json` 识别。

### 1.4 硬规矩（源码实证，必须遵守）
| 规矩 | 说明 |
|---|---|
| `WORKSHOP_DIR = <repo>/workshop` | 缺失 = 正常（不算错误） |
| `mod-registry.json` 放**状态根** | `<repo>/var`（或环境变量 `SP_STATE_DIR`），**绝不能放进 workshop/**，否则被当包扫 |
| 被禁用的包必须「彻底不在」 | 没有「禁用但仍可见」的中间态 → 启动器删 mod 就是删目录 |

---

## 二、启动器改造设计

### 2.1 好消息：几乎不用改
现有 `GamePack.kt` 的 `locateGameRoot()` 探测逻辑是「找 `node_modules` + 入口文件」——
**Forge 包同样满足这个特征**，所以 Forge 包能被直接识别、导入、启动。

### 2.2 需要新增的东西（极简）
| # | 改动 | 位置 | 说明 |
|---|---|---|---|
| 1 | 识别包「类型」 | `GamePack.kt` | 读包内是否存在 `workshop/` 或 `shared/constants.js` 的 `APP_VERSION`，判定 upstream / forge |
| 2 | **mod 导入入口** | 新增 `ModImportActivity`（或复用 ImportActivity） | 用户选中的 `mod.zip` → 解压到 `active/workshop/<pack.json.id>/` |
| 3 | mod 列表 / 删除 | UI | 列 `active/workshop/*/pack.json` 的 name/version；删除 = 删对应目录 |
| 4 | 状态目录保障 | `GamePack.kt` | 确保 `active/var/` 存在（`mod-registry.json` 落这里），或在启动前 `mkdir -p` |
| 5 | 重启加载 | `NodeService.kt` | 装/删 mod 后需重启 Node 进程（mod 在启动时装载） |

### 2.3 不需要做的
- ❌ 不需要为 Forge 单独做一套启动流程
- ❌ 不需要改 NodeRunner / AndroidBridge
- ❌ 不需要动 Node 启动参数（Forge 读 `workshop/` 是自动的）

---

## 三、下一步实施顺序

1. `GamePack.kt`：加包类型判定（读 `package.json` 或 `shared/constants.js`）
2. 新增 mod 导入：选 zip → 校验 `pack.json` → 解压到 `active/workshop/<id>/`
3. 新增 mod 管理界面：列表 + 删除（删除即彻底移除目录）
4. `NodeService.kt`：装/删 mod 后触发重启
5. 真机验证：装 `healthz-ext` → 开游戏 → 看 `/healthz` 是否出现 `mods` 指纹

---

_本文件为阶段6 的设计依据，随实现进度更新。_ 🐟
