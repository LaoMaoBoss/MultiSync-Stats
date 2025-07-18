package com.myname.multisyncstats;

import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * 数据库管理器，负责所有数据库操作.
 * 正确的架构:
 * - `mss_synced_placeholders` 表是占位符列表的唯一数据源.
 * - PAPI 扩展调用 getSyncedData 获取所有服务器数据的总和.
 * - 同步任务调用 updateLocalStat 更新本服务器的数据.
 */
public class DatabaseManager {

    private final MultiSyncStats plugin;
    private final HikariDataSource dataSource;
    private final Object columnCreateLock = new Object();

    /**
     * 初始化数据库连接池并初始化表结构.
     * @param plugin 插件主类实例.
     * @throws SQLException 如果连接或初始化失败.
     */
    public DatabaseManager(MultiSyncStats plugin) throws SQLException {
        this.plugin = plugin;
        ConfigurationSection dbConfig = plugin.getConfig().getConfigurationSection("database");
        if (dbConfig == null) {
            throw new SQLException("数据库配置 'database' 部分缺失!");
        }

        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=%s&autoReconnect=true&allowPublicKeyRetrieval=true",
                dbConfig.getString("host"),
                dbConfig.getInt("port"),
                dbConfig.getString("database"),
                dbConfig.getBoolean("useSSL", false)
        ));
        dataSource.setUsername(dbConfig.getString("username"));
        dataSource.setPassword(dbConfig.getString("password"));
        dataSource.addDataSourceProperty("cachePrepStmts", "true");
        dataSource.addDataSourceProperty("prepStmtCacheSize", "250");
        dataSource.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        try {
            // 尝试初始化表结构来验证连接是否成功.
            initializeTables();
        } catch (SQLException e) {
            // 不在此处记录日志. 直接将异常抛出，由主类统一处理.
            throw e;
        }
    }
    
    /**
     * 初始化插件所需的核心数据表.
     * @throws SQLException SQL异常.
     */
    private void initializeTables() throws SQLException {
        String createPlaceholdersTableSQL = "CREATE TABLE IF NOT EXISTS mss_synced_placeholders (" +
                "id INT AUTO_INCREMENT PRIMARY KEY," +
                "placeholder_name VARCHAR(255) NOT NULL UNIQUE" +
                ")";
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createPlaceholdersTableSQL);
        }
    }

    /**
     * 关闭数据库连接池.
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    /**
     * 添加一个新的占位符到数据库, 并创建对应的数据表.
     * @param placeholderName 占位符名称.
     * @return 如果添加成功或已存在，返回 true.
     */
    public boolean addPlaceholder(String placeholderName) {
        // 清理占位符名称，去除PAPI的百分号
        String cleanPlaceholderName = placeholderName.replace("%", "");

        String insertSQL = "INSERT IGNORE INTO mss_synced_placeholders (placeholder_name) VALUES (?)";
        String createTableSQL = "CREATE TABLE IF NOT EXISTS `" + getTableName(cleanPlaceholderName) + "` (" +
                                "`player_uuid` VARCHAR(36) NOT NULL PRIMARY KEY," +
                                "`player_name` VARCHAR(16) NOT NULL" +
                                ")";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement insertStmt = conn.prepareStatement(insertSQL);
             Statement createStmt = conn.createStatement()) {
            // 1. 插入到占位符列表 (存储的是原始带%的名称)
            insertStmt.setString(1, placeholderName);
            insertStmt.executeUpdate();
            
            // 2. 创建数据表 (使用清理后的名称)
            createStmt.execute(createTableSQL);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "添加占位符 " + placeholderName + " 失败.", e);
            return false;
        }
    }

    /**
     * 从数据库中移除一个占位符.
     * @param placeholderName 占位符名称.
     * @return 如果移除成功，返回 true.
     */
    public boolean removePlaceholder(String placeholderName) {
        // 为了数据安全，我们不删除数据表 (mss_data_...)
        String deleteSQL = "DELETE FROM mss_synced_placeholders WHERE placeholder_name = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement deleteStmt = conn.prepareStatement(deleteSQL)) {
            deleteStmt.setString(1, placeholderName);
            int affectedRows = deleteStmt.executeUpdate();
            return affectedRows > 0;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "移除占位符 " + placeholderName + " 失败.", e);
            return false;
        }
    }

    /**
     * 从数据库获取所有需要同步的占位符列表.
     * @return 占位符名称集合.
     */
    public Set<String> getSyncedPlaceholders() {
        Set<String> placeholders = new HashSet<>();
        String sql = "SELECT placeholder_name FROM mss_synced_placeholders";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                placeholders.add(rs.getString("placeholder_name"));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "从数据库获取同步占位符列表失败.", e);
        }
        return placeholders;
    }


    /**
     * 核心方法: 获取某个玩家在所有服务器上某个统计的总和.
     * 由 PAPIExpansion 调用.
     *
     * @param player          玩家.
     * @param placeholderName 占位符的名称 (例如 "statistic_mine_block").
     * @return 字符串格式的合计数据，或 "0" 如果没有数据.
     */
    public String getSyncedData(OfflinePlayer player, String placeholderName) {
        String tableName = getTableName(placeholderName);
        List<String> serverColumns = getColumnsForTable(tableName);

        if (serverColumns.isEmpty()) {
            return "0";
        }
        
        String sumCalculation = serverColumns.stream()
                .map(col -> String.format("IFNULL(`%s`, 0)", col))
                .collect(Collectors.joining(" + "));

        String sql = String.format("SELECT (%s) AS total FROM `%s` WHERE player_uuid = ?", sumCalculation, tableName);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, player.getUniqueId().toString());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long total = rs.getLong("total");
                    return String.valueOf(total);
                } else {
                    return "0";
                }
            }
        } catch (SQLException e) {
            if (e.getMessage().toLowerCase().contains("doesn't exist")) {
                 // 这个错误理论上不应该发生，因为PAPI扩展只会查询在列表中的占位符.
                 // 但作为安全措施，我们记录它.
                 plugin.getLogger().warning("PAPI扩展尝试查询一个不存在的数据表: " + tableName);
                 return "0";
            }
            plugin.getLogger().log(Level.WARNING, "获取同步数据失败 for " + placeholderName, e);
            return "0";
        }
    }

    /**
     * 获取指定表的所有列名，除了 'player_uuid'.
     * @param tableName 表名.
     * @return 列名列表.
     */
    private List<String> getColumnsForTable(String tableName) {
        List<String> columns = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getColumns(conn.getCatalog(), null, tableName, null)) {
                while (rs.next()) {
                    String columnName = rs.getString("COLUMN_NAME");
                    // 同时排除 player_uuid 和 player_name
                    if (!"player_uuid".equalsIgnoreCase(columnName) && !"player_name".equalsIgnoreCase(columnName)) {
                        columns.add(columnName);
                    }
                }
            }
        } catch (SQLException e) {
             if (!e.getMessage().toLowerCase().contains("doesn't exist")) {
                plugin.getLogger().log(Level.SEVERE, "无法获取表 " + tableName + " 的列信息", e);
             }
        }
        return columns;
    }
    
    /**
     * 确保数据表中存在当前服务器的列. 如果不存在，则创建它.
     * @param conn The database connection to use.
     * @param tableName  表名
     * @param serverName 服务器名
     * @throws SQLException 如果检查或创建列时发生SQL错误
     */
    private void ensureServerColumnExists(Connection conn, String tableName, String serverName) throws SQLException {
        // 为了防止多个线程同时尝试创建同一个列（竞态条件），我们在这里使用同步锁。
        // 这是一个罕见的操作（只在服务器第一次被记录时发生），所以性能影响可以忽略不计。
        synchronized (columnCreateLock) {
            if (!columnExists(conn, tableName, serverName)) {
                plugin.getLogger().info(plugin.getLanguageManager().get("console.migration.adding_server_column", "table", tableName, "server", serverName));
                // 注意：在列名和表名周围使用反引号以处理特殊字符
                // 使用 VARCHAR 来存储可能非数字的值，并默认为'0'以便于计算
                String addColumnSQL = String.format("ALTER TABLE `%s` ADD COLUMN `%s` VARCHAR(255) DEFAULT '0'", tableName, serverName);
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate(addColumnSQL);
                    plugin.getLogger().info(plugin.getLanguageManager().get("console.migration.column_added", "column", serverName, "table", tableName));
                }
            }
        }
    }

    /**
     * 更新本服务器上一个玩家的统计数据.
     * 由同步任务调用.
     *
     * @param playerUUID      玩家UUID.
     * @param playerName      玩家名.
     * @param placeholderName 占位符名称 (不带百分号).
     * @param value           新的数值.
     */
    public void updateLocalStat(UUID playerUUID, String playerName, String placeholderName, String value) {
        String tableName = getTableName(placeholderName);
        String serverName = plugin.getServerName();

        try (Connection conn = dataSource.getConnection()) {
            // 步骤 1: 确保服务器列存在
            ensureServerColumnExists(conn, tableName, serverName);

            // 步骤 2: 更新数据
            // 使用 INSERT ... ON DUPLICATE KEY UPDATE 来插入或更新.
            // 这要求 player_uuid 是主键或唯一键.
            String sql = String.format(
                    "INSERT INTO `%s` (player_uuid, player_name, `%s`) VALUES (?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE player_name = VALUES(player_name), `%s` = VALUES(`%s`)",
                    tableName, serverName, serverName, serverName
            );

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerUUID.toString());
                stmt.setString(2, playerName);
                stmt.setString(3, value);
                stmt.executeUpdate();
            }

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "更新本地统计数据失败 for " + placeholderName, e);
        }
    }

    /**
     * 在插件启动时，检查并升级所有已知的数据表结构.
     * 主要用于从旧版本迁移，例如添加 player_name 列.
     * @return 如果执行了任何表结构更改，则返回 true.
     */
    public boolean migrateAllTables() {
        plugin.getLogger().info(plugin.getLanguageManager().get("console.migration.start"));
        Set<String> placeholders = getSyncedPlaceholders();
        boolean migrationPerformed = false;

        for (String placeholderNameWithPct : placeholders) {
            String placeholderName = placeholderNameWithPct.replace("%", "");
            String tableName = getTableName(placeholderName);
            try (Connection conn = dataSource.getConnection()) {
                // 检查并添加 player_name 列 (用于从旧版本迁移)
                if (!columnExists(conn, tableName, "player_name")) {
                    plugin.getLogger().info(plugin.getLanguageManager().get("console.migration.migrating_table", "table", tableName, "column", "player_name"));
                    try (Statement stmt = conn.createStatement()) {
                        String addPlayerNameColSQL = String.format("ALTER TABLE `%s` ADD COLUMN `player_name` VARCHAR(16) NOT NULL AFTER `player_uuid`", tableName);
                        stmt.executeUpdate(addPlayerNameColSQL);
                        migrationPerformed = true;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "检查或迁移表 " + tableName + " 失败。", e);
            }
        }
        return migrationPerformed;
    }

    /**
     * 检查数据库表中是否存在指定的列.
     * @param conn 数据库连接.
     * @param tableName 表名.
     * @param columnName 列名.
     * @return 如果列存在则返回 true.
     * @throws SQLException SQL 异常.
     */
    private boolean columnExists(Connection conn, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getColumns(conn.getCatalog(), null, tableName, columnName)) {
            return rs.next();
        }
    }

    /**
     * 根据占位符名称生成一个安全的表名.
     * @param placeholderName 占位符名称 (不含 %).
     * @return 安全的、可用于SQL的表名.
     */
    private String getTableName(String placeholderName) {
        // 移除百分号，以防万一
        String cleanName = placeholderName.replace("%", "");
        // 将所有不符合规则的字符替换为下划线
        String sanitizedName = cleanName.replaceAll("[^a-zA-Z0-9_]", "_");
        return "mss_" + sanitizedName;
    }
} 