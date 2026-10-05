package org.yinwu.villagerdiscount.data;

import net.yinwu.lib.scheduler.SchedulerUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yinwu.villagerdiscount.YinwuVillagerDiscountPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每只村民的"共享折扣"记录（data.yml）。
 *
 * <pre>
 * villagers:
 *   &lt;villager-uuid&gt;:
 *     shared:   40    # 权威共享值（由治愈事件用增量法维护）
 *     applied:  40    # 上次写进该村民 gossip 的值 —— 增量法用它扣掉"插件自己写的部分"
 *     cures:    2     # 治愈次数（仅供 /yvd info 展示）
 *     last-seen: 1790657604884
 * </pre>
 *
 * 内存用 ConcurrentHashMap（多区域线程访问）；落盘走异步调度器。
 */
public class SharedDiscountStore {

    public static final class Entry {
        /** 权威共享值（MAJOR_POSITIVE 原始值，上限 20）。 */
        public int shared;
        /** 上一次写进村民 gossip 的 MAJOR_POSITIVE 值（增量法的基准）。 */
        public int applied;
        /** 共享的 MINOR_POSITIVE 原始值（原版治愈会额外给 +25，上限 25）。 */
        public int minor;
        /** 累计治愈次数（信息用）。 */
        public int cures;
        public long lastSeen = System.currentTimeMillis();
    }

    private final YinwuVillagerDiscountPlugin plugin;
    private final File file;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private Object saveTask;

    public SharedDiscountStore(YinwuVillagerDiscountPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public Entry entry(UUID villagerId) {
        return entries.computeIfAbsent(villagerId, k -> new Entry());
    }

    public Entry peek(UUID villagerId) {
        return entries.get(villagerId);
    }

    /** 返回已知的共享值；没有记录时返回 null。 */
    public Integer sharedValue(UUID villagerId) {
        Entry entry = entries.get(villagerId);
        return entry == null ? null : entry.shared;
    }

    public int size() {
        return entries.size();
    }

    public int totalShared() {
        int sum = 0;
        for (Entry entry : entries.values()) {
            sum += entry.shared;
        }
        return sum;
    }

    public void markDirty() {
        dirty.set(true);
    }

    // ---- 读写 ----

    public void load() {
        entries.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yml.getConfigurationSection("villagers");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                Entry entry = new Entry();
                entry.shared = section.getInt(key + ".shared", 0);
                entry.applied = section.getInt(key + ".applied", entry.shared);
                entry.minor = section.getInt(key + ".minor", 0);
                entry.cures = section.getInt(key + ".cures", 0);
                // 兼容 1.0.0 的数据：那时只共享 MAJOR_POSITIVE。原版治愈必然同时给 MINOR_POSITIVE +25，
                // 所以有 shared 却缺 minor 的记录直接补上，老村民不必重新治愈一遍。
                if (entry.shared > 0 && entry.minor <= 0) {
                    entry.minor = 25;
                }
                entry.lastSeen = section.getLong(key + ".last-seen", System.currentTimeMillis());
                entries.put(id, entry);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("data.yml 里有非法的村民 UUID，已忽略: " + key);
            }
        }
        plugin.getLogger().info("已载入 " + entries.size() + " 条村民折扣记录");
    }

    public void saveNow() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录，data.yml 未保存");
            return;
        }
        try {
            YamlConfiguration yml = new YamlConfiguration();
            yml.set("version", 1);
            for (Map.Entry<UUID, Entry> pair : entries.entrySet()) {
                String base = "villagers." + pair.getKey();
                Entry entry = pair.getValue();
                yml.set(base + ".shared", entry.shared);
                yml.set(base + ".applied", entry.applied);
                yml.set(base + ".minor", entry.minor);
                yml.set(base + ".cures", entry.cures);
                yml.set(base + ".last-seen", entry.lastSeen);
            }
            yml.save(file);
            dirty.set(false);
        } catch (IOException ex) {
            plugin.getLogger().warning("保存 data.yml 失败: " + ex.getMessage());
        }
    }

    public void startAutoSave(int intervalSeconds) {
        stopAutoSave();
        long period = Math.max(1L, intervalSeconds) * 20L;
        saveTask = plugin.scheduler().globalTimer(
                () -> plugin.scheduler().async(this::saveIfDirty), 1L, period);
    }

    public void stopAutoSave() {
        if (saveTask != null) {
            SchedulerUtil.cancel(saveTask);
            saveTask = null;
        }
    }

    private void saveIfDirty() {
        if (dirty.get()) {
            saveNow();
        }
    }

    /** 清理超过 days 天没再出现的记录。 */
    public int purge(int days) {
        if (days <= 0) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        int before = entries.size();
        entries.entrySet().removeIf(pair -> pair.getValue().lastSeen < cutoff);
        int removed = before - entries.size();
        if (removed > 0) {
            markDirty();
        }
        return removed;
    }
}
