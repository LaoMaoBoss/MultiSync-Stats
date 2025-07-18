package com.myname.multisyncstats;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import me.clip.placeholderapi.PlaceholderAPI;

public class MSSCommand implements CommandExecutor, TabCompleter {

    private final MultiSyncStats plugin;
    private final DatabaseManager dbManager;
    private final LanguageManager lang;

    public MSSCommand(MultiSyncStats plugin) {
        this.plugin = plugin;
        this.dbManager = plugin.getDatabaseManager();
        this.lang = plugin.getLanguageManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "add":
                handleAdd(sender, args);
                break;
            case "remove":
                handleRemove(sender, args);
                break;
            case "list":
                handleList(sender);
                break;
            case "reload":
                // 让主插件处理重载逻辑
                plugin.onReload();
                sender.sendMessage(lang.get("command.reload.success"));
                break;
            default:
                sendHelp(sender);
                break;
        }
        return true;
    }

    private void handleAdd(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(lang.get("command.add.usage"));
            return;
        }
        String placeholder = args[1];

        // 统一转换为小写，以避免大小写问题
        String normalizedPlaceholder = placeholder.toLowerCase();

        // 检查占位符是否已存在
        if (plugin.getSyncedPlaceholders().contains(normalizedPlaceholder)) {
            sender.sendMessage(lang.get("command.add.already_exists", "placeholder", normalizedPlaceholder));
            return;
        }

        // --- 前置检查 ---
        Player testPlayer = null;
        if (sender instanceof Player) {
            testPlayer = (Player) sender;
        } else if (!Bukkit.getOnlinePlayers().isEmpty()) {
            testPlayer = Bukkit.getOnlinePlayers().iterator().next();
        }

        // 如果没有可用的玩家来进行测试，我们无法进行验证
        if (testPlayer == null) {
            sender.sendMessage(lang.get("command.add.validation.no_player"));
            addPlaceholderToDb(sender, normalizedPlaceholder);
            return;
        }

        // 在主线程上解析占位符
        String value = PlaceholderAPI.setPlaceholders(testPlayer, normalizedPlaceholder);

        // 检查占位符是否被成功解析
        if (value.equals(normalizedPlaceholder)) {
            sender.sendMessage(lang.get("command.add.validation.fail", "placeholder", normalizedPlaceholder));
            return;
        }

        // 检查返回值是否为数值
        try {
            // 替换掉千位分隔符(,)以正确解析
            Double.parseDouble(value.replace(",", ""));
            
            // 验证通过，发送提示信息并添加到数据库
            sender.sendMessage(lang.get("command.add.validation.success", "value", value));
            addPlaceholderToDb(sender, normalizedPlaceholder);

        } catch (NumberFormatException e) {
            sender.sendMessage(lang.get("command.add.validation.not_numeric", "placeholder", normalizedPlaceholder, "value", value));
        }
    }

    private void addPlaceholderToDb(CommandSender sender, String placeholder) {
        Runnable dbTask = () -> {
            boolean success = dbManager.addPlaceholder(placeholder);
            Runnable callback = () -> {
                if (success) {
                    sender.sendMessage(lang.get("command.add.db_success", "placeholder", placeholder));
                    plugin.reloadPlaceholdersFromDB(); // 更新缓存
                } else {
                    sender.sendMessage(lang.get("command.add.db_fail"));
                }
            };
            // 根据服务器类型，在主线程或合适的区域线程上执行回调
            if (plugin.isPaperOrFolia()) {
                if (sender instanceof Player) {
                    ((Player) sender).getScheduler().run(plugin, task -> callback.run(), null);
                } else {
                    plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> callback.run());
                }
            } else {
                plugin.getServer().getScheduler().runTask(plugin, callback);
            }
        };

        // 根据服务器类型，在后台线程执行数据库操作
        if (plugin.isPaperOrFolia()) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> dbTask.run());
        } else {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, dbTask);
        }
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(lang.get("command.remove.usage"));
            return;
        }
        String placeholder = args[1];

        Runnable dbTask = () -> {
            boolean success = dbManager.removePlaceholder(placeholder);
            Runnable callback = () -> {
                if (success) {
                    sender.sendMessage(lang.get("command.remove.success", "placeholder", placeholder));
                    sender.sendMessage(lang.get("command.remove.data_not_deleted_notice"));
                    plugin.reloadPlaceholdersFromDB(); // 更新缓存
                } else {
                    sender.sendMessage(lang.get("command.remove.not_exists"));
                }
            };
            // 根据服务器类型，在主线程或合适的区域线程上执行回调
            if (plugin.isPaperOrFolia()) {
                if (sender instanceof Player) {
                    ((Player) sender).getScheduler().run(plugin, task -> callback.run(), null);
                } else {
                    plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> callback.run());
                }
            } else {
                plugin.getServer().getScheduler().runTask(plugin, callback);
            }
        };

        // 根据服务器类型，在后台线程执行数据库操作
        if (plugin.isPaperOrFolia()) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> dbTask.run());
        } else {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, dbTask);
        }
    }

    private void handleList(CommandSender sender) {
        Runnable dbTask = () -> {
            Set<String> placeholders = dbManager.getSyncedPlaceholders();
            Runnable callback = () -> {
                if (placeholders.isEmpty()) {
                    sender.sendMessage(lang.get("command.list.empty"));
                    return;
                }
                sender.sendMessage(lang.get("command.list.header"));
                placeholders.forEach(p -> sender.sendMessage(lang.get("command.list.item", "placeholder", p)));
            };

            // 根据服务器类型，在主线程或合适的区域线程上执行回调
            if (plugin.isPaperOrFolia()) {
                if (sender instanceof Player) {
                    ((Player) sender).getScheduler().run(plugin, task -> callback.run(), null);
                } else {
                    plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> callback.run());
                }
            } else {
                plugin.getServer().getScheduler().runTask(plugin, callback);
            }
        };
        // 根据服务器类型，在后台线程执行数据库操作
        if (plugin.isPaperOrFolia()) {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> dbTask.run());
        } else {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, dbTask);
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(lang.get("command.help.header", "version", plugin.getDescription().getVersion()));
        sender.sendMessage(lang.get("command.help.add"));
        sender.sendMessage(lang.get("command.help.remove"));
        sender.sendMessage(lang.get("command.help.list"));
        sender.sendMessage(lang.get("command.help.reload"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return Arrays.asList("add", "remove", "list", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            // Tab补全直接使用主插件的缓存，避免数据库查询
            return plugin.getSyncedPlaceholders().stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return new ArrayList<>();
    }
} 