package org.yinwu.villagerdiscount.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.yinwu.villagerdiscount.YinwuVillagerDiscountPlugin;
import org.yinwu.villagerdiscount.config.VillagerDiscountConfig;
import org.yinwu.villagerdiscount.data.SharedDiscountStore;
import org.yinwu.villagerdiscount.discount.DiscountManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 命令：/yinwuvillagerdiscount &lt;help|info|reload|purge&gt;（别名 /yvd） */
public class DiscountCommand implements CommandExecutor, TabCompleter {

    private final YinwuVillagerDiscountPlugin plugin;
    private final VillagerDiscountConfig config;
    private final SharedDiscountStore store;
    private final DiscountManager discount;

    public DiscountCommand(YinwuVillagerDiscountPlugin plugin, VillagerDiscountConfig config,
                           SharedDiscountStore store, DiscountManager discount) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.discount = discount;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "info" -> info(sender);
            case "reload" -> reload(sender);
            case "purge" -> purge(sender, args);
            case "curetest" -> cureTest(sender, args);
            case "dump" -> dump(sender);
            default -> sender.sendMessage(config.prefix() + "§c未知子命令，用 §e/" + label + " help §c查看");
        }
        return true;
    }

    /**
     * 看着一只村民执行：打印它的完整 gossip 表（每个玩家每种传言的原始值 + 权重折算后的有效声望）
     * 与全部报价的当前价格参数。同时写进服务端日志，便于远程排查。
     */
    private void dump(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage(config.prefix() + "§c该子命令只能由玩家执行");
            return;
        }
        if (!(player.getTargetEntity(10) instanceof org.bukkit.entity.Villager villager)) {
            sender.sendMessage(config.prefix() + "§c请先看着一只村民（10 格内）再执行");
            return;
        }
        plugin.scheduler().atEntity(villager, () -> {
            List<String> lines = collectDump(villager);
            for (String line : lines) {
                plugin.getLogger().info("[DUMP] " + line);
            }
            // 玩家可能在别的区域线程上，回他自己的线程再发消息
            plugin.scheduler().atEntity(player, () -> {
                for (String line : lines) {
                    player.sendMessage("§7" + line);
                }
            });
        });
    }

    /** 必须在村民自己的区域线程上调用。 */
    private List<String> collectDump(org.bukkit.entity.Villager villager) {
        List<String> lines = new ArrayList<>();
        lines.add("=== YinwuVillagerDiscount dump ===");
        lines.add("villager=" + villager.getUniqueId()
                + " world=" + villager.getWorld().getName()
                + " loc=" + String.format(Locale.ROOT, "%.1f,%.1f,%.1f",
                        villager.getLocation().getX(), villager.getLocation().getY(), villager.getLocation().getZ()));

        org.yinwu.villagerdiscount.data.SharedDiscountStore.Entry entry = store.peek(villager.getUniqueId());
        lines.add("plugin-record: " + (entry == null
                ? "(无记录 —— 插件认为这只村民没被治愈过)"
                : "shared=" + entry.shared + " applied=" + entry.applied + " cures=" + entry.cures));

        java.util.Map<java.util.UUID, com.destroystokyo.paper.entity.villager.Reputation> reps = villager.getReputations();
        lines.add("gossip-entries=" + reps.size());
        for (java.util.Map.Entry<java.util.UUID, com.destroystokyo.paper.entity.villager.Reputation> e : reps.entrySet()) {
            StringBuilder detail = new StringBuilder();
            int effective = 0;
            for (com.destroystokyo.paper.entity.villager.ReputationType type
                    : com.destroystokyo.paper.entity.villager.ReputationType.values()) {
                if (!e.getValue().hasReputationSet(type)) {
                    continue;
                }
                int raw = e.getValue().getReputation(type);
                int weight = weightOf(type);
                effective += raw * weight;
                detail.append(type.name().toLowerCase(Locale.ROOT)).append('=').append(raw)
                        .append("(w").append(weight).append(") ");
            }
            lines.add("  player=" + nameOf(e.getKey()) + " uuid=" + e.getKey()
                    + " effective-reputation=" + effective + "  [" + detail.toString().trim() + "]");
        }

        for (org.bukkit.inventory.MerchantRecipe recipe : villager.getRecipes()) {
            int baseCount = recipe.getIngredients().isEmpty() ? -1 : recipe.getIngredients().get(0).getAmount();
            lines.add("  offer: " + recipe.getResult().getType() + " x" + recipe.getResult().getAmount()
                    + " base=" + baseCount
                    + " adjusted=" + recipe.getAdjustedIngredient1().getAmount()
                    + " demand=" + recipe.getDemand()
                    + " priceMultiplier=" + recipe.getPriceMultiplier()
                    + " specialPrice=" + recipe.getSpecialPrice()
                    + " -> 折扣=" + (-recipe.getSpecialPrice()));
        }
        return lines;
    }

    /** 传言类型的权重（从服务端 GossipType 枚举反编译得到，用于把原始值折算成有效声望）。 */
    private int weightOf(com.destroystokyo.paper.entity.villager.ReputationType type) {
        return switch (type) {
            case MAJOR_NEGATIVE -> -5;
            case MINOR_NEGATIVE -> -1;
            case MINOR_POSITIVE -> 1;
            case MAJOR_POSITIVE -> 5;
            case TRADING -> 1;
        };
    }

    private String nameOf(java.util.UUID uuid) {
        org.bukkit.entity.Player online = plugin.getServer().getPlayer(uuid);
        return online != null ? online.getName() : "(离线)";
    }

    /**
     * 仅用于测试：看着一只僵尸村民执行，直接把它设为"正在被自己治愈"并压短转化时间，
     * 省掉原版 3~5 分钟的等待（等价于"喂金苹果 + 虚弱"之后的等待过程）。
     */
    private void cureTest(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        if (!(sender instanceof org.bukkit.entity.Player player)) {
            sender.sendMessage(config.prefix() + "§c该子命令只能由玩家执行");
            return;
        }
        if (!(player.getTargetEntity(10) instanceof org.bukkit.entity.ZombieVillager zombie)) {
            sender.sendMessage(config.prefix() + "§c请先看着一只僵尸村民（10 格内）再执行");
            return;
        }
        int ticks = 40;
        if (args.length >= 2) {
            try {
                ticks = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException ex) {
                sender.sendMessage(config.prefix() + "§c ticks 必须是数字，例如 §e/yvd curetest 40");
                return;
            }
        }
        final int finalTicks = ticks;
        plugin.scheduler().atEntity(zombie, () -> {
            if (zombie.isDead() || !zombie.isValid()) {
                return;
            }
            // 顺序很重要：setConversionPlayer 的 API 明确写着"实体当前不在转化中时无效"，
            // 所以必须先设置转化时间让它进入转化状态，再记录治愈者。
            zombie.setConversionTime(finalTicks);
            zombie.setConversionPlayer(player);
            plugin.debug("测试治愈：僵尸村民 " + zombie.getUniqueId()
                    + " 转化时间设为 " + finalTicks + " tick，治愈者 " + player.getName());
        });
        sender.sendMessage(config.prefix() + "§a已把它设为正在被你治愈（" + ticks + " tick 后完成）");
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("yinwu.villagerdiscount.admin")) {
            return true;
        }
        sender.sendMessage(config.prefix() + config.message("no-permission").replace('&', '§'));
        return false;
    }

    private void info(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return;
        }
        sender.sendMessage("§8§m                                                  ");
        sender.sendMessage("§eYinwuVillagerDiscount §7v" + plugin.getPluginMeta().getVersion());
        sender.sendMessage("§7共享上限 §f" + config.maxValue()
                + "§7，延迟 §f" + config.applyDelayTicks() + " tick");
        sender.sendMessage("§7右键补写 §f" + config.applyOnInteract()
                + "§7，交易兜底 §f" + config.applyOnTrade()
                + "§7，提示治愈者 §f" + config.notifyCurer());
        sender.sendMessage("§7已记录村民 §f" + store.size()
                + "§7，共享值合计 §f" + store.totalShared());
        sender.sendMessage("§7累计治愈次数 §f" + discount.curedTotal()
                + "§7，累计写入玩家次数 §f" + discount.appliedTotal());
        sender.sendMessage("§7接管存量村民 §f" + discount.adoptedTotal()
                + "§8（插件安装前就治愈好的，玩家右键时自动接管）");
        sender.sendMessage("§8共享的声望类型：§7MAJOR_POSITIVE（治愈类）—— 其它类型不共享");
        sender.sendMessage("§8§m                                                  ");
    }

    private void reload(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return;
        }
        plugin.scheduler().global(plugin::reloadAll);
        sender.sendMessage(config.prefix() + config.message("reloaded").replace('&', '§'));
    }

    private void purge(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        int days = config.purgeAfterDays();
        if (args.length >= 2) {
            try {
                days = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(config.prefix() + "§c天数必须是数字，例如 §e/yvd purge 30");
                return;
            }
        }
        if (days <= 0) {
            sender.sendMessage(config.prefix() + "§c天数必须大于 0");
            return;
        }
        int removed = store.purge(days);
        store.saveNow();
        sender.sendMessage(config.prefix() + config.format("purged", "%count%", String.valueOf(removed)));
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage("§8§m                                                  ");
        sender.sendMessage("§e/" + label + " help   §7查看帮助");
        if (sender.hasPermission("yinwu.villagerdiscount.admin")) {
            sender.sendMessage("§e/" + label + " info   §7运行状态");
            sender.sendMessage("§e/" + label + " reload §7重载配置与数据");
            sender.sendMessage("§e/" + label + " purge [天数] §7清理过期记录");
            sender.sendMessage("§e/" + label + " curetest [tick] §7测试用：看着僵尸村民，立刻开始治愈它");
            sender.sendMessage("§e/" + label + " dump   §7看着村民执行：打印它的完整 gossip 表与报价参数（同时写入服务端日志）");
        }
        sender.sendMessage("§7共享折扣是自动生效的，玩家无需任何操作。");
        sender.sendMessage("§8§m                                                  ");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> subs = new ArrayList<>(List.of("help"));
        if (sender.hasPermission("yinwu.villagerdiscount.admin")) {
            subs.add("info");
            subs.add("reload");
            subs.add("purge");
            subs.add("curetest");
            subs.add("dump");
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String sub : subs) {
            if (sub.startsWith(prefix)) {
                out.add(sub);
            }
        }
        return out;
    }
}
