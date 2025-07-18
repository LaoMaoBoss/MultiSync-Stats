package com.myname.multisyncstats;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

/**
 * 负责加载和管理插件的语言文件.
 */
public class LanguageManager {

    private final MultiSyncStats plugin;
    private FileConfiguration langConfig;
    private String selectedLang;
    private final List<String> knownLanguages = Arrays.asList("zh_CN", "en_US");

    /**
     * 构造函数.
     * @param plugin 插件主类实例.
     */
    public LanguageManager(MultiSyncStats plugin) {
        this.plugin = plugin;
        this.selectedLang = plugin.getConfig().getString("language", "zh_CN");
    }
    
    /**
     * 重新加载语言文件，以应用配置更改.
     */
    public void reload() {
        this.selectedLang = plugin.getConfig().getString("language", "zh_CN");
        loadSelectedLanguage();
    }
    
    /**
     * 检查所有已知的语言文件，如果不存在则创建，如果缺少条目则补全.
     */
    public void updateAllLanguageFiles() {
        for (String langName : knownLanguages) {
            File langFile = new File(plugin.getDataFolder(), "lang" + File.separator + langName + ".yml");
            
            // 从 JAR 加载默认语言文件作为“模板”
            YamlConfiguration defaultLangConfig;
            try (InputStream defLangStream = plugin.getResource("lang/" + langName + ".yml")) {
                if (defLangStream == null) {
                    plugin.getLogger().warning("在 JAR 中找不到默认语言文件: " + langName + ".yml，跳过更新。");
                    continue;
                }
                defaultLangConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defLangStream, StandardCharsets.UTF_8));
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "无法加载默认语言文件: " + langName, e);
                continue;
            }

            // 加载磁盘上的语言文件，如果不存在则使用空的配置
            FileConfiguration userLangConfig = YamlConfiguration.loadConfiguration(langFile);
            
            // 检查并补全缺失的项
            boolean updated = false;
            for (String key : defaultLangConfig.getKeys(true)) {
                if (!userLangConfig.isSet(key)) {
                    userLangConfig.set(key, defaultLangConfig.get(key));
                    updated = true;
                }
            }
            
            // 如果文件被修改过或首次创建，就保存它
            if (updated || !langFile.exists()) {
                try {
                    userLangConfig.save(langFile);
                } catch (IOException e) {
                    plugin.getLogger().log(Level.SEVERE, "无法保存语言文件: " + langFile.getName(), e);
                }
            }
        }
    }
    
    /**
     * 将用户在 config.yml 中选择的语言加载到内存中.
     */
    public void loadSelectedLanguage() {
        File langFile = new File(plugin.getDataFolder(), "lang" + File.separator + selectedLang + ".yml");
        if (!langFile.exists()) {
            plugin.getLogger().severe("严重错误: 选定的语言文件 " + selectedLang + ".yml 不存在! 将使用默认值。");
            langConfig = new YamlConfiguration();
            return;
        }
        langConfig = YamlConfiguration.loadConfiguration(langFile);
        
        // 再次加载默认值作为备用，防止文件损坏或为空
        try (InputStream defLangStream = plugin.getResource("lang/" + selectedLang + ".yml")) {
            if (defLangStream != null) {
                langConfig.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defLangStream, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
             plugin.getLogger().log(Level.SEVERE, "无法加载默认语言文件作为备用: " + selectedLang, e);
        }
    }

    /**
     * 根据路径获取翻译后的字符串.
     * @param path 语言文件中的路径 (例如 "command.add.success").
     * @return 经过颜色代码转换的字符串.
     */
    public String get(String path) {
        String message = langConfig.getString(path, "&c语言文件错误: 未找到路径 '" + path + "'");
        return ChatColor.translateAlternateColorCodes('&', message);
    }
    
    /**
     * 根据路径获取翻译后的字符串，并替换其中的占位符.
     * @param path 语言文件中的路径.
     * @param replacements 要替换的占位符和值，成对出现 (例如 "placeholder", a, "value", b).
     * @return 格式化后的字符串.
     */
    public String get(String path, String... replacements) {
        String message = get(path);
        for (int i = 0; i < replacements.length; i += 2) {
            if (i + 1 < replacements.length) {
                String key = "{" + replacements[i] + "}";
                String value = replacements[i+1];
                message = message.replace(key, value);
            }
        }
        return message;
    }
} 