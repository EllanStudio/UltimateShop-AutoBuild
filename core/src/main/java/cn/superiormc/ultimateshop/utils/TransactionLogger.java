package cn.superiormc.ultimateshop.utils;

import cn.superiormc.ultimateshop.UltimateShop;
import cn.superiormc.ultimateshop.database.SQLDatabase;
import cn.superiormc.ultimateshop.managers.ConfigManager;
import cn.superiormc.ultimateshop.managers.DatabaseManager;
import cn.superiormc.ultimateshop.objects.buttons.ObjectItem;
import org.bukkit.entity.Player;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Transaction logging with bounded batches and explicit lifecycle flushes. */
public final class TransactionLogger {

    private static final int DEFAULT_BATCH_SIZE = 500;
    private static final int DEFAULT_MAX_PENDING_LOGS = 20_000;
    private static final long DEFAULT_FLUSH_PERIOD_TICKS = 100L;

    private static final Queue<SQLDatabase.TransactionLog> pendingDatabaseLogs =
            new ConcurrentLinkedQueue<>();
    private static final Queue<FileLog> pendingFileLogs = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean flushScheduled = new AtomicBoolean();
    private static final Object flushLock = new Object();
    private static final Object lifecycleLock = new Object();

    private static boolean databaseMisconfigurationWarned;
    private static boolean queueOverflowWarned;
    private static SchedulerUtil flushTask;

    private TransactionLogger() {
    }

    public static void start() {
        if (UltimateShop.freeVersion || ConfigManager.configManager == null
                || !ConfigManager.configManager.getBoolean("log-transaction.enabled")) {
            return;
        }
        synchronized (lifecycleLock) {
            if (flushTask == null) {
                flushTask = SchedulerUtil.runTaskTimerAsynchronously(
                        TransactionLogger::flush,
                        getFlushPeriodTicks(),
                        getFlushPeriodTicks()
                );
            }
        }
    }

    public static void stopAndFlush() {
        synchronized (lifecycleLock) {
            if (flushTask != null) {
                flushTask.cancel();
                flushTask = null;
            }
        }
        flush(true);
    }

    public static void flush() {
        flush(false);
    }

    private static void flush(boolean flushAll) {
        synchronized (flushLock) {
            flushDatabaseLogs(flushAll);
            flushFileLogs(flushAll);
        }
    }

    public static void log(Player player,
                           ObjectItem item,
                           int amount,
                           String multiplier,
                           String action,
                           String priceText) {
        if (player == null || item == null
                || !ConfigManager.configManager.getBoolean("log-transaction.enabled")
                || UltimateShop.freeVersion) {
            return;
        }
        start();

        String storage = ConfigManager.configManager.getString("log-transaction.storage");
        if (storage == null || storage.isBlank()) {
            storage = "file";
        }
        storage = storage.toLowerCase();

        double multiplierValue;
        try {
            multiplierValue = Double.parseDouble(multiplier);
        } catch (NumberFormatException exception) {
            multiplierValue = 1.0;
        }

        if ("database".equals(storage)) {
            if (getSqlDatabase() == null) {
                warnDatabaseMisconfiguration();
                return;
            }
            offerDatabaseLog(new SQLDatabase.TransactionLog(
                    CommonUtil.getNowTime(),
                    player.getUniqueId().toString(),
                    player.getName(),
                    item.getShop(),
                    item.getShopObject().getShopDisplayName(),
                    item.getProduct(),
                    TextUtil.parse(item.getDisplayName(player)),
                    action,
                    amount,
                    multiplierValue,
                    priceText
            ));
        } else {
            String filePath = ConfigManager.configManager.getString("log-transaction.file");
            String message = buildMessage(player, item, amount, multiplier, action, priceText);
            if (filePath == null || filePath.isBlank()) {
                TextUtil.sendMessage(null, TextUtil.pluginPrefix() + " §fLog: " + message);
                return;
            }
            offerFileLog(new FileLog(filePath, message));
        }

        requestFlushIfNeeded();
    }

    private static void offerDatabaseLog(SQLDatabase.TransactionLog log) {
        if (pendingDatabaseLogs.size() >= getMaxPendingLogs()) {
            warnQueueOverflow();
            return;
        }
        pendingDatabaseLogs.add(log);
    }

    private static void offerFileLog(FileLog log) {
        if (pendingFileLogs.size() >= getMaxPendingLogs()) {
            warnQueueOverflow();
            return;
        }
        pendingFileLogs.add(log);
    }

    private static void requestFlushIfNeeded() {
        if (pendingDatabaseLogs.size() < getBatchSize()
                && pendingFileLogs.size() < getBatchSize()) {
            return;
        }
        if (!flushScheduled.compareAndSet(false, true)) {
            return;
        }
        SchedulerUtil.runTaskAsynchronously(() -> {
            try {
                flush(false);
            } finally {
                flushScheduled.set(false);
                if (pendingDatabaseLogs.size() >= getBatchSize()
                        || pendingFileLogs.size() >= getBatchSize()) {
                    requestFlushIfNeeded();
                }
            }
        });
    }

    private static void flushDatabaseLogs(boolean flushAll) {
        SQLDatabase sqlDatabase = getSqlDatabase();
        if (sqlDatabase == null) {
            return;
        }
        do {
            List<SQLDatabase.TransactionLog> batch = new ArrayList<>(getBatchSize());
            for (int i = 0; i < getBatchSize(); i++) {
                SQLDatabase.TransactionLog log = pendingDatabaseLogs.poll();
                if (log == null) {
                    break;
                }
                batch.add(log);
            }
            if (batch.isEmpty()) {
                return;
            }
            if (!sqlDatabase.logTransactions(batch)) {
                pendingDatabaseLogs.addAll(batch);
                return;
            }
        } while (flushAll && !pendingDatabaseLogs.isEmpty());
    }

    private static void flushFileLogs(boolean flushAll) {
        do {
            List<FileLog> batch = new ArrayList<>(getBatchSize());
            for (int i = 0; i < getBatchSize(); i++) {
                FileLog log = pendingFileLogs.poll();
                if (log == null) {
                    break;
                }
                batch.add(log);
            }
            if (batch.isEmpty()) {
                return;
            }

            Map<String, List<String>> messagesByPath = new LinkedHashMap<>();
            for (FileLog log : batch) {
                messagesByPath.computeIfAbsent(log.path(), ignored -> new ArrayList<>())
                        .add(log.message());
            }
            for (Map.Entry<String, List<String>> entry : messagesByPath.entrySet()) {
                CommonUtil.logFile(entry.getKey(), String.join(System.lineSeparator(), entry.getValue()));
            }
        } while (flushAll && !pendingFileLogs.isEmpty());
    }

    private static SQLDatabase getSqlDatabase() {
        if (!ConfigManager.configManager.getBoolean("database.enabled")
                || DatabaseManager.databaseManager == null
                || !(DatabaseManager.databaseManager.database instanceof SQLDatabase sqlDatabase)) {
            return null;
        }
        return sqlDatabase;
    }

    private static int getBatchSize() {
        return Math.max(1, ConfigManager.configManager.getInt(
                "log-transaction.cache.max-batch-size", DEFAULT_BATCH_SIZE));
    }

    private static int getMaxPendingLogs() {
        return Math.max(getBatchSize(), ConfigManager.configManager.getInt(
                "log-transaction.cache.max-pending", DEFAULT_MAX_PENDING_LOGS));
    }

    private static long getFlushPeriodTicks() {
        return Math.max(20L, ConfigManager.configManager.getLong(
                "log-transaction.cache.flush-period-ticks", DEFAULT_FLUSH_PERIOD_TICKS));
    }

    private static String buildMessage(Player player,
                                       ObjectItem item,
                                       int amount,
                                       String multiplier,
                                       String action,
                                       String priceText) {
        return CommonUtil.modifyString(player,
                ConfigManager.configManager.getString("log-transaction.format"),
                "player", player.getName(),
                "player-uuid", player.getUniqueId().toString(),
                "shop", item.getShop(),
                "shop-name", item.getShopObject().getShopDisplayName(),
                "item", item.getProduct(),
                "item-name", TextUtil.parse(item.getDisplayName(player)),
                "amount", String.valueOf(amount),
                "multiplier", multiplier,
                "price", priceText,
                "buy-or-sell", action,
                "time", CommonUtil.timeToString(CommonUtil.getNowTime(),
                        ConfigManager.configManager.getString("log-transaction.time-format")));
    }

    private static void warnDatabaseMisconfiguration() {
        if (databaseMisconfigurationWarned) {
            return;
        }
        databaseMisconfigurationWarned = true;
        TextUtil.sendMessage(null, TextUtil.pluginPrefix()
                + " §cWarning: transaction log database storage is unavailable; logs are not saved.");
    }

    private static void warnQueueOverflow() {
        if (queueOverflowWarned) {
            return;
        }
        queueOverflowWarned = true;
        TextUtil.sendMessage(null, TextUtil.pluginPrefix()
                + " §cWarning: transaction log queue is full; new log entries are being dropped.");
    }

    private record FileLog(String path, String message) {
    }
}
