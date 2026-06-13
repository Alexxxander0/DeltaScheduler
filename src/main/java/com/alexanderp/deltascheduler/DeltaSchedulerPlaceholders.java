package com.alexanderp.deltascheduler;

import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

public final class DeltaSchedulerPlaceholders extends PlaceholderExpansion {
    private final DeltaSchedulerPlugin plugin;

    public DeltaSchedulerPlaceholders(DeltaSchedulerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "deltascheduler";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (params == null) {
            return "";
        }

        return plugin.resolvePlaceholderValue(params.trim().toLowerCase(Locale.ROOT));
    }
}
