package io.github.freecorebank.lang;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Lang {
    private static final String DEFAULT_LANGUAGE = "zh_CN";

    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private YamlConfiguration messages;

    private static final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer LEGACY_SERIALIZER = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final java.util.regex.Pattern LEGACY_CODE = java.util.regex.Pattern.compile(
            "(?i)&([0-9a-fk-or]|#[0-9a-f]{6}|x[0-9a-f](?:[0-9a-f]{5}))");

    private static String convertLegacy(String input) {
        if (input == null) return null;
        java.util.regex.Matcher matcher = LEGACY_CODE.matcher(input);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            String code = matcher.group(1);
            if (code.length() == 7 && Character.toLowerCase(code.charAt(0)) == 'x') {
                StringBuilder hex = new StringBuilder("&#");
                for (int index = 1; index < 7; index++) hex.append(code.charAt(index));
                code = hex.toString();
            }
            matcher.appendReplacement(output, java.util.regex.Matcher.quoteReplacement("\u00a7" + code));
        }
        matcher.appendTail(output);
        return output.toString();
    }


    private static boolean hasLegacy(String input) {
        return input != null && LEGACY_CODE.matcher(input).find();
    }

    private static boolean hasMiniMessageTag(String input) {
        return input != null && input.matches(".*<[a-zA-Z#/][^>]*>.*");
    }

    private static boolean hasLegacyOnlyLegacyTags(String input) {
        return hasLegacy(input) && !hasMiniMessageTag(input);
    }

    private static String replaceLegacyPlaceholders(String input, String... placeholders) {
        String rendered = input;
        for (int index = 0; index + 1 < placeholders.length; index += 2) {
            rendered = rendered.replace("<" + placeholders[index] + ">", placeholders[index + 1]);
        }
        return rendered;
    }
    public Lang(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File directory = new File(plugin.getDataFolder(), "lang");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建语言文件夹: " + directory);
        }
        File defaultFile = new File(directory, DEFAULT_LANGUAGE + ".yml");
        if (!defaultFile.exists()) {
            plugin.saveResource("lang/" + DEFAULT_LANGUAGE + ".yml", false);
        }

        String selected = plugin.getConfig().getString("settings.language", DEFAULT_LANGUAGE);
        File selectedFile = new File(directory, selected + ".yml");
        if (!selectedFile.isFile()) {
            plugin.getLogger().warning("语言文件 " + selectedFile.getName() + " 不存在，已使用 "
                    + DEFAULT_LANGUAGE + ".yml");
            selectedFile = defaultFile;
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(selectedFile);
        try (InputStream stream = plugin.getResource("lang/" + DEFAULT_LANGUAGE + ".yml")) {
            if (stream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(stream, StandardCharsets.UTF_8));
                loaded.setDefaults(defaults);
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("读取内置语言默认值失败: " + exception.getMessage());
        }
        this.messages = loaded;
    }

    public Component text(String path, String... placeholders) {
        String template = messages.getString(path, "<red>缺少语言项: " + path + "</red>");
        try {
            return hasLegacyOnlyLegacyTags(template) ? Component.empty().decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE).append(LEGACY_SERIALIZER.deserialize(replaceLegacyPlaceholders(template, placeholders))) : miniMessage.deserialize(template, resolver(placeholders))
                    .decoration(TextDecoration.ITALIC, false);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("语言项格式错误 " + path + ": " + exception.getMessage());
            return Component.text("语言项格式错误: " + path, NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false);
        }
    }

    public List<Component> list(String path, String... placeholders) {
        List<Component> result = new ArrayList<>();
        for (String line : messages.getStringList(path)) {
            try {
                result.add(hasLegacyOnlyLegacyTags(line) ? Component.empty().decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE).append(LEGACY_SERIALIZER.deserialize(replaceLegacyPlaceholders(line, placeholders))) : miniMessage.deserialize(line, resolver(placeholders))
                        .decoration(TextDecoration.ITALIC, false));
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("语言项格式错误 " + path + ": " + exception.getMessage());
                result.add(Component.text("语言项格式错误: " + path, NamedTextColor.RED));
            }
        }
        return result;
    }

    public String plain(String path) {
        return messages.getString(path, "");
    }

    public Component message(String path, String... placeholders) {
        return text("prefix").append(text(path, placeholders));
    }

    public void send(CommandSender sender, String path, String... placeholders) {
        sender.sendMessage(message(path, placeholders));
    }

    private TagResolver resolver(String... placeholders) {
        if (placeholders.length % 2 != 0) {
            throw new IllegalArgumentException("语言占位符必须成对提供");
        }
        TagResolver.Builder builder = TagResolver.builder();
        for (int index = 0; index < placeholders.length; index += 2) {
            builder.resolver(Placeholder.unparsed(placeholders[index], placeholders[index + 1]));
        }
        return builder.build();
    }
}
