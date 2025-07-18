package com.myname.multisyncstats.placeholder;

import com.myname.multisyncstats.MultiSyncStats;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * MultiSyncStats 的主 PlaceholderAPI 扩展.
 * 负责处理所有格式为 %mss_<placeholder>% 的占位符.
 */
public class MssExpansion extends PlaceholderExpansion {

    private final MultiSyncStats plugin;

    public MssExpansion(MultiSyncStats plugin) {
        this.plugin = plugin;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        // params 是占位符的参数部分 (例如, "main" 来自 %mss_main%)
        if (params.isEmpty()) {
            return null;
        }

        // 构建原始占位符的格式, 用于在已注册列表中检查
        // 注意：我们的数据库和同步列表存储的是带 % 的格式，如 "%main%"
        String originalPlaceholder = "%" + params + "%";

        // 检查这个原始占位符是否在通过 /mss add 添加的列表中
        if (plugin.getSyncedPlaceholders().contains(originalPlaceholder)) {
            // 如果存在, 就委托给 DatabaseManager 来获取真实的同步数据.
            // 我们传递的是不带 % 的参数, 如 "main"
            return plugin.getDatabaseManager().getSyncedData(player, params);
        }

        // 如果原始占位符未注册，则返回 null.
        return null;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "mss";
    }

    @Override
    public @NotNull String getAuthor() {
        return "YourName";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }
} 