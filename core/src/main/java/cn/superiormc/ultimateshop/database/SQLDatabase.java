package cn.superiormc.ultimateshop.database;

import cn.superiormc.ultimateshop.UltimateShop;
import cn.superiormc.ultimateshop.database.sql.DatabaseDialect;
import cn.superiormc.ultimateshop.database.sql.H2Dialect;
import cn.superiormc.ultimateshop.database.sql.MySQLDialect;
import cn.superiormc.ultimateshop.database.sql.PostgreSQLDialect;
import cn.superiormc.ultimateshop.database.sql.SQLiteDialect;
import cn.superiormc.ultimateshop.managers.CacheManager;
import cn.superiormc.ultimateshop.managers.ConfigManager;
import cn.superiormc.ultimateshop.objects.caches.ObjectCache;
import cn.superiormc.ultimateshop.objects.caches.FavouriteProductReference;
import cn.superiormc.ultimateshop.objects.caches.ObjectRandomPlaceholderCache;
import cn.superiormc.ultimateshop.objects.caches.ObjectUseTimesCache;
import cn.superiormc.ultimateshop.objects.caches.UseTimesStorageKey;
import cn.superiormc.ultimateshop.objects.items.subobjects.ObjectCustomPlaceholder;
import cn.superiormc.ultimateshop.utils.CommonUtil;
import cn.superiormc.ultimateshop.utils.TextUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class SQLDatabase extends AbstractDatabase {

    public record TransactionLog(LocalDateTime createdAt,
                                 String playerUuid,
                                 String playerName,
                                 String shopId,
                                 String shopName,
                                 String itemId,
                                 String itemName,
                                 String action,
                                 int amount,
                                 double multiplier,
                                 String priceText) {
    }

    private HikariDataSource dataSource;

    private DatabaseDialect dialect;

    @Override
    public void onInit() {
        onClose();

        TextUtil.sendMessage(
                null,
                TextUtil.pluginPrefix() + " §fConnecting to SQL database..."
        );

        String jdbcUrl = ConfigManager.configManager.getString("database.jdbc-url");
        initDialect(jdbcUrl);
        dialect.needExtraDownload(jdbcUrl);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);

        String user = ConfigManager.configManager.getString("database.properties.user");
        if (user != null) {
            config.setUsername(user);
            config.setPassword(
                    ConfigManager.configManager.getString("database.properties.password")
            );
        }

        config.setPoolName("UltimateShop-Hikari");
        config.setMaximumPoolSize(dialect.maxPoolSize());
        config.setMinimumIdle(dialect.minIdle());

        dataSource = new HikariDataSource(config);

        createTables();
    }

    @Override
    public void onClose() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        if (dialect != null) {
            dialect.closeDrivers();
        }
        dataSource = null;
        dialect = null;
    }

    private void initDialect(String jdbcUrl) {
        List<DatabaseDialect> dialects = List.of(
                new MySQLDialect(),
                new H2Dialect(),
                new PostgreSQLDialect(),
                new SQLiteDialect()
        );

        this.dialect = dialects.stream()
                .filter(d -> d.matches(jdbcUrl))
                .findFirst()
                .orElse(new MySQLDialect());
    }

    private void createTables() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute(dialect.createUseTimesTable());
            stmt.execute(dialect.createFavouriteTable());

            if (!UltimateShop.freeVersion) {
                stmt.execute(dialect.createRandomPlaceholderTable());
                stmt.execute(dialect.createCustomPlaceholderTable());
                stmt.execute(dialect.createTransactionLogTable());
                for (String indexSql : dialect.createTransactionLogIndexes()) {
                    stmt.execute(indexSql);
                }
            }

            addHistoryColumns(conn);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void addHistoryColumns(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE ultimateshop_useTimes ADD COLUMN sellHistory TEXT");
        } catch (SQLException ignored) {}
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE ultimateshop_useTimes ADD COLUMN buyHistory TEXT");
        } catch (SQLException ignored) {}
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE ultimateshop_useTimes ADD COLUMN totalSellRevenue DOUBLE DEFAULT 0");
        } catch (SQLException ignored) {}
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE ultimateshop_useTimes ADD COLUMN totalBuyCost DOUBLE DEFAULT 0");
        } catch (SQLException ignored) {}
    }

    @Override
    public void checkData(ObjectCache cache) {
        CompletableFuture.runAsync(
                () -> loadData(cache),
                DatabaseExecutor.getExecutor()
        );
    }

    private void loadData(ObjectCache cache) {
        String playerUUID = cache.isServer()
                ? "Global-Server"
                : cache.getPlayer().getUniqueId().toString();

        try (Connection conn = dataSource.getConnection()) {

            loadUseTimes(conn, cache, playerUUID);
            loadFavourites(conn, cache, playerUUID);

            if (!UltimateShop.freeVersion) {
                loadPlaceholders(conn, cache, playerUUID);
                loadCustomPlaceholders(conn, cache, playerUUID);
            }

            cache.ready();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void loadFavourites(Connection conn, ObjectCache cache, String playerUUID)
            throws SQLException {

        String sql = """
                SELECT menuName, sortOrder, shop, product
                FROM ultimateshop_favourites
                WHERE playerUUID = ?
                ORDER BY menuName ASC, sortOrder ASC
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUUID);

            try (ResultSet rs = ps.executeQuery()) {
                String currentMenu = null;
                List<FavouriteProductReference> references = new ArrayList<>();
                while (rs.next()) {
                    String menuName = rs.getString("menuName");
                    if (currentMenu != null && !currentMenu.equals(menuName)) {
                        cache.setFavouriteProductCache(currentMenu, references);
                        references = new ArrayList<>();
                    }
                    references.add(new FavouriteProductReference(
                            rs.getString("shop"),
                            rs.getString("product")
                    ));
                    currentMenu = menuName;
                }
                if (currentMenu != null) {
                    cache.setFavouriteProductCache(currentMenu, references);
                }
            }
        }
    }

    private void loadUseTimes(Connection conn, ObjectCache cache, String playerUUID)
            throws SQLException {

        String sql = "SELECT * FROM ultimateshop_useTimes WHERE playerUUID = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUUID);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String shop = rs.getString("shop");
                    String product = rs.getString("product");
                    cache.setUseTimesCache(
                            shop,
                            product,
                            rs.getInt("buyUseTimes"),
                            rs.getInt("totalBuyUseTimes"),
                            rs.getInt("sellUseTimes"),
                            rs.getInt("totalSellUseTimes"),
                            rs.getString("lastBuyTime"),
                            rs.getString("lastSellTime"),
                            rs.getString("lastResetBuyTime"),
                            rs.getString("lastResetSellTime"),
                            rs.getString("cooldownBuyTime"),
                            rs.getString("cooldownSellTime")
                    );
                    ObjectUseTimesCache useTimesCache = cache.getSharedUseTimesCache()
                            .get(new UseTimesStorageKey(shop, product));
                    if (useTimesCache != null) {
                        double totalSellRevenue = rs.getDouble("totalSellRevenue");
                        if (!rs.wasNull()) {
                            useTimesCache.setTotalSellRevenue(totalSellRevenue);
                        }
                        double totalBuyCost = rs.getDouble("totalBuyCost");
                        if (!rs.wasNull()) {
                            useTimesCache.setTotalBuyCost(totalBuyCost);
                        }
                        loadHistory(useTimesCache,
                                rs.getString("sellHistory"),
                                rs.getString("buyHistory"));
                    }
                }
            }
        }
    }

    private void loadPlaceholders(Connection conn, ObjectCache cache, String playerUUID)
            throws SQLException {

        String sql = """
                SELECT placeholderID, nowValue, refreshDoneTime
                FROM ultimateshop_randomPlaceholders
                WHERE playerUUID = ?
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUUID);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String nowValue = rs.getString("nowValue");
                    String refreshDoneTime = rs.getString("refreshDoneTime");
                    if (nowValue == null || refreshDoneTime == null) continue;

                    cache.setRandomPlaceholderCache(
                            rs.getString("placeholderID"),
                            refreshDoneTime,
                            CommonUtil.translateString(nowValue)
                    );
                }
            }
        }
    }

    private void loadCustomPlaceholders(Connection conn, ObjectCache cache, String playerUUID)
            throws SQLException {

        String sql = """
                SELECT placeholderID, nowValue
                FROM ultimateshop_customPlaceholders
                WHERE playerUUID = ?
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, playerUUID);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String nowValue = rs.getString("nowValue");
                    if (nowValue == null) continue;

                    cache.setCustomPlaceholderCache(
                            rs.getString("placeholderID"),
                            nowValue
                    );
                }
            }
        }
    }

    @Override
    public void updateData(ObjectCache cache, boolean quitServer) {
        ObjectCache.SaveRevision saveRevision;
        PlayerDataSnapshot snapshot;
        synchronized (cache.getSaveLock()) {
            saveRevision = cache.captureSaveRevision(quitServer);
            snapshot = PlayerDataSnapshot.from(cache);
        }
        DatabaseExecutor.executePlayerSave(snapshot.storageId(), () -> {
            try {
                boolean saved = saveSections(snapshot, saveRevision);
                if (saved) {
                    cache.markSaved(saveRevision);
                }
            } finally {
                if (quitServer) {
                    CacheManager.cacheManager.removeObjectCache(cache);
                } else {
                    cache.finishAutoSave();
                }
            }
        }, !quitServer);
    }

    private boolean saveSections(PlayerDataSnapshot snapshot, ObjectCache.SaveRevision saveRevision) {
        if (!saveRevision.hasSections()) {
            return true;
        }

        try (Connection conn = dataSource.getConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                if (saveRevision.includes(ObjectCache.DirtySection.USE_TIMES)) {
                    saveUseTimes(conn, snapshot);
                }
                if (saveRevision.includes(ObjectCache.DirtySection.FAVOURITES)) {
                    saveFavourites(conn, snapshot);
                }
                if (!UltimateShop.freeVersion) {
                    if (saveRevision.includes(ObjectCache.DirtySection.RANDOM_PLACEHOLDERS)) {
                        savePlaceholders(conn, snapshot);
                    }
                    if (saveRevision.includes(ObjectCache.DirtySection.CUSTOM_PLACEHOLDERS)) {
                        saveCustomPlaceholders(conn, snapshot);
                    }
                }
                conn.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                exception.printStackTrace();
                return false;
            } finally {
                try {
                    if (!conn.isClosed() && conn.getAutoCommit() != originalAutoCommit) {
                        conn.setAutoCommit(originalAutoCommit);
                    }
                } catch (SQLException exception) {
                    exception.printStackTrace();
                }
            }
        } catch (SQLException exception) {
            exception.printStackTrace();
            return false;
        }
    }

    private void saveFavourites(Connection conn, PlayerDataSnapshot snapshot) throws SQLException {
        if (snapshot.server()) {
            return;
        }
        String playerUUID = snapshot.storageId();

        try (PreparedStatement deletePs = conn.prepareStatement(dialect.deleteFavourites());
             PreparedStatement insertPs = conn.prepareStatement(dialect.insertFavourite())) {

            deletePs.setString(1, playerUUID);
            deletePs.executeUpdate();

            for (Map.Entry<String, List<FavouriteProductReference>> entry : snapshot.favourites().entrySet()) {
                List<FavouriteProductReference> references = entry.getValue();
                for (int i = 0; i < references.size(); i++) {
                    FavouriteProductReference reference = references.get(i);
                    insertPs.setString(1, playerUUID);
                    insertPs.setString(2, entry.getKey());
                    insertPs.setInt(3, i);
                    insertPs.setString(4, reference.shop());
                    insertPs.setString(5, reference.product());
                    if (dialect.supportBatch()) {
                        insertPs.addBatch();
                    } else {
                        insertPs.executeUpdate();
                    }
                }
            }

            if (dialect.supportBatch()) {
                insertPs.executeBatch();
            }
        }
    }

    private void saveUseTimes(Connection conn, PlayerDataSnapshot snapshot) throws SQLException {
        String playerUUID = snapshot.storageId();
        String sql = dialect.upsertUseTimes();

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map.Entry<UseTimesStorageKey, PlayerDataSnapshot.UseTimesSnapshot> entry
                    : snapshot.useTimes().entrySet()) {
                writeUseTimesCache(ps, playerUUID, entry.getKey(), entry.getValue());
            }

            if (dialect.supportBatch()) {
                ps.executeBatch();
            }
        }
    }

    private void writeUseTimesCache(PreparedStatement ps,
                                    String playerUUID,
                                    UseTimesStorageKey key,
                                    PlayerDataSnapshot.UseTimesSnapshot state) throws SQLException {
        if (state == null || state.isEmpty()) {
            return;
        }
        fillUseTimes(
                ps,
                playerUUID,
                key,
                state.buyUseTimes(),
                state.totalBuyUseTimes(),
                state.sellUseTimes(),
                state.totalSellUseTimes(),
                state.lastBuyTime(),
                state.lastSellTime(),
                state.lastResetBuyTime(),
                state.lastResetSellTime(),
                state.cooldownBuyTime(),
                state.cooldownSellTime(),
                serializeHistory(state.sellHistory()),
                serializeHistory(state.buyHistory()),
                state.totalSellRevenue(),
                state.totalBuyCost()
        );
    }

    private void fillUseTimes(PreparedStatement ps,
                              String playerUUID,
                              UseTimesStorageKey key,
                              int buyUseTimes,
                              int totalBuyUseTimes,
                              int sellUseTimes,
                              int totalSellUseTimes,
                              String lastBuyTime,
                              String lastSellTime,
                              String lastResetBuyTime,
                              String lastResetSellTime,
                              String cooldownBuyTime,
                              String cooldownSellTime,
                              String sellHistory,
                              String buyHistory,
                              double totalSellRevenue,
                              double totalBuyCost) throws SQLException {
        ps.setString(1, playerUUID);
        ps.setString(2, key.shop());
        ps.setString(3, key.product());
        ps.setInt(4, buyUseTimes);
        ps.setInt(5, totalBuyUseTimes);
        ps.setInt(6, sellUseTimes);
        ps.setInt(7, totalSellUseTimes);
        ps.setString(8, lastBuyTime);
        ps.setString(9, lastSellTime);
        ps.setString(10, lastResetBuyTime);
        ps.setString(11, lastResetSellTime);
        ps.setString(12, cooldownBuyTime);
        ps.setString(13, cooldownSellTime);
        ps.setString(14, sellHistory);
        ps.setString(15, buyHistory);
        ps.setDouble(16, totalSellRevenue);
        ps.setDouble(17, totalBuyCost);

        if (dialect.supportBatch()) {
            ps.addBatch();
        } else {
            ps.executeUpdate();
        }
    }

    private void savePlaceholders(Connection conn, PlayerDataSnapshot snapshot) throws SQLException {
        String playerUUID = snapshot.storageId();
        String sql = dialect.upsertRandomPlaceholder();

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (PlayerDataSnapshot.RandomPlaceholderSnapshot placeholder
                    : snapshot.randomPlaceholders()) {
                if ("ONCE".equals(placeholder.mode())) {
                    continue;
                }
                ps.setString(1, playerUUID);
                ps.setString(2, placeholder.id());
                ps.setString(3, placeholder.nowValue());
                ps.setString(4, placeholder.refreshDoneTime());

                if (dialect.supportBatch()) {
                    ps.addBatch();
                } else {
                    ps.executeUpdate();
                }
            }

            if (dialect.supportBatch()) {
                ps.executeBatch();
            }
        }
    }

    private void saveCustomPlaceholders(Connection conn, PlayerDataSnapshot snapshot) throws SQLException {
        String playerUUID = snapshot.storageId();
        String sql = dialect.upsertCustomPlaceholder();

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Map.Entry<String, String> entry : snapshot.customPlaceholders().entrySet()) {
                ps.setString(1, playerUUID);
                ps.setString(2, entry.getKey());
                ps.setString(3, entry.getValue());

                if (dialect.supportBatch()) {
                    ps.addBatch();
                } else {
                    ps.executeUpdate();
                }
            }

            if (dialect.supportBatch()) {
                ps.executeBatch();
            }
        }
    }

    private String serializeHistory(List<Map<String, Object>> history) {
        JSONArray arr = new JSONArray();
        for (Map<String, Object> record : history) {
            JSONObject obj = new JSONObject();
            obj.put("times", record.get("times"));
            obj.put("resetTime", record.get("resetTime"));
            arr.put(obj);
        }
        return arr.toString();
    }

    private void loadHistory(ObjectUseTimesCache cache, String sellHistoryJson, String buyHistoryJson) {
        if (sellHistoryJson != null && !sellHistoryJson.isEmpty()) {
            JSONArray arr = new JSONArray(sellHistoryJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                cache.addSellHistoryRecord(obj.getInt("times"), obj.optString("resetTime", null));
            }
        }
        if (buyHistoryJson != null && !buyHistoryJson.isEmpty()) {
            JSONArray arr = new JSONArray(buyHistoryJson);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                cache.addBuyHistoryRecord(obj.getInt("times"), obj.optString("resetTime", null));
            }
        }
    }

    @Override
    public void updateDataOnDisable(ObjectCache cache, boolean disable) {
        ObjectCache.SaveRevision saveRevision;
        PlayerDataSnapshot snapshot;
        synchronized (cache.getSaveLock()) {
            saveRevision = cache.captureSaveRevision(true);
            snapshot = PlayerDataSnapshot.from(cache);
        }
        try {
            boolean saved = saveSections(snapshot, saveRevision);
            if (saved) {
                cache.markSaved(saveRevision);
            }
        } finally {
            CacheManager.cacheManager.removeObjectCache(cache);
        }
    }

    public void logTransaction(LocalDateTime createdAt,
                               String playerUuid,
                               String playerName,
                               String shopId,
                               String shopName,
                               String itemId,
                               String itemName,
                               String action,
                               int amount,
                               double multiplier,
                               String priceText) {
        if (dataSource == null || dialect == null) {
            return;
        }
        DatabaseExecutor.getExecutor().execute(() -> logTransactions(List.of(
                new TransactionLog(createdAt, playerUuid, playerName, shopId, shopName,
                        itemId, itemName, action, amount, multiplier, priceText)
        )));
    }

    /** Writes a batch in one transaction; returns false so callers can retain failed entries. */
    public boolean logTransactions(List<TransactionLog> logs) {
        if (dataSource == null || dialect == null || logs == null || logs.isEmpty()) {
            return false;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(dialect.insertTransactionLog())) {
            boolean originalAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(false);
                for (TransactionLog log : logs) {
                    ps.setTimestamp(1, Timestamp.valueOf(log.createdAt()));
                    ps.setString(2, log.playerUuid());
                    ps.setString(3, log.playerName());
                    ps.setString(4, log.shopId());
                    ps.setString(5, log.shopName());
                    ps.setString(6, log.itemId());
                    ps.setString(7, log.itemName());
                    ps.setString(8, log.action());
                    ps.setInt(9, log.amount());
                    ps.setDouble(10, log.multiplier());
                    ps.setString(11, log.priceText());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
                return true;
            } catch (SQLException exception) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                exception.printStackTrace();
                return false;
            } finally {
                try {
                    if (!conn.isClosed() && conn.getAutoCommit() != originalAutoCommit) {
                        conn.setAutoCommit(originalAutoCommit);
                    }
                } catch (SQLException exception) {
                    exception.printStackTrace();
                }
            }
        } catch (SQLException exception) {
            exception.printStackTrace();
            return false;
        }
    }
}
