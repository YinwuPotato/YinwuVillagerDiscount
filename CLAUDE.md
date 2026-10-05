# YinwuVillagerDiscount — 共享村民折扣

## 项目信息
- **功能**: 任何人治愈僵尸村民后，全服玩家都享受那份折扣（移植 totos-carpet-tweaks 的 `sharedVillagerDiscounts`）
- **位置**: `Van\YinwuVillagerDiscount-村民折扣共享`（**Van 树 = Canvas 插件**，不是 Sur 树）
- **技术栈**: Java 21（`--release 21`）, Paper API（Canvas 完整实现）, **不用 Maven**
- **打包**: `build-javac.bat` → `yinwu-villagerdiscount-1.0.0.jar`（模块根目录）
  - Van 约定：产物 jar 走 GitHub Release，不进仓库树（`.gitignore` 已忽略 `*.jar`）
  - ⚠️ **脚本必须把 `Sur\YinwuPluginLib-共享库` shade 进包**：本插件 `extends YinwuPlugin`，
    而 Van 树其它插件都是独立实现；少了这一步运行时会 `NoClassDefFoundError`
- **辅助校验**: `自动化\compile-check.bat YinwuVillagerDiscount`（只编译不打包，Sur/Van 都支持）
- **Folia 兼容**: 是（已在 Canvas 26.3 实测）
- **跨树依赖**: 源码在 Van 树，但用 Sur 树的 `YinwuPluginLib`（构建脚本自动打进去）
- ⚠️ **`plugin.yml` 里是写死的字面值**（Van 不走 Maven，没有资源过滤）——
  改版本号要同时改 **三处**：`src\main\resources\plugin.yml` 的 `version:`、
  `pom.xml` 的 `<version>`、`build-javac.bat` 的 `OUT_JAR`。
  千万别在 plugin.yml 里用 Maven 占位符：Bukkit 校验插件名只允许 `[A-Za-z0-9 _.-]`，
  带 `$` `{` `}` 会直接拒绝加载。
- **仓库**: [YinwuPotato/YinwuVillagerDiscount](https://github.com/YinwuPotato/YinwuVillagerDiscount)（待建）
- **上游**: [totorewa/totos-carpet-tweaks](https://github.com/totorewa/totos-carpet-tweaks)（LGPL-3.0）

## 四条不可动摇的设计约束（改代码前先读）
1. **共享的传言类型**：`MAJOR_POSITIVE`（治愈 +20，权重 5）+ `MINOR_POSITIVE`（治愈 +25，权重 1）。
   **两种都必须共享** —— 原版治愈一次写两种（`Villager#onReputationEventFrom` 反编译确认），
   只共享前者会让被共享者的价格与治愈者不一致（实测 9 vs 4 绿宝石）。
   可用 `share.share-minor-positive: false` 退回原 mod 的严格行为。
   `TRADING` 与负面传言**永远不共享**（会把差评扩散给全服）。
2. **原始值上限必须自己守**：插件写的是 gossip 原始值，而 `setReputations` 走的是 `put`，
   **不经过原版 `add()` 的截断**。所以 MAJOR_POSITIVE 必须 ≤ 20、MINOR_POSITIVE ≤ 25，
   否则会写出超模折扣。配置里的 `max-value` 是"有效声望"口径（100），要除以权重 5 换算。
3. **增量法不能改**：`canonical = min(max(shared, applied) + max(0, curerNow - applied), rawCap)`。
   因为插件是"写数据"而不是"拦截查询"，不留 `applied` 基准的话，自己写的值会被下次求和重复累加。
4. **Folia 线程 + 一个时序坑**：村民 gossip 的读写只能在该村民自己的区域线程上做；
   右键补写必须用 `SchedulerUtil.isOwnedByCurrentRegion(villager)` 判断能否同步写 ——
   交易界面紧跟右键事件打开，派发到下一 tick 会让玩家"第一眼看到原价"。
   另外 `setConversionPlayer` 必须在 `setConversionTime` 之后调用（否则无效）。

## 共享规则（适用于所有 Yinwu 插件）

### 调度规范（Folia）
- ✅ 用 `plugin.scheduler()`（即 `SchedulerUtil`）：`global` / `atEntity` / `atEntityLater` / `atRegion` / `atChunk` / `async`
- ✅ 也可以直接用 `RegionScheduler` / `GlobalRegionScheduler` / `EntityScheduler`
- ❌ 禁止 `Bukkit.getScheduler()`、`runTask`、`runTaskAsynchronously`
- ❌ 初始延迟禁止为 `0L`（必须 ≥ `1L`）

### 生命周期
- 主类 `extends net.yinwu.lib.plugin.YinwuPlugin`，只实现 `enable()` / `disable()` / `name()`
- 基类已负责：`saveDefaultConfig` → `reloadConfig` → Folia 检测 → `SchedulerUtil.init` → 禁用时取消全局任务 + 解注册监听器

### 配置
- 继承 `net.yinwu.lib.config.BaseConfigManager`，在 `reload()` 里对每个键调用 `cache(path, value)`
- ⚠️ **存/取类型必须一致**：库的 `BaseConfigManager.get()` 是 `(T)` 无检查强转，
  `cache("k", raw().getInt(...))` 之后用 `getLong("k")` 读会直接 `ClassCastException` 崩服
  （YinwuLlamaGuard 1.0.0 就是这么炸的）。要按 long 读就按 long 存。
- 数值一律做兜底（`Math.max(1, …)`）

### 代码风格
- 注释极简，无废话
- 仅使用 Paper / Folia API，禁止 NMS
