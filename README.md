# YinwuVillagerDiscount — 共享村民折扣

**最新版本：v1.0.0** | [下载 Release](https://github.com/YinwuPotato/YinwuVillagerDiscount/releases/tag/v1.0.0)

**任何人治愈一只僵尸村民后，那份折扣对全服玩家生效** —— 不需要每个人都去治愈一遍。

> 移植自 Fabric mod [totos-carpet-tweaks](https://github.com/totorewa/totos-carpet-tweaks) 的
> `sharedVillagerDiscounts` 规则（LGPL-3.0）。原 mod 只能跑在 Fabric 服务端，
> 本插件用 Paper/Folia API 在 Canvas 上实现同样的效果。
>
> ⚡ 完全兼容 Folia 区域线程。

---

## 1. 它到底做了什么

原 mod 是**拦截内部查询**：当游戏问"某玩家对这只村民的声望是多少"时，把其中
"治愈类声望"（`MAJOR_POSITIVE`）替换成**所有玩家该项声望的总和**（封顶 100）。

Canvas/Paper 的插件拦不到这个内部查询，所以本插件改成**写数据**：把共享值直接写进
村民的 gossip 记录里，让原版自己算出折扣。效果等价：

| | 行为 |
|---|---|
| **共享什么** | 治愈类声望 `MAJOR_POSITIVE` **以及** `MINOR_POSITIVE` —— 因为原版治愈本身就会同时写这两种（见下） |
| **不共享什么** | 交易累积（`TRADING`）、负面传言（`MAJOR_NEGATIVE`/`MINOR_NEGATIVE`）一律不动 |
| **共享给谁** | 全服所有玩家（含之后进服的），**每只村民独立计算** |
| **上限** | 原始值按原版上限截断：`MAJOR_POSITIVE ≤ 20`（有效 100）、`MINOR_POSITIVE ≤ 25`（有效 25） |
| **需要玩家做什么** | 什么都不用做 —— 治愈那一刻自动生效；漏掉的玩家下次右键村民时自动补上 |

### ★ 为什么必须同时共享 MINOR_POSITIVE（实测踩过的坑）

原版治愈在服务端是这么写的（`Villager#onReputationEventFrom`，反编译自 Canvas 26.3）：

```java
if (type == ReputationEventType.ZOMBIE_VILLAGER_CURED) {
    gossips.add(player.getUUID(), GossipType.MAJOR_POSITIVE, 20);   // 20 × 权重5 = 有效 100
    gossips.add(player.getUUID(), GossipType.MINOR_POSITIVE, 25);   // 25 × 权重1 = 有效  25
}
```

**一次治愈写入两种传言，合计有效声望 125。** 原 mod 只共享 `MAJOR_POSITIVE`（它的文档写得很明确），
于是被共享者只有 100 —— 价格并不一致。实测（原价 29、`priceMultiplier = 0.2`）：

| | MAJOR_POSITIVE | MINOR_POSITIVE | 有效声望 | 折扣 | 价格 |
|---|---|---|---|---|---|
| 治愈者 | 20 → 100 | 25 → 25 | **125** | 25 | **4** |
| 只共享 MAJOR_POSITIVE（= 原 mod） | 20 → 100 | 0 | **100** | 20 | **9** ✗ |
| 两种都共享（本插件默认） | 20 → 100 | 25 → 25 | **125** | 25 | **4** ✓ |

价格公式（同样来自反编译）：

```java
// Villager#updateSpecialPrices
int rep = getPlayerReputation(player);                     // = Σ(原始值 × 权重)，所有传言类型都算
offer.addToSpecialPriceDiff(-floor(rep × priceMultiplier));
// MerchantOffer#getModifiedCostCount
price = clamp(base + max(0, floor(base × demand × priceMultiplier)) + specialPriceDiff, 1, 64);
```

`share.share-minor-positive: false` 可以退回原 mod 的严格行为。

### 为什么用"增量法"（关键实现细节）

mod 不写数据，所以它的"求和"永远只统计真实的治愈声望。
而本插件必须写数据，于是会遇到一个问题：

> 一旦把共享值 20 写给了 10 个玩家，下次再"求和"就会得到 20×10 = 200，
> 封顶成 100 —— 数值被插件自己写的数据污染，直接跳到满折扣。

解决办法：记录**上次写入值 `applied`**，用 `现在读到的值 − applied` 还原出
"这一次治愈真正新增的声望"，再累加到权威共享值上：

```
canonical = min( max(shared, applied) + max(0, curerNow - applied), 上限 )
```

举例（治愈一次原版给 `MAJOR_POSITIVE +20`）：

| 事件 | 村民数据 | 计算 | 共享值 |
|---|---|---|---|
| 玩家 A 首次治愈 | A=20 | delta = 20−0 = 20 | **20** |
| 插件写入所有人 | A=20, B=20, C=20 | — | 20 |
| 玩家 B 又治愈一次 | B=40（原版累加） | **delta = min(40, rawCap=20) − applied(20) = 0** | **20（不再增长）** |

> ⚠️ **共享值在一次治愈后就已经封顶**，重复治愈不会让它继续变大。
> 原因：原版 `MAJOR_POSITIVE` 的**原始值上限就是 20**（代码常量 `MAJOR_POSITIVE_MAX_RAW = 20`），
> 而配置里的 `share.max-value: 100` 是**有效声望**口径，除以权重 5 换算成原始值上限 `100/5 = 20`
> （`DiscountManager.java:80`：`rawCap = min(maxValue / 5, 20)`）。
> 治愈者自己能到 raw 40，但插件只会写入到 raw 20 —— 这是刻意的**防超模**设计，不是 bug。
>
> 另一个被共享的传言 `MINOR_POSITIVE` 原始值上限是 **25**（`MINOR_POSITIVE_MAX_RAW = 25`），
> 由 `share.share-minor-positive: true` 控制是否一起共享。

---

## 2. 命令与权限

| 命令 | 说明 | 权限 | 默认 |
|---|---|---|---|
| `/yinwuvillagerdiscount help`（别名 `/yvd`） | 查看帮助 | — | — |
| `/yvd info` | 运行状态：共享上限、已记录村民数、累计治愈/写入次数 | `yinwu.villagerdiscount.admin` | OP |
| `/yvd reload` | 重载配置与数据 | `yinwu.villagerdiscount.admin` | OP |
| `/yvd purge [天数]` | 清理过期村民记录（默认取配置值） | `yinwu.villagerdiscount.admin` | OP |
| `/yvd curetest [tick]` | **仅测试用**：看着僵尸村民执行，立刻开始治愈（默认 40 tick） | `yinwu.villagerdiscount.admin` | OP |
| `/yvd dump` | **诊断用**：看着村民执行，打印完整 gossip 表（每种传言的原始值 + 权重折算后的有效声望）与全部报价的价格参数；**同时写入服务端日志** | `yinwu.villagerdiscount.admin` | OP |

> 折扣是**全自动**的，普通玩家不需要任何命令。`/yvd help`（别名主命令 `/yinwuvillagerdiscount`）
> 对所有人开放，只输出帮助文本；其余子命令都要求 `yinwu.villagerdiscount.admin`（默认 OP）。

---

## 3. 配置（`plugins/YinwuVillagerDiscount/config.yml`）

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关 |
| `debug` | `false` | 调试日志（会打印每次治愈的增量与写入人数） |
| `share.max-value` | `100` | 共享值上限，**有效声望口径**（代码换算成原始值上限 100/5 = 20） |
| `share.share-minor-positive` | `true` | **是否连 `MINOR_POSITIVE` 一起共享**；`false` = 退回原 mod 的严格行为（价格会有差异） |
| `share.apply-on-interact` | `true` | 右键村民（交易界面前）补写，让后来者拿到折扣 |
| `share.apply-on-trade` | `true` | 交易时再兜底补写一次 |
| `share.notify-curer` | `true` | 治愈者收到一条"折扣已共享"提示 |
| `share.apply-delay-ticks` | `2` | 治愈后延迟多少 tick 再读写（等原版把 gossip 写完） |
| `data.save-interval-seconds` | `300` | `data.yml` 定时落盘（有变更才写） |
| `data.purge-after-days` | `30` | 超过该天数没再出现的村民记录会被清理（`0` = 不清理） |
| `messages.*` | 中文 | 聊天提示文本（6 个键：治愈者提示、重载、清理结果等），支持 `&` 颜色代码 |

数据文件 `plugins/YinwuVillagerDiscount/data.yml`：

```yaml
version: 1
villagers:
  <村民UUID>:
    shared: 20      # 权威共享值（MAJOR_POSITIVE 原始值）
    applied: 20     # 上次写进村民 gossip 的值（增量法基准）
    minor: 25       # 共享的 MINOR_POSITIVE 原始值
    cures: 1        # 治愈次数
    last-seen: 1790657604884
```

---

## 4. 工作原理与线程模型（Folia）

```
EntityTransformEvent(CURED)              ← 僵尸村民转化完成的瞬间
  └─ 取 ZombieVillager#getConversionPlayer()  ← 直接拿到治愈者（比 mod 的 mixin 还省事）
     └─ atEntityLater(新村民, …, apply-delay-ticks)   ← 村民自己的区域线程
        ├─ 读该村民全部玩家的 MAJOR_POSITIVE
        ├─ 增量法算出新的共享值（封顶）
        ├─ 写给所有在线玩家（只改 MAJOR_POSITIVE，其它声望保留）
        └─ 记入 data.yml（异步落盘）

PlayerInteractEntityEvent / PlayerTradeEvent
  └─ atEntity(村民, …) → 若该村民已有共享值且该玩家低于它，补写
```

- 村民的 gossip 读写**只发生在该村民自己的区域线程**上，不碰其它实体 → 零线程违规
- 玩家 UUID 不是实体状态，跨线程读取安全
- 写 gossip 用的是 `Villager#setReputations(Map)`，整表替换
- **默认共享两种传言**：`MAJOR_POSITIVE`（权重 5，原始值封顶 20）与 `MINOR_POSITIVE`（权重 1，原始值封顶 25）。
  原版治愈一次会同时写这两种（`Villager#onReputationEventFrom` 反编译确认），只共享前者会让被共享者的价格与治愈者**不一致**
  （实测 9 vs 4 绿宝石）。可用 `share.share-minor-positive: false` 退回原 mod 的严格行为
- **`TRADING` 与负面传言永不共享**（会把差评扩散给全服）
- 原版在交易界面开着时每 tick 会重算价格，所以"右键时补写"能立刻反映到价格上

---

## 5. 安装

1. 把 `yinwu-villagerdiscount-1.0.0.jar` 放进 `plugins/` 并重启
   （**jar 里已经 shade 了 YinwuPluginLib，不需要单独安装它**）
2. 重启服务器
3. 启动日志应出现：
   `[YinwuVillagerDiscount] 已启用：共享上限=100，右键补写=true，交易兜底=true，延迟=2 tick，已记录村民=0（Folia 区域线程）`

---

## 6. 验收（怎么确认真的生效）

原版治愈要等 3~5 分钟，用插件自带的测试指令可以秒级验证：

```
1. /summon minecraft:zombie_villager ~ ~ ~        # 放一只僵尸村民
2. /yvd curetest 40                               # 看着它执行：40 tick 后完成治愈（等价于喂金苹果+虚弱后的等待）
3. 等 2 秒，控制台/聊天应出现治愈者提示"折扣已共享给全服玩家"
4. /yvd info                                      # 已记录村民 ≥1，累计治愈次数 ≥1
5. 换一个没参与治愈的玩家（或另一个账号）右键同一只村民打开交易界面
6. 看价格：应该已经是折扣价（与治愈者看到的一致）
```

**判定要点**：

| 现象 | 说明 |
|---|---|
| 玩家 B 没治愈过也能看到折扣价 | ✅ 核心功能生效 |
| `data.yml` 里该村民 `shared` 有值、`cures` 增长 | ✅ 记录正常 |
| 首次治愈后 `shared` = **20**（= `rawCap`），再次治愈**不再变大** | ✅ 正确 —— 原版 `MAJOR_POSITIVE` 原始值上限就是 20，这里是防超模 |
| 村民的交易累积声望仍在（没被覆盖） | ✅ 只动 `MAJOR_POSITIVE` / `MINOR_POSITIVE` 两项 |

---

## 7. 与原 mod 的差异 / 已知限制

1. **mod 拦截查询、插件写数据**：结果等价，但插件会真实修改村民的 gossip 记录。
   如果想撤销，删掉 `data.yml` 里对应条目并不会回滚已写入的声望（那是原版数据）。
2. **增量法的边界情况**：如果治愈者"从未被插件写入过"（例如上次写入时他不在线、
   也还没右键过村民），这一次治愈的增量可能被算成 0，共享值少涨一次。
   影响很小（下一次治愈会补上来），但确实与原 mod 的严格求和有细微差别。
3. **负面传言永不共享**：`MAJOR_NEGATIVE`/`MINOR_NEGATIVE`/`TRADING` 一律不动 ——
   共享负面声望会把差评扩散给全服，不是这个功能的目的。
4. **村民死亡/被清除**：记录会留在 `data.yml` 里直到 `purge`；UUID 不会复用，无副作用。
5. **`curetest` 绕过原版前置条件**（不需要虚弱药水 + 金苹果），仅供测试，别在生产环境依赖。

---

## 8. 构建

本模块在 **Van 树**（Canvas 插件）里，遵循 Van 约定：**不用 Maven，javac + jar 一把梭**。

```bat
cd Van\YinwuVillagerDiscount-村民折扣共享
build-javac.bat
```

产物：`Van\YinwuVillagerDiscount-村民折扣共享\yinwu-villagerdiscount-1.0.0.jar`（模块根目录）

脚本做的事：
1. 找 API jar —— 优先 `Van\libraries\...\canvas-api-*.jar`，找不到就退回 `..\..\.buildcache\paper-api.jar`
   （本插件只用 Paper API，Canvas 完整实现它，所以两者都能编译）
2. `javac --release 21` 编译 `src\main\java\**`
3. **把 `Sur\YinwuPluginLib-共享库` 的类 shade 进 jar** —— 因为插件 `extends YinwuPlugin`，
   而 Van 树里其它插件都是独立实现。这一步等价于 Sur 那边的 `maven-shade-plugin`，
   少了它运行时会 `NoClassDefFoundError: net/yinwu/lib/plugin/YinwuPlugin`
4. 把 `src\main\resources`（plugin.yml / config.yml）一起打进包

> **构建前置**：第 3 步需要 `Sur\YinwuPluginLib-共享库\target\YinwuPluginLib-1.0.3.jar`。
> 若不存在，先构建共享库（`mvn clean install` 或 `自动化\compile-check.bat YinwuPluginLib`）。

**辅助校验**（不打包，只编译，Sur/Van 都能用）：

```bat
自动化\compile-check.bat YinwuVillagerDiscount
```

`pom.xml` 仍保留（声明依赖用，父 POM 已随仓库提供在 `parent/pom.xml`），
但本模块的正式构建路径是 `build-javac.bat`。

**依赖**：`YinwuPluginLib`（**已打进 jar**，服务器上不需要单独安装）、Paper API 1.21+。**无任何软依赖。**

---

## License | 许可证

LGPL-3.0 —— 见 [LICENSE](LICENSE)。移植自 [totorewa/totos-carpet-tweaks](https://github.com/totorewa/totos-carpet-tweaks) 的 `sharedVillagerDiscounts` 规则（同为 LGPL-3.0）。

---

## 9. 链接

- 原 mod：[totorewa/totos-carpet-tweaks](https://github.com/totorewa/totos-carpet-tweaks)（LGPL-3.0）
- 组织：[github.com/YinwuPotato](https://github.com/YinwuPotato)
- 前置：[YinwuPluginLib](https://github.com/YinwuPotato/YinwuPluginLib)
- 作者：Qumingjam
