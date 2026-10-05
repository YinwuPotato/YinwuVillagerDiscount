package org.yinwu.villagerdiscount;

import net.yinwu.lib.plugin.YinwuPlugin;
import org.yinwu.villagerdiscount.command.DiscountCommand;
import org.yinwu.villagerdiscount.config.VillagerDiscountConfig;
import org.yinwu.villagerdiscount.data.SharedDiscountStore;
import org.yinwu.villagerdiscount.discount.DiscountManager;
import org.yinwu.villagerdiscount.listener.CureListener;

/**
 * YinwuVillagerDiscount —— 共享村民折扣。
 *
 * <p>移植自 Fabric mod totos-carpet-tweaks 的 sharedVillagerDiscounts 规则：
 * 任何人治愈僵尸村民后，那份"治愈类声望"（MAJOR_POSITIVE）对所有玩家生效，
 * 不必每个人都去治愈一遍；其它声望（交易累积、负面传言）不共享。
 *
 * <p>Folia：所有操作都在目标村民自己的区域线程上执行，绝不跨区域。
 */
public class YinwuVillagerDiscountPlugin extends YinwuPlugin {

    private VillagerDiscountConfig config;
    private SharedDiscountStore store;
    private DiscountManager discount;

    @Override
    public String name() {
        return "YinwuVillagerDiscount";
    }

    @Override
    public void enable() {
        config = new VillagerDiscountConfig(this);
        config.reload();

        if (!config.enabled()) {
            getLogger().info(name() + " 已在 config.yml 中禁用（enabled: false）");
            return;
        }

        store = new SharedDiscountStore(this);
        store.load();

        discount = new DiscountManager(this, config, store);
        getServer().getPluginManager().registerEvents(new CureListener(this, config, discount), this);

        DiscountCommand command = new DiscountCommand(this, config, store, discount);
        if (getCommand("yinwuvillagerdiscount") != null) {
            getCommand("yinwuvillagerdiscount").setExecutor(command);
            getCommand("yinwuvillagerdiscount").setTabCompleter(command);
        } else {
            getLogger().warning("plugin.yml 里没有 yinwuvillagerdiscount 命令，命令功能不可用");
        }

        store.startAutoSave(config.saveIntervalSeconds());

        getLogger().info(name() + " 已启用"
                + "：共享上限=" + config.maxValue()
                + "，右键补写=" + config.applyOnInteract()
                + "，交易兜底=" + config.applyOnTrade()
                + "，延迟=" + config.applyDelayTicks() + " tick"
                + "，已记录村民=" + store.size()
                + (isFolia() ? "（Folia 区域线程）" : "（Paper 主线程）"));
    }

    @Override
    public void disable() {
        if (store != null) {
            store.stopAutoSave();
            store.saveNow();
        }
    }

    /** /yvd reload 调用。 */
    public void reloadAll() {
        config.reload();
        if (store != null) {
            store.stopAutoSave();
            store.load();
            store.startAutoSave(config.saveIntervalSeconds());
        }
    }

    public VillagerDiscountConfig config() {
        return config;
    }

    public SharedDiscountStore store() {
        return store;
    }

    public DiscountManager discount() {
        return discount;
    }
}
