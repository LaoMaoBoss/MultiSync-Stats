package com.myname.multisyncstats;

import com.myname.multisyncstats.placeholder.MssExpansion;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;


/**
 * 插件主类.
 */
public final class MultiSyncStats extends JavaPlugin {

    private DatabaseManager databaseManager;
    private String serverName;
    private Set<String> syncedPlaceholders = Collections.emptySet();
    private Object syncTask;
    private MssExpansion mssExpansion;
    private LanguageManager languageManager;
    private boolean isPaperOrFolia;


    @Override
    public void onEnable() {
        // 1. 更新并加载配置文件
        updateConfig();
        if (!loadAndValidateConfig()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 1.1 初始化语言管理器 (必须在任何使用它的代码之前)
        languageManager = new LanguageManager(this);
        languageManager.updateAllLanguageFiles();
        languageManager.loadSelectedLanguage();

        // 检查服务器是否为 Paper 或 Folia
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            this.isPaperOrFolia = true;
        } catch (ClassNotFoundException e) {
            try {
                Class.forName("com.destroystokyo.paper.PaperConfig");
                this.isPaperOrFolia = true;
            } catch (ClassNotFoundException e2) {
                this.isPaperOrFolia = false;
            }
        }

        if (isPaperOrFolia) {
            getLogger().info(languageManager.get("console.scheduler.paper_folia_detected"));
        } else {
            getLogger().info(languageManager.get("console.scheduler.bukkit_detected"));
        }

        // 2. 初始化数据库连接
        try {
            databaseManager = new DatabaseManager(this);
            getLogger().info(languageManager.get("console.db.init_success"));
        } catch (SQLException e) {
            String errorMessage = e.getMessage().toLowerCase();
            getLogger().severe("==============================================================");
            getLogger().severe(languageManager.get("console.db.connection_failed"));
            if (errorMessage.contains("access denied")) {
                getLogger().severe(languageManager.get("console.db.access_denied"));
            } else if (errorMessage.contains("communications link failure") || errorMessage.contains("could not create connection")) {
                getLogger().severe(languageManager.get("console.db.link_failure"));
            } else if (errorMessage.contains("unknown database")) {
                getLogger().severe(languageManager.get("console.db.unknown_database"));
            } else {
                getLogger().severe(languageManager.get("console.db.unknown_error"));
                getLogger().severe(languageManager.get("console.db.error_details", "error", e.getMessage()));
            }
            getLogger().severe("==============================================================");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        // 3. 从数据库加载需要同步的占位符列表 (同步)
        reloadPlaceholdersFromDB();
        getLogger().info(languageManager.get("console.db.placeholders_loaded", "count", String.valueOf(this.syncedPlaceholders.size())));

        // 4. 检查并迁移所有数据表结构
        boolean migrationPerformed = databaseManager.migrateAllTables();
        if (migrationPerformed) {
            getLogger().info(languageManager.get("console.migration.finish"));
        } else {
            getLogger().info(languageManager.get("console.migration.no_migration_needed"));
        }

        // 5. 注册 PAPI 扩展
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            mssExpansion = new MssExpansion(this);
            mssExpansion.register();
            
            getLogger().info(languageManager.get("console.papi.register_success"));
        } else {
            getLogger().warning(languageManager.get("console.papi.not_found"));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 6. 注册指令和 TabCompleter
        MSSCommand mssCommand = new MSSCommand(this);
        getCommand("mss").setExecutor(mssCommand);
        getCommand("mss").setTabCompleter(mssCommand);
        
        // 7. 启动后台同步任务
        startSyncTask();

        getLogger().info(languageManager.get("console.plugin.enable_success"));
        getLogger().info(languageManager.get("console.plugin.server_name", "server_name", serverName));
    }

    @Override
    public void onDisable() {
        if (syncTask != null) {
            if (syncTask instanceof BukkitTask) {
                ((BukkitTask) syncTask).cancel();
            } else if (syncTask instanceof ScheduledTask) {
                ((ScheduledTask) syncTask).cancel();
            }
        }
        if (mssExpansion != null) {
            mssExpansion.unregister();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        // 添加 null 检查以提高健壮性
        if (languageManager != null) {
            getLogger().info(languageManager.get("console.plugin.disable_success"));
        } else {
            getLogger().info("MultiSyncStats plugin has been disabled.");
        }
    }

    /**
     * 处理插件重载 (/mss reload)
     */
    public void onReload() {
        getLogger().info(languageManager.get("console.reload.start"));
        // 停止并等待旧任务完成
        if (syncTask != null) {
            if (syncTask instanceof BukkitTask) {
                ((BukkitTask) syncTask).cancel();
            } else if (syncTask instanceof ScheduledTask) {
                ((ScheduledTask) syncTask).cancel();
            }
        }
        // 重新加载 yml 配置
        reloadConfig();
        if (!loadAndValidateConfig()) {
            // 如果新配置无效，则禁用插件以防万一
            getLogger().severe(languageManager.get("console.reload.invalid_config"));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // 重新加载语言文件
        languageManager.reload();
        // 从数据库重新加载占位符列表 (同步)
        reloadPlaceholdersFromDB();
        getLogger().info(languageManager.get("console.db.placeholders_loaded", "count", String.valueOf(this.syncedPlaceholders.size())));
        // 重新检查和迁移数据表
        boolean migrationPerformedOnReload = databaseManager.migrateAllTables();
        if (migrationPerformedOnReload) {
            getLogger().info(languageManager.get("console.migration.finish"));
        } else {
            getLogger().info(languageManager.get("console.migration.no_migration_needed"));
        }
        // 重启同步任务
        startSyncTask();
        getLogger().info(languageManager.get("console.reload.success"));
    }

    /**
     * 更新配置文件，将新版本插件的默认值添加进来，并保留用户的旧设置.
     */
    private void updateConfig() {
        // 这会从 JAR 中加载默认的 config.yml, 但不会覆盖磁盘上的文件
        saveDefaultConfig();
        // 设置插件配置的“默认值”为 JAR 中的默认配置
        getConfig().options().copyDefaults(true);
        // 保存配置，此时任何缺失的项都会从默认值中复制过来
        saveConfig();
    }

    /**
     * 从数据库同步加载需要同步的占位符列表.
     * 此方法现在不直接记录日志，以便在同步任务中安静地调用.
     */
    public void reloadPlaceholdersFromDB() {
        // 在插件主线程或异步任务中运行数据库查询.
        this.syncedPlaceholders = databaseManager.getSyncedPlaceholders();
    }

    /**
     * 启动周期性的后台数据同步任务.
     */
    private void startSyncTask() {
        // 如果有旧任务，先取消
        if (syncTask != null) {
            if (syncTask instanceof BukkitTask) {
                ((BukkitTask) syncTask).cancel();
            } else if (syncTask instanceof ScheduledTask) {
                ((ScheduledTask) syncTask).cancel();
            }
        }
        // 从配置中读取同步周期 (单位: 秒)
        long syncIntervalSeconds = getConfig().getLong("sync-interval-seconds", 300);

        Runnable syncLogic = () -> {
                // 在每个同步周期开始时，重新从数据库加载占位符列表
                reloadPlaceholdersFromDB();
                
                if (syncedPlaceholders.isEmpty() || Bukkit.getOnlinePlayers().isEmpty()) {
                    return;
                }
                for (Player player : Bukkit.getOnlinePlayers()) {
                    for (String placeholderNameWithPct : syncedPlaceholders) {
                        String placeholderName = placeholderNameWithPct.replace("%", "");
                        getValueFromPAPI(player, placeholderNameWithPct).thenAcceptAsync(value -> {
                            if (value != null) {
                                databaseManager.updateLocalStat(player.getUniqueId(), player.getName(), placeholderName, value);
                            }
                    });
                }
            }
        };
        
        if (isPaperOrFolia) {
            // Paper/Folia: 使用现代异步调度器
            this.syncTask = getServer().getAsyncScheduler().runAtFixedRate(this, (task) -> syncLogic.run(), 60L, syncIntervalSeconds, TimeUnit.SECONDS);
        } else {
            // Spigot/Other: 使用旧版 Bukkit 调度器
            long intervalTicks = syncIntervalSeconds * 20L;
            this.syncTask = new BukkitRunnable() {
                @Override
                public void run() {
                    syncLogic.run();
            }
            }.runTaskTimerAsynchronously(this, 60 * 20L, intervalTicks);
        }
    }

    /**
     * 在主线程安全地获取 PlaceholderAPI 的值.
     * @param player 玩家.
     * @param fullPlaceholder 完整的占位符 (例如, "%player_kills%").
     * @return 一个 CompletableFuture，包含占位符的值，如果解析失败则为 null.
     */
    private CompletableFuture<String> getValueFromPAPI(Player player, String fullPlaceholder) {
        CompletableFuture<String> future = new CompletableFuture<>();

        Runnable getPapiValue = () -> {
                try {
                    String value = PlaceholderAPI.setPlaceholders(player, fullPlaceholder);
                    if (value.equals(fullPlaceholder)) {
                    getLogger().warning(languageManager.get("console.papi.parse_fail", "placeholder", fullPlaceholder, "player_name", player.getName()));
                    future.complete(null);
                } else {
                    future.complete(value);
            }
            } catch (Exception e) {
                getLogger().warning(languageManager.get("console.papi.parse_error", "placeholder", fullPlaceholder));
                future.complete(null);
            }
        };

        if (isPaperOrFolia) {
            // Paper/Folia: 使用玩家调度器，确保在正确的线程上运行
            player.getScheduler().run(this, (task) -> getPapiValue.run(), null);
        } else {
            // Spigot/Other: 回退到在主服务器线程上运行
            new BukkitRunnable() {
                @Override
                public void run() {
                    getPapiValue.run();
                }
            }.runTask(this);
        }

        return future;
    }

    /**
     * 加载并验证 config.yml 中的核心配置.
     * @return 如果配置有效则返回 true.
     */
    private boolean loadAndValidateConfig() {
        serverName = getConfig().getString("server-name", "default-server");

        if (serverName.equals("default-server")) {
            getLogger().warning(languageManager.get("console.config.default_server_name_warning"));
        }
        if (!serverName.matches("[a-zA-Z0-9_\\-]+")) {
            getLogger().severe(languageManager.get("console.config.invalid_server_name", "server_name", serverName));
            return false;
        }

        // ... 可以在这里添加更多配置验证 ...

        return true;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public String getServerName() {
        return serverName;
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }
    
    public Set<String> getSyncedPlaceholders() {
        return syncedPlaceholders;
    }

    public boolean isPaperOrFolia() {
        return isPaperOrFolia;
    }
} 