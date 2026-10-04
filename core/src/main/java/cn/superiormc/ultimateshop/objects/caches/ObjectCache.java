package cn.superiormc.ultimateshop.objects.caches;

import cn.superiormc.ultimateshop.UltimateShop;
import cn.superiormc.ultimateshop.database.DatabaseExecutor;
import cn.superiormc.ultimateshop.managers.CacheManager;
import cn.superiormc.ultimateshop.managers.ConfigManager;
import cn.superiormc.ultimateshop.managers.DatabaseManager;
import cn.superiormc.ultimateshop.managers.ErrorManager;
import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import cn.superiormc.ultimateshop.objects.items.subobjects.ObjectCustomPlaceholder;
import cn.superiormc.ultimateshop.objects.items.subobjects.ObjectRandomPlaceholder;
import cn.superiormc.ultimateshop.utils.CommonUtil;
import cn.superiormc.ultimateshop.utils.TextUtil;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public class ObjectCache {

    public enum DirtySection {
        USE_TIMES,
        FAVOURITES,
        RANDOM_PLACEHOLDERS,
        CUSTOM_PLACEHOLDERS
    }

    public static final class SaveRevision {

        private final long cacheVersion;

        private final long[] sectionVersions;

        private final boolean[] includedSections;

        private SaveRevision(long cacheVersion,
                             long[] sectionVersions,
                             boolean[] includedSections) {
            this.cacheVersion = cacheVersion;
            this.sectionVersions = sectionVersions;
            this.includedSections = includedSections;
        }

        public boolean includes(DirtySection section) {
            return includedSections[section.ordinal()];
        }

        public boolean hasSections() {
            for (boolean included : includedSections) {
                if (included) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final DirtySection[] DIRTY_SECTIONS = DirtySection.values();

    private final Map<UseTimesStorageKey, ObjectUseTimesCache> sharedUseTimesCache = new ConcurrentHashMap<>();

    private final Map<ObjectItem, ObjectUseTimesCache> useTimesCache = new ConcurrentHashMap<>();

    private final Map<ObjectRandomPlaceholder, ObjectRandomPlaceholderCache> randomPlaceholderCache = new ConcurrentHashMap<>();

    private final Map<ObjectCustomPlaceholder, String> customPlaceholderCache = new ConcurrentHashMap<>();

    private final Map<String, List<FavouriteProductReference>> favouriteProductCache = new ConcurrentHashMap<>();

    private final boolean server;

    private final Player player;

    private volatile boolean initialized = false;

    private volatile boolean ready = false;

    private volatile boolean closed = false;

    // 借鉴 craft-engine 的 unsaved 机制。使用版本号而不是单个 boolean，避免保存期间发生的
    // 新修改被旧保存任务错误地清除。
    private final AtomicLong modificationVersion = new AtomicLong();

    private final AtomicLong savedVersion = new AtomicLong();

    private final AtomicLongArray sectionModificationVersions =
            new AtomicLongArray(DIRTY_SECTIONS.length);

    private final AtomicLongArray sectionSavedVersions =
            new AtomicLongArray(DIRTY_SECTIONS.length);

    private final AtomicBoolean autoSaveInProgress = new AtomicBoolean();

    // 同一个缓存可能同时遇到自动保存和玩家退出保存，串行化落盘可避免旧任务覆盖新数据。
    private final Object saveLock = new Object();

    public ObjectCache() {
        this.server = true;
        this.player = null;
    }

    public ObjectCache(Player player) {
        this.server = false;
        this.player = player;
    }

    public void initCache() {
        if (closed || initialized) {
            return;
        }
        initialized = true;
        DatabaseManager.databaseManager.database.checkData(this);
    }

    public void shutCache(boolean quitServer) {
        if (canNotModify()) {
            return;
        }
        boolean autoSave = !quitServer;
        if (autoSave && (!isDirty() || !autoSaveInProgress.compareAndSet(false, true))) {
            return;
        }
        try {
            DatabaseManager.databaseManager.database.updateData(this, quitServer);
        } catch (RejectedExecutionException exception) {
            if (autoSave) {
                finishAutoSave();
            }
            if (DatabaseExecutor.isAcceptingTasks()) {
                throw exception;
            }
            if (quitServer) {
                CacheManager.cacheManager.removeObjectCache(this);
                cancelResetTasks();
            }
            return;
        } catch (RuntimeException | Error exception) {
            if (autoSave) {
                finishAutoSave();
            }
            throw exception;
        }
        if (quitServer) {
            CacheManager.cacheManager.removeObjectCache(this);
            cancelResetTasks();
        }
    }

    public void shutCacheOnDisable(boolean disable) {
        if (canNotModify()) {
            return;
        }
        DatabaseManager.databaseManager.database.updateDataOnDisable(this, disable);
        cancelResetTasks();
    }

    public synchronized void cancelResetTasks() {
        closed = true;
        sharedUseTimesCache.values().forEach(ObjectUseTimesCache::cancelResetTime);
        randomPlaceholderCache.values().forEach(ObjectRandomPlaceholderCache::cancelResetTask);
    }

    /*
    USE TIMES CACHE
     */
    public ObjectUseTimesCache getUseTimesCache(ObjectItem item) {
        if (item == null) {
            return new ObjectUseTimesCache(this);
        }

        UseTimesStorageKey storageKey = item.getUseTimesStorageKey();
        ObjectUseTimesCache existing = sharedUseTimesCache.get(storageKey);
        if (existing != null) {
            existing.bindProduct(item);
            useTimesCache.put(item, existing);
            return existing;
        }

        synchronized (this) {
            if (closed) {
                return new ObjectUseTimesCache(this);
            }
            existing = sharedUseTimesCache.get(storageKey);
            if (existing != null) {
                existing.bindProduct(item);
                useTimesCache.put(item, existing);
                return existing;
            }
            return useTimesCache.computeIfAbsent(item, key -> {
                int defaultBuyTimes = 0;
            int defaultSellTimes = 0;

            if (ConfigManager.configManager.getBoolean("use-times.set-reset-value-by-default")) {
                defaultBuyTimes = key.getBuyTimesResetValue(player);
                defaultSellTimes = key.getSellTimesResetValue(player);
            }

            ObjectUseTimesCache created = new ObjectUseTimesCache(this,
                    defaultBuyTimes,
                    0,
                    defaultSellTimes,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    key
            );
                sharedUseTimesCache.put(storageKey, created);
                return created;
            });
        }
    }

    public synchronized void setUseTimesCache(String shop,
                                              String product,
                                              int buyUseTimes,
                                              int totalBuyUseTimes,
                                              int sellUseTimes,
                                              int totalSellUseTimes,
                                              String lastBuyTime,
                                              String lastSellTime,
                                              String lastResetBuyTime,
                                              String lastResetSellTime,
                                              String cooldownBuyTime,
                                              String cooldownSellTime) {
        if (closed) {
            return;
        }
        ObjectUseTimesCache created = new ObjectUseTimesCache(
                this,
                buyUseTimes,
                totalBuyUseTimes,
                sellUseTimes,
                totalSellUseTimes,
                lastBuyTime,
                lastSellTime,
                lastResetBuyTime,
                lastResetSellTime,
                cooldownBuyTime,
                cooldownSellTime,
                null
        );
        ObjectUseTimesCache previous = sharedUseTimesCache.put(new UseTimesStorageKey(shop, product), created);
        if (previous != null) {
            ObjectItem boundProduct = previous.getProduct();
            if (boundProduct != null) {
                created.bindProduct(boundProduct);
                useTimesCache.replaceAll((item, cache) -> cache == previous ? created : cache);
            }
            previous.cancelResetTime();
        }
    }

    public Map<ObjectItem, ObjectUseTimesCache> getUseTimesCache() {
        return Collections.unmodifiableMap(useTimesCache);
    }

    public Map<UseTimesStorageKey, ObjectUseTimesCache> getSharedUseTimesCache() {
        return Collections.unmodifiableMap(sharedUseTimesCache);
    }

    /*
    RANDOM PLACEHOLDER CACHE
     */
    public void setRandomPlaceholderCache(ObjectRandomPlaceholder placeholder,
                                          String refreshDoneTime,
                                          List<String> nowValue) {

        if (closed || placeholder == null || nowValue == null) {
            return;
        }
        if (!checkPlaceholderScope(placeholder)) {
            return;
        }
        ObjectRandomPlaceholderCache placeholderCache = randomPlaceholderCache.computeIfAbsent(
                placeholder, key -> new ObjectRandomPlaceholderCache(this, key));
        placeholderCache.loadState(nowValue, CommonUtil.stringToTime(refreshDoneTime));
        if (!UltimateShop.freeVersion && !"ONCE".equals(placeholder.getMode())) {
            markDirty(DirtySection.RANDOM_PLACEHOLDERS);
        }
    }

    public void setRandomPlaceholderCache(String id,
                                          String refreshDoneTime,
                                          List<String> nowValue) {

        if (nowValue == null) {
            return;
        }
        ObjectRandomPlaceholder placeholder = ConfigManager.configManager.getRandomPlaceholder(id);
        setRandomPlaceholderCache(placeholder, refreshDoneTime, nowValue);
    }

    public ObjectRandomPlaceholderCache getRandomPlaceholderCache(ObjectRandomPlaceholder placeholder) {
        if (closed || placeholder == null) {
            return null;
        }
        if (!checkPlaceholderScope(placeholder)) {
            return null;
        }
        ObjectRandomPlaceholderCache placeholderCache = randomPlaceholderCache.computeIfAbsent(
                placeholder, key -> new ObjectRandomPlaceholderCache(this, key));
        placeholderCache.initialize();
        return placeholderCache;
    }

    private boolean checkPlaceholderScope(ObjectRandomPlaceholder placeholder) {
        if (server && placeholder.isPerPlayer()) {
            ErrorManager.errorManager.sendErrorMessage(
                    "§cThe random placeholder is per player and can not sync data with server cache.");
            return false;
        }
        if (!server && !placeholder.isPerPlayer()) {
            ErrorManager.errorManager.sendErrorMessage(
                    "§cThe random placeholder is globally and can not sync data with player cache.");
            return false;
        }
        return true;
    }

    public Map<ObjectRandomPlaceholder, ObjectRandomPlaceholderCache> getRandomPlaceholderCache() {
        return Collections.unmodifiableMap(randomPlaceholderCache);
    }

    /*
    CUSTOM PLACEHOLDER
     */
    public void setCustomPlaceholderCache(ObjectCustomPlaceholder placeholder, String nowValue) {
        if (placeholder == null || nowValue == null) {
            return;
        }
        if (!checkCustomPlaceholderScope(placeholder)) {
            return;
        }
        String normalizedValue = placeholder.normalizeValue(nowValue);
        String previousValue = customPlaceholderCache.put(placeholder, normalizedValue);
        if (!UltimateShop.freeVersion && !Objects.equals(previousValue, normalizedValue)) {
            markDirty(DirtySection.CUSTOM_PLACEHOLDERS);
        }
    }

    public void setCustomPlaceholderCache(String id, String nowValue) {
        if (nowValue == null) {
            return;
        }
        ObjectCustomPlaceholder placeholder = ConfigManager.configManager.getCustomPlaceholder(id);
        setCustomPlaceholderCache(placeholder, nowValue);
    }

    public Map<ObjectCustomPlaceholder, String> getCustomPlaceholderCache() {
        return Collections.unmodifiableMap(customPlaceholderCache);
    }

    private boolean checkCustomPlaceholderScope(ObjectCustomPlaceholder placeholder) {
        if (server && placeholder.isPerPlayer()) {
            ErrorManager.errorManager.sendErrorMessage(
                    "§cThe custom placeholder is per player and can not sync data with server cache.");
            return false;
        }
        if (!server && !placeholder.isPerPlayer()) {
            ErrorManager.errorManager.sendErrorMessage(
                    "§cThe custom placeholder is globally and can not sync data with player cache.");
            return false;
        }
        return true;
    }

    /*
    FAVOURITE
     */
    public synchronized void setFavouriteProductCache(String menuName,
                                                      List<FavouriteProductReference> references) {
        if (menuName == null || menuName.isEmpty()) {
            return;
        }
        if (references == null || references.isEmpty()) {
            if (favouriteProductCache.remove(menuName) != null) {
                markDirty(DirtySection.FAVOURITES);
            }
            return;
        }
        List<FavouriteProductReference> newReferences = new ArrayList<>(references);
        List<FavouriteProductReference> previousReferences = favouriteProductCache.put(menuName, newReferences);
        if (!newReferences.equals(previousReferences)) {
            markDirty(DirtySection.FAVOURITES);
        }
    }

    public synchronized List<FavouriteProductReference> getFavouriteProductReferences(String menuName) {
        List<FavouriteProductReference> references = favouriteProductCache.get(menuName);
        if (references == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(references);
    }

    public synchronized boolean addFavouriteProduct(String menuName, ObjectItem item) {
        if (server || player == null || menuName == null || menuName.isEmpty() || item == null || !item.isAllowFavourite()) {
            return false;
        }
        FavouriteProductReference reference = FavouriteProductReference.fromItem(item);
        if (reference == null) {
            return false;
        }
        List<FavouriteProductReference> references = favouriteProductCache.computeIfAbsent(menuName, key -> new ArrayList<>());
        if (references.contains(reference)) {
            return false;
        }
        references.add(reference);
        markDirty(DirtySection.FAVOURITES);
        return true;
    }

    public synchronized boolean hasFavouriteProduct(String menuName, ObjectItem item) {
        FavouriteProductReference reference = FavouriteProductReference.fromItem(item);
        if (menuName == null || menuName.isEmpty() || reference == null) {
            return false;
        }
        List<FavouriteProductReference> references = favouriteProductCache.get(menuName);
        return references != null && references.contains(reference);
    }

    public synchronized int getResolvedFavouriteProductAmount(String menuName) {
        return getResolvedFavouriteProducts(menuName).size();
    }

    public synchronized boolean removeFavouriteProduct(String menuName, ObjectItem item) {
        FavouriteProductReference reference = FavouriteProductReference.fromItem(item);
        if (reference == null) {
            return false;
        }
        return removeFavouriteProduct(menuName, reference);
    }

    public synchronized boolean removeFavouriteProduct(String menuName, FavouriteProductReference reference) {
        if (menuName == null || menuName.isEmpty() || reference == null) {
            return false;
        }
        List<FavouriteProductReference> references = favouriteProductCache.get(menuName);
        if (references == null) {
            return false;
        }
        boolean removed = references.remove(reference);
        if (references.isEmpty()) {
            favouriteProductCache.remove(menuName);
        }
        if (removed) {
            markDirty(DirtySection.FAVOURITES);
        }
        return removed;
    }

    public synchronized boolean moveFavouriteProduct(String menuName, int fromIndex, int toIndex) {
        List<FavouriteProductReference> references = favouriteProductCache.get(menuName);
        if (references == null || fromIndex < 0 || toIndex < 0
                || fromIndex >= references.size() || toIndex >= references.size()) {
            return false;
        }
        if (fromIndex == toIndex) {
            return true;
        }
        FavouriteProductReference reference = references.remove(fromIndex);
        references.add(toIndex, reference);
        markDirty(DirtySection.FAVOURITES);
        return true;
    }

    public synchronized void clearFavouriteProductCache(String menuName) {
        if (menuName == null || menuName.isEmpty()) {
            return;
        }
        if (favouriteProductCache.remove(menuName) != null) {
            markDirty(DirtySection.FAVOURITES);
        }
    }

    public synchronized Map<FavouriteProductReference, ObjectItem> getResolvedFavouriteProducts(String menuName) {
        Map<FavouriteProductReference, ObjectItem> result = new LinkedHashMap<>();
        List<FavouriteProductReference> references = favouriteProductCache.get(menuName);
        if (server || player == null || references == null || references.isEmpty()) {
            return result;
        }

        List<FavouriteProductReference> validReferences = new ArrayList<>();
        for (FavouriteProductReference reference : references) {
            ObjectItem item = reference.resolve(player);
            if (item == null) {
                continue;
            }
            result.put(reference, item);
            validReferences.add(reference);
        }
        if (validReferences.isEmpty()) {
            favouriteProductCache.remove(menuName);
        } else if (validReferences.size() != references.size()) {
            favouriteProductCache.put(menuName, validReferences);
        }
        if (validReferences.size() != references.size()) {
            markDirty(DirtySection.FAVOURITES);
        }
        return result;
    }

    public synchronized Map<String, List<FavouriteProductReference>> getFavouriteProductCache() {
        Map<String, List<FavouriteProductReference>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<FavouriteProductReference>> entry : favouriteProductCache.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    public Player getPlayer() {
        return player;
    }

    public boolean isServer() {
        return server;
    }

    public void close() {
        cancelResetTasks();
        if (player != null) {
            TextUtil.sendMessage(null, TextUtil.pluginPrefix() + " §fUnloaded player data: " + player.getName() + ".");
        }
    }

    public void ready() {
        for (DirtySection section : DIRTY_SECTIONS) {
            int index = section.ordinal();
            sectionSavedVersions.set(index, sectionModificationVersions.get(index));
        }
        savedVersion.set(modificationVersion.get());
        ready = true;
        randomPlaceholderCache.values().forEach(ObjectRandomPlaceholderCache::activateResetTask);
        if (player != null) {
            TextUtil.sendMessage(null, TextUtil.pluginPrefix() + " §fLoaded player data: " + player.getName() + ".");
        }
    }

    public boolean canNotModify() {
        return closed || !ready;
    }

    void markDirty(DirtySection section) {
        if (ready && !closed) {
            sectionModificationVersions.incrementAndGet(section.ordinal());
            modificationVersion.incrementAndGet();
        }
    }

    public boolean isDirty() {
        return modificationVersion.get() != savedVersion.get();
    }

    public SaveRevision captureSaveRevision(boolean includeAllSections) {
        long cacheVersion = modificationVersion.get();
        long[] sectionVersions = new long[DIRTY_SECTIONS.length];
        boolean[] includedSections = new boolean[DIRTY_SECTIONS.length];
        boolean hasIncludedSection = false;

        for (DirtySection section : DIRTY_SECTIONS) {
            int index = section.ordinal();
            long sectionVersion = sectionModificationVersions.get(index);
            sectionVersions[index] = sectionVersion;
            if (includeAllSections || sectionVersion != sectionSavedVersions.get(index)) {
                includedSections[index] = true;
                hasIncludedSection = true;
            }
        }

        // A conservative fallback keeps future unclassified mutations safe.
        if (!hasIncludedSection && cacheVersion != savedVersion.get()) {
            for (DirtySection section : DIRTY_SECTIONS) {
                includedSections[section.ordinal()] = true;
            }
        }
        return new SaveRevision(cacheVersion, sectionVersions, includedSections);
    }

    public void markSaved(SaveRevision revision) {
        for (DirtySection section : DIRTY_SECTIONS) {
            int index = section.ordinal();
            if (revision.includedSections[index]) {
                sectionSavedVersions.accumulateAndGet(
                        index,
                        revision.sectionVersions[index],
                        Math::max
                );
            }
        }
        savedVersion.accumulateAndGet(revision.cacheVersion, Math::max);
    }

    public void finishAutoSave() {
        autoSaveInProgress.set(false);
    }

    public Object getSaveLock() {
        return saveLock;
    }
}
