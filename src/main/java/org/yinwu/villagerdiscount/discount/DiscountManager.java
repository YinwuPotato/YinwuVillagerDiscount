package org.yinwu.villagerdiscount.discount;

import com.destroystokyo.paper.entity.villager.Reputation;
import com.destroystokyo.paper.entity.villager.ReputationType;
import net.yinwu.lib.scheduler.SchedulerUtil;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.yinwu.villagerdiscount.YinwuVillagerDiscountPlugin;
import org.yinwu.villagerdiscount.config.VillagerDiscountConfig;
import org.yinwu.villagerdiscount.data.SharedDiscountStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 共享折扣核心。
 *
 * <p><b>语义</b>（对应 mod 的 sharedVillagerDiscounts）：把这只村民身上所有玩家累计的
 * "治愈类声望"（MAJOR_POSITIVE）求和、封顶，然后写给所有玩家；其它声望类型一律不动。
 *
 * <p><b>为什么用"增量法"</b>：mod 是拦截内部查询、只改返回值，不写数据；
 * 而我们对外的接口只能读写 gossip，一旦把共享值写给所有人，下次再求和就会把
 * 插件自己写的值也累加进去（数值会爆炸式膨胀）。所以这里记录"上次写入值 applied"，
 * 用 {@code 现在读到的值 − applied} 还原出"这一次治愈真正新增的声望"，再累加到权威值上。
 *
 * <p><b>Folia</b>：所有读写都发生在该村民自己的区域线程上（调用方负责派发）。
 */
public class DiscountManager {

    /**
     * 只共享治愈类声望 —— 与原 mod 行为一致。
     * 注意：原版治愈实际会写入<b>两种</b>传言（见 {@code Villager#onReputationEventFrom}）：
     * MAJOR_POSITIVE +20（权重 5 → 有效 100）与 MINOR_POSITIVE +25（权重 1 → 有效 25）。
     * 原 mod 只共享前者，导致被共享者的折扣比治愈者少一截；本插件默认两种都共享（可关）。
     */
    private static final ReputationType SHARED_TYPE = ReputationType.MAJOR_POSITIVE;
    private static final ReputationType SHARED_TYPE_MINOR = ReputationType.MINOR_POSITIVE;

    /** MAJOR_POSITIVE 的原始值上限（服务端 GossipType.max）。 */
    private static final int MAJOR_POSITIVE_MAX_RAW = 20;
    /** MAJOR_POSITIVE 的权重（有效声望 = 原始值 × 权重）。 */
    private static final int MAJOR_POSITIVE_WEIGHT = 5;
    /** MINOR_POSITIVE 的原始值上限。 */
    private static final int MINOR_POSITIVE_MAX_RAW = 25;

    private final YinwuVillagerDiscountPlugin plugin;
    private final VillagerDiscountConfig config;
    private final SharedDiscountStore store;

    private volatile int curedTotal;
    private volatile int appliedTotal;
    private volatile int adoptedTotal;

    public DiscountManager(YinwuVillagerDiscountPlugin plugin, VillagerDiscountConfig config,
                           SharedDiscountStore store) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
    }

    // ---- 治愈：延迟到原版写完 gossip 之后再处理 ----

    public void onCured(Villager villager, UUID curerId) {
        long delay = config.applyDelayTicks();
        plugin.scheduler().atEntityLater(villager, () -> handleCure(villager, curerId), delay);
    }

    private void handleCure(Villager villager, UUID curerId) {
        if (villager.isDead() || !villager.isValid()) {
            return;
        }
        UUID villagerId = villager.getUniqueId();
        SharedDiscountStore.Entry entry = store.entry(villagerId);

        // 原始值上限：配置里的 max-value 是"有效声望"口径（100 = 原版最大），换算成原始值要除以权重
        int rawCap = Math.max(1, Math.min(config.maxValue() / MAJOR_POSITIVE_WEIGHT, MAJOR_POSITIVE_MAX_RAW));

        int curerValue = curerId == null ? 0 : read(villager, curerId, SHARED_TYPE);
        int delta = Math.max(0, curerValue - entry.applied);
        int base = Math.max(entry.shared, entry.applied);
        int canonical = Math.min(base + delta, rawCap);

        // MINOR_POSITIVE：原版治愈同时给 +25，取"治愈者现值 / 历史值"的较大者（原版上限就是 25）
        int minor = entry.minor;
        if (config.shareMinorPositive()) {
            int curerMinor = curerId == null ? 0 : read(villager, curerId, SHARED_TYPE_MINOR);
            minor = Math.max(minor, Math.min(curerMinor, MINOR_POSITIVE_MAX_RAW));
        }

        if (canonical <= 0 && minor <= 0) {
            plugin.debug("村民 " + villagerId + " 治愈后没有可共享的声望，跳过");
            return;
        }

        int changed = applyTo(villager, onlinePlayerIds(), canonical, minor);
        entry.shared = canonical;
        entry.applied = canonical;
        entry.minor = minor;
        entry.cures++;
        entry.lastSeen = System.currentTimeMillis();
        store.markDirty();

        curedTotal++;
        appliedTotal += changed;

        plugin.debug("村民 " + villagerId + " 共享值 → MAJOR_POSITIVE=" + canonical
                + "（治愈增量 " + delta + "，有效 " + (canonical * MAJOR_POSITIVE_WEIGHT) + "）"
                + " / MINOR_POSITIVE=" + minor
                + "，写入 " + changed + " 名玩家，累计治愈 " + entry.cures + " 次");

        if (config.notifyCurer() && curerId != null) {
            Player curer = plugin.getServer().getPlayer(curerId);
            if (curer != null) {
                String message = config.prefix() + config.message("curer-notify").replace('&', '§');
                plugin.scheduler().atEntity(curer, () -> {
                    if (curer.isOnline()) {
                        curer.sendMessage(message);
                    }
                });
            }
        }
    }

    // ---- 补写：让后来/没在场的玩家也拿到折扣 ----

    /** 玩家右键村民或开始交易时调用。 */
    public void applyForPlayer(Villager villager, Player player) {
        UUID playerId = player.getUniqueId();
        if (villager.isDead() || !villager.isValid()) {
            return; // 先挡掉已失效实体，避免区域归属查询踩空
        }
        // 时序关键：交易界面是紧跟着右键事件打开的，如果把写入派发到下一 tick，
        // 可能出现"第一次打开看到原价、关掉再开才是折扣价"。
        // 右键村民时事件通常就跑在该村民自己的区域线程上 —— 那就直接同步写，赶在界面之前。
        // 若玩家与村民正好分处区域边界两侧（事件跑在别的区域线程上），自动退回派发方式，同样安全。
        if (SchedulerUtil.isOwnedByCurrentRegion(villager)) {
            applyNow(villager, playerId);
        } else {
            plugin.scheduler().atEntity(villager, () -> applyNow(villager, playerId));
        }
    }

    /** 真正执行补写；必须在村民自己的区域线程上调用。 */
    private void applyNow(Villager villager, UUID playerId) {
        if (villager.isDead() || !villager.isValid()) {
            return;
        }
        SharedDiscountStore.Entry entry = adoptIfNeeded(villager);
        if (entry == null) {
            return;
        }
        int targetMajor = entry.shared;
        int targetMinor = config.shareMinorPositive() ? entry.minor : 0;
        if (targetMajor <= 0 && targetMinor <= 0) {
            return;
        }
        int curMajor = read(villager, playerId, SHARED_TYPE);
        int curMinor = targetMinor > 0 ? read(villager, playerId, SHARED_TYPE_MINOR) : targetMinor;
        if (curMajor >= targetMajor && curMinor >= targetMinor) {
            return; // 已经达标，不动它
        }
        int changed = applyTo(villager, List.of(playerId), targetMajor, targetMinor);
        if (changed > 0) {
            appliedTotal += changed;
            plugin.debug("为玩家 " + playerId + " 补写村民 " + villager.getUniqueId()
                    + " 的共享折扣 → MAJOR_POSITIVE=" + targetMajor + " / MINOR_POSITIVE=" + targetMinor);
        }
    }

    /**
     * 接管"插件安装前就已经被治愈"的存量村民。
     *
     * <p>插件只认识自己见过的治愈事件，所以装插件之前治愈的村民本来不会被共享。
     * 这里在玩家右键时就地读该村民的 gossip、现算共享值并建记录，让存量村民立刻可用
     * （也顺带覆盖"插件曾被卸载 / data.yml 被删"的情况）。
     */
    private SharedDiscountStore.Entry adoptIfNeeded(Villager villager) {
        UUID villagerId = villager.getUniqueId();
        SharedDiscountStore.Entry existing = store.peek(villagerId);
        if (existing != null) {
            return existing;
        }
        int major = 0;
        int minor = 0;
        for (Reputation reputation : villager.getReputations().values()) {
            major = Math.max(major, readFrom(reputation, SHARED_TYPE));
            minor = Math.max(minor, readFrom(reputation, SHARED_TYPE_MINOR));
        }
        if (major <= 0 && minor <= 0) {
            return null; // 确实没被治愈过，不建记录
        }
        int rawCap = Math.max(1, Math.min(config.maxValue() / MAJOR_POSITIVE_WEIGHT, MAJOR_POSITIVE_MAX_RAW));
        SharedDiscountStore.Entry entry = store.entry(villagerId);
        entry.shared = Math.min(major, rawCap);
        entry.applied = entry.shared;
        entry.minor = config.shareMinorPositive() ? Math.min(minor, MINOR_POSITIVE_MAX_RAW) : 0;
        entry.cures = 0; // 不是插件记到的治愈
        entry.lastSeen = System.currentTimeMillis();
        store.markDirty();
        adoptedTotal++;
        plugin.debug("接管存量村民 " + villagerId + "：MAJOR_POSITIVE=" + entry.shared
                + " / MINOR_POSITIVE=" + entry.minor + "（插件安装前治愈的，就地现算）");
        return entry;
    }

    // ---- 读写 gossip ----

    /**
     * 把共享值写入指定玩家的声望；其它传言类型保持原样，且只升不降。
     *
     * @return 实际改动的玩家数
     */
    private int applyTo(Villager villager, Collection<UUID> playerIds, int majorValue, int minorValue) {
        Map<UUID, Reputation> reputations = new HashMap<>(villager.getReputations());
        int changed = 0;
        for (UUID playerId : playerIds) {
            Reputation reputation = reputations.get(playerId);
            if (reputation == null) {
                reputation = new Reputation();
                reputations.put(playerId, reputation);
            }
            boolean touched = false;
            if (majorValue > 0) {
                touched |= raise(reputation, SHARED_TYPE, majorValue, MAJOR_POSITIVE_MAX_RAW);
            }
            if (minorValue > 0) {
                touched |= raise(reputation, SHARED_TYPE_MINOR, minorValue, MINOR_POSITIVE_MAX_RAW);
            }
            if (touched) {
                changed++;
            }
        }
        if (changed > 0) {
            villager.setReputations(reputations);
        }
        return changed;
    }

    /** 只升不降地写一个传言类型，并按原版上限截断（插件写的是原始值，必须自己守住上限）。 */
    private boolean raise(Reputation reputation, ReputationType type, int value, int max) {
        int target = Math.min(value, max);
        int current = reputation.hasReputationSet(type) ? reputation.getReputation(type) : 0;
        if (current >= target) {
            return false;
        }
        reputation.setReputation(type, target);
        return true;
    }

    private int read(Villager villager, UUID playerId, ReputationType type) {
        return readFrom(villager.getReputations().get(playerId), type);
    }

    private int readFrom(Reputation reputation, ReputationType type) {
        if (reputation == null || !reputation.hasReputationSet(type)) {
            return 0;
        }
        return reputation.getReputation(type);
    }

    private Collection<UUID> onlinePlayerIds() {
        List<UUID> ids = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            ids.add(player.getUniqueId());
        }
        return ids;
    }

    // ---- 供 /yvd info ----

    public int curedTotal() {
        return curedTotal;
    }

    public int appliedTotal() {
        return appliedTotal;
    }

    /** 接管的存量村民数量（插件安装前就治愈好的）。 */
    public int adoptedTotal() {
        return adoptedTotal;
    }
}
