package com.alexanderp.deltascheduler;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.md_5.bungee.api.ChatColor;

public final class TextUtil {
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private TextUtil() {
    }

    public static String color(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String output = input;
        Matcher matcher = HEX_PATTERN.matcher(output);
        while (matcher.find()) {
            String hex = matcher.group(1);
            output = output.replace("&#" + hex, ChatColor.of("#" + hex).toString());
        }
        return ChatColor.translateAlternateColorCodes('&', output);
    }
}
