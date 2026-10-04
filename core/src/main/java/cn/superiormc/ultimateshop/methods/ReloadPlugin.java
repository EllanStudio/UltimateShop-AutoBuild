package cn.superiormc.ultimateshop.methods;

import cn.superiormc.ultimateshop.UltimateShop;
import cn.superiormc.ultimateshop.database.DatabaseExecutor;
import cn.superiormc.ultimateshop.listeners.SellStickListener;
import cn.superiormc.ultimateshop.utils.TransactionLogger;
import cn.superiormc.ultimateshop.managers.*;
import cn.superiormc.ultimateshop.objects.menus.ObjectMenu;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ReloadPlugin {

    public static void reload(CommandSender sender) {
        LanguageManager.languageManager.sendStringText(sender, "plugin.reloading");
        UltimateShop.instance.reloadConfig();
        DynamicCommandManager.unregisterAll();
        TaskManager.taskManager.cancelTask();
        MenuStatusManager.menuStatusManager.onPluginReload();
        TransactionLogger.flush();
        DatabaseExecutor.quiesce();
        try {
            DatabaseExecutor.await();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!UltimateShop.freeVersion) {
                    SellStickListener.playerList.remove(player);
                }
                CacheManager.cacheManager.saveObjectCacheOnDisable(player, false);
            }
            if (CacheManager.cacheManager.serverCache != null) {
                CacheManager.cacheManager.serverCache.shutCacheOnDisable(false);
            }
            CacheManager.cacheManager.shutdown();
            ObjectMenu.commonMenus.clear();
            ObjectMenu.notCommonMenuNames.clear();
            new ConfigManager();
            new ItemManager();
            new LanguageManager();
        } finally {
            DatabaseExecutor.resume();
        }
        new CacheManager();
        new TaskManager();
        LanguageManager.languageManager.sendStringText(sender, "plugin.reloaded");
    }
}
