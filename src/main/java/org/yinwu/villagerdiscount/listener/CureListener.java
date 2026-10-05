package org.yinwu.villagerdiscount.listener;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Villager;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.yinwu.villagerdiscount.YinwuVillagerDiscountPlugin;
import org.yinwu.villagerdiscount.config.VillagerDiscountConfig;
import org.yinwu.villagerdiscount.discount.DiscountManager;

/**
 * 事件入口。
 *
 * <p>治愈：{@link EntityTransformEvent}（原因 CURED）—— 僵尸村民转化完成的瞬间触发，
 * 用 {@link ZombieVillager#getConversionPlayer()} 拿到治愈者，比 mod 的 mixin 还省事。
 *
 * <p>补写：右键村民（交易界面打开前）与交易事件，用于把折扣补给没在场/后来进服的玩家。
 * 原版在交易界面开着时会每 tick 调 startTrading 重算价格，所以这两种时机写进去都能生效。
 */
public class CureListener implements Listener {

    private final YinwuVillagerDiscountPlugin plugin;
    private final VillagerDiscountConfig config;
    private final DiscountManager discount;

    public CureListener(YinwuVillagerDiscountPlugin plugin, VillagerDiscountConfig config,
                        DiscountManager discount) {
        this.plugin = plugin;
        this.config = config;
        this.discount = discount;
    }

    /** 僵尸村民被治愈（金苹果 + 虚弱 + 等待转化完成）。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (event.getTransformReason() != EntityTransformEvent.TransformReason.CURED) {
            return;
        }
        if (!(event.getEntity() instanceof ZombieVillager zombie)) {
            return;
        }
        if (!(event.getTransformedEntity() instanceof Villager villager)) {
            return;
        }
        OfflinePlayer curer = zombie.getConversionPlayer();
        discount.onCured(villager, curer == null ? null : curer.getUniqueId());
    }

    /** 右键村民：交易界面打开前补写折扣。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!config.applyOnInteract()) {
            return;
        }
        if (!(event.getRightClicked() instanceof Villager villager)) {
            return;
        }
        discount.applyForPlayer(villager, event.getPlayer());
    }

    /** 交易兜底：万一右键事件没触发，也在交易时补写。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(PlayerTradeEvent event) {
        if (!config.applyOnTrade()) {
            return;
        }
        if (!(event.getVillager() instanceof Villager villager)) {
            return;
        }
        discount.applyForPlayer(villager, event.getPlayer());
    }
}
