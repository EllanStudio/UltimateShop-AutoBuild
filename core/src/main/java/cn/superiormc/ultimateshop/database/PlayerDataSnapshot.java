package cn.superiormc.ultimateshop.database;

import cn.superiormc.ultimateshop.objects.caches.FavouriteProductReference;
import cn.superiormc.ultimateshop.objects.caches.ObjectCache;
import cn.superiormc.ultimateshop.objects.caches.ObjectRandomPlaceholderCache;
import cn.superiormc.ultimateshop.objects.caches.ObjectUseTimesCache;
import cn.superiormc.ultimateshop.objects.caches.UseTimesStorageKey;
import cn.superiormc.ultimateshop.objects.items.subobjects.ObjectCustomPlaceholder;
import cn.superiormc.ultimateshop.objects.items.subobjects.ObjectRandomPlaceholder;
import cn.superiormc.ultimateshop.utils.CommonUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable persistence view of one player or the shared server cache.
 *
 * <p>The live cache remains owned by the Bukkit thread. Database workers only
 * receive this detached value object, so a slow write cannot observe a partly
 * changed cache or retain Bukkit objects.</p>
 */
public final class PlayerDataSnapshot {

    private final String storageId;
    private final String playerName;
    private final boolean server;
    private final Map<UseTimesStorageKey, UseTimesSnapshot> useTimes;
    private final Map<String, List<FavouriteProductReference>> favourites;
    private final List<RandomPlaceholderSnapshot> randomPlaceholders;
    private final Map<String, String> customPlaceholders;

    private PlayerDataSnapshot(String storageId,
                               String playerName,
                               boolean server,
                               Map<UseTimesStorageKey, UseTimesSnapshot> useTimes,
                               Map<String, List<FavouriteProductReference>> favourites,
                               List<RandomPlaceholderSnapshot> randomPlaceholders,
                               Map<String, String> customPlaceholders) {
        this.storageId = storageId;
        this.playerName = playerName;
        this.server = server;
        this.useTimes = Collections.unmodifiableMap(new LinkedHashMap<>(useTimes));

        Map<String, List<FavouriteProductReference>> copiedFavourites = new LinkedHashMap<>();
        favourites.forEach((menu, references) ->
                copiedFavourites.put(menu, List.copyOf(references)));
        this.favourites = Collections.unmodifiableMap(copiedFavourites);
        this.randomPlaceholders = List.copyOf(randomPlaceholders);
        this.customPlaceholders = Collections.unmodifiableMap(new LinkedHashMap<>(customPlaceholders));
    }

    public static PlayerDataSnapshot from(ObjectCache cache) {
        boolean server = cache.isServer();
        String storageId = server
                ? "Global-Server"
                : cache.getPlayer().getUniqueId().toString();
        String playerName = server ? "global" : cache.getPlayer().getName();

        Map<UseTimesStorageKey, UseTimesSnapshot> useTimes = new LinkedHashMap<>();
        for (Map.Entry<UseTimesStorageKey, ObjectUseTimesCache> entry
                : cache.getSharedUseTimesCache().entrySet()) {
            UseTimesSnapshot snapshot = UseTimesSnapshot.from(entry.getValue());
            if (!snapshot.isEmpty()) {
                useTimes.put(entry.getKey(), snapshot);
            }
        }

        Map<String, List<FavouriteProductReference>> favourites = new LinkedHashMap<>();
        cache.getFavouriteProductCache().forEach((menu, references) ->
                favourites.put(menu, new ArrayList<>(references)));

        List<RandomPlaceholderSnapshot> randomPlaceholders = new ArrayList<>();
        for (Map.Entry<ObjectRandomPlaceholder, ObjectRandomPlaceholderCache> entry
                : cache.getRandomPlaceholderCache().entrySet()) {
            ObjectRandomPlaceholderCache value = entry.getValue();
            List<String> nowValue = value.getNowValue(false, false);
            if (nowValue != null) {
                randomPlaceholders.add(new RandomPlaceholderSnapshot(
                        entry.getKey().getID(),
                        entry.getKey().getMode(),
                        CommonUtil.translateStringList(nowValue),
                        CommonUtil.timeToString(value.getStoredRefreshDoneTime())
                ));
            }
        }

        Map<String, String> customPlaceholders = new LinkedHashMap<>();
        for (Map.Entry<ObjectCustomPlaceholder, String> entry
                : cache.getCustomPlaceholderCache().entrySet()) {
            customPlaceholders.put(entry.getKey().getID(), entry.getValue());
        }

        return new PlayerDataSnapshot(
                storageId,
                playerName,
                server,
                useTimes,
                favourites,
                randomPlaceholders,
                customPlaceholders
        );
    }

    public String storageId() {
        return storageId;
    }

    public String playerName() {
        return playerName;
    }

    public boolean server() {
        return server;
    }

    public Map<UseTimesStorageKey, UseTimesSnapshot> useTimes() {
        return useTimes;
    }

    public Map<String, List<FavouriteProductReference>> favourites() {
        return favourites;
    }

    public List<RandomPlaceholderSnapshot> randomPlaceholders() {
        return randomPlaceholders;
    }

    public Map<String, String> customPlaceholders() {
        return customPlaceholders;
    }

    public static final class UseTimesSnapshot {
        private final int buyUseTimes;
        private final int totalBuyUseTimes;
        private final int sellUseTimes;
        private final int totalSellUseTimes;
        private final String lastBuyTime;
        private final String lastSellTime;
        private final String lastResetBuyTime;
        private final String lastResetSellTime;
        private final String cooldownBuyTime;
        private final String cooldownSellTime;
        private final List<Map<String, Object>> sellHistory;
        private final List<Map<String, Object>> buyHistory;
        private final double totalSellRevenue;
        private final double totalBuyCost;

        private UseTimesSnapshot(int buyUseTimes,
                                 int totalBuyUseTimes,
                                 int sellUseTimes,
                                 int totalSellUseTimes,
                                 String lastBuyTime,
                                 String lastSellTime,
                                 String lastResetBuyTime,
                                 String lastResetSellTime,
                                 String cooldownBuyTime,
                                 String cooldownSellTime,
                                 List<Map<String, Object>> sellHistory,
                                 List<Map<String, Object>> buyHistory,
                                 double totalSellRevenue,
                                 double totalBuyCost) {
            this.buyUseTimes = buyUseTimes;
            this.totalBuyUseTimes = totalBuyUseTimes;
            this.sellUseTimes = sellUseTimes;
            this.totalSellUseTimes = totalSellUseTimes;
            this.lastBuyTime = lastBuyTime;
            this.lastSellTime = lastSellTime;
            this.lastResetBuyTime = lastResetBuyTime;
            this.lastResetSellTime = lastResetSellTime;
            this.cooldownBuyTime = cooldownBuyTime;
            this.cooldownSellTime = cooldownSellTime;
            this.sellHistory = copyHistory(sellHistory);
            this.buyHistory = copyHistory(buyHistory);
            this.totalSellRevenue = totalSellRevenue;
            this.totalBuyCost = totalBuyCost;
        }

        private static UseTimesSnapshot from(ObjectUseTimesCache cache) {
            return new UseTimesSnapshot(
                    cache.getBuyUseTimes(),
                    cache.getTotalBuyUseTimes(),
                    cache.getSellUseTimes(),
                    cache.getTotalSellUseTimes(),
                    cache.getLastBuyTime(),
                    cache.getLastSellTime(),
                    cache.getLastResetBuyTime(),
                    cache.getLastResetSellTime(),
                    cache.getCooldownBuyTime(),
                    cache.getCooldownSellTime(),
                    cache.getSellHistorySerialized(),
                    cache.getBuyHistorySerialized(),
                    cache.getTotalSellRevenue(),
                    cache.getTotalBuyCost()
            );
        }

        private static List<Map<String, Object>> copyHistory(List<Map<String, Object>> history) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> record : history) {
                result.add(Collections.unmodifiableMap(new LinkedHashMap<>(record)));
            }
            return List.copyOf(result);
        }

        public int buyUseTimes() { return buyUseTimes; }
        public int totalBuyUseTimes() { return totalBuyUseTimes; }
        public int sellUseTimes() { return sellUseTimes; }
        public int totalSellUseTimes() { return totalSellUseTimes; }
        public String lastBuyTime() { return lastBuyTime; }
        public String lastSellTime() { return lastSellTime; }
        public String lastResetBuyTime() { return lastResetBuyTime; }
        public String lastResetSellTime() { return lastResetSellTime; }
        public String cooldownBuyTime() { return cooldownBuyTime; }
        public String cooldownSellTime() { return cooldownSellTime; }
        public List<Map<String, Object>> sellHistory() { return sellHistory; }
        public List<Map<String, Object>> buyHistory() { return buyHistory; }
        public double totalSellRevenue() { return totalSellRevenue; }
        public double totalBuyCost() { return totalBuyCost; }

        public boolean isEmpty() {
            return buyUseTimes == 0 && totalBuyUseTimes == 0
                    && sellUseTimes == 0 && totalSellUseTimes == 0
                    && lastBuyTime == null && lastSellTime == null
                    && lastResetBuyTime == null && lastResetSellTime == null
                    && cooldownBuyTime == null && cooldownSellTime == null
                    && sellHistory.isEmpty() && buyHistory.isEmpty()
                    && totalSellRevenue == 0 && totalBuyCost == 0;
        }
    }

    public record RandomPlaceholderSnapshot(String id,
                                             String mode,
                                             String nowValue,
                                             String refreshDoneTime) {
    }
}
