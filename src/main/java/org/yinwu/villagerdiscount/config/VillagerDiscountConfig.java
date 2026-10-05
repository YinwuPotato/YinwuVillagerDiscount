package org.yinwu.villagerdiscount.config;

import net.yinwu.lib.config.BaseConfigManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 配置读取。所有键在 {@link #reload()} 里缓存一次。
 *
 * <p>注意：库的 {@code BaseConfigManager.get()} 是 (T) 强转，
 * 缓存类型必须与读取方法一致（存 Long 就只能用 getLong 读），否则 ClassCastException。
 */
public class VillagerDiscountConfig extends BaseConfigManager {

    private static final String[] MESSAGE_KEYS = {
            "prefix", "no-permission", "reloaded", "curer-notify", "info-header", "purged"
    };

    public VillagerDiscountConfig(JavaPlugin plugin) {
        super(plugin);
    }

    @Override
    public void reload() {
        super.reload();

        cache("enabled", raw().getBoolean("enabled", true));

        cache("share.max-value", raw().getInt("share.max-value", 100));
        cache("share.share-minor-positive", raw().getBoolean("share.share-minor-positive", true));
        cache("share.apply-on-interact", raw().getBoolean("share.apply-on-interact", true));
        cache("share.apply-on-trade", raw().getBoolean("share.apply-on-trade", true));
        cache("share.notify-curer", raw().getBoolean("share.notify-curer", true));
        cache("share.apply-delay-ticks", raw().getLong("share.apply-delay-ticks", 2L));

        cache("data.save-interval-seconds", raw().getInt("data.save-interval-seconds", 300));
        cache("data.purge-after-days", raw().getInt("data.purge-after-days", 30));

        for (String key : MESSAGE_KEYS) {
            cache("messages." + key, raw().getString("messages." + key, ""));
        }
    }

    public boolean enabled() {
        return getBoolean("enabled");
    }

    /** 共享值上限（有效声望口径：100 = 原版 MAJOR_POSITIVE 的 20 × 权重 5）。 */
    public int maxValue() {
        return Math.max(1, getInt("share.max-value"));
    }

    /**
     * 是否连 MINOR_POSITIVE 一起共享。
     * 原版治愈会同时写入 MAJOR_POSITIVE +20 与 MINOR_POSITIVE +25，只共享前者的话
     * 被共享者拿到的折扣会比治愈者少一截（实测 9 绿宝石 vs 4 绿宝石）。
     */
    public boolean shareMinorPositive() {
        return getBoolean("share.share-minor-positive");
    }

    public boolean applyOnInteract() {
        return getBoolean("share.apply-on-interact");
    }

    public boolean applyOnTrade() {
        return getBoolean("share.apply-on-trade");
    }

    public boolean notifyCurer() {
        return getBoolean("share.notify-curer");
    }

    /** 延迟必须 ≥ 1 tick（规范要求），且用于等原版把 gossip 写完。 */
    public long applyDelayTicks() {
        return Math.max(1L, getLong("share.apply-delay-ticks"));
    }

    public int saveIntervalSeconds() {
        return Math.max(1, getInt("data.save-interval-seconds"));
    }

    public int purgeAfterDays() {
        return Math.max(0, getInt("data.purge-after-days"));
    }

    // ---- 消息 ----

    public String message(String key) {
        String value = getString("messages." + key);
        return value == null ? "" : value;
    }

    public String format(String key, String... replacements) {
        String text = message(key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        return text.replace('&', '§');
    }

    public String prefix() {
        return message("prefix").replace('&', '§');
    }
}
