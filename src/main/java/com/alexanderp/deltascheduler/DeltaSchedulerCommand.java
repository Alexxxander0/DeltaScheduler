package com.alexanderp.deltascheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;

public final class DeltaSchedulerCommand implements CommandExecutor, TabCompleter {
    private final DeltaSchedulerPlugin plugin;

    public DeltaSchedulerCommand(DeltaSchedulerPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            plugin.send(sender, "usage");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!sub.equals("reload") && !sub.equals("time")) {
            plugin.send(sender, "usage");
            return true;
        }

        if (!sender.hasPermission("deltascheduler.admin")) {
            plugin.send(sender, "no-permission");
            return true;
        }

        if (sub.equals("time")) {
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now(plugin.getSchedulerZone());
            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            String formattedTime = now.format(formatter);
            String zoneName = now.getZone().getId();
            String zoneOffset = now.getOffset().getId();
            String zoneString = zoneName + " (" + zoneOffset + ")";
            plugin.send(sender, "server-time", "{time}", formattedTime, "{zone}", zoneString);
            return true;
        }

        boolean success = plugin.reloadPlugin();
        if (!success) {
            plugin.send(sender, "reload-failed");
            return true;
        }

        plugin.send(sender, "reload-success");
        DeltaSchedulerPlugin.SchedulerState state = plugin.getSchedulerState();
        if (state == DeltaSchedulerPlugin.SchedulerState.RUNNING) {
            plugin.send(sender, "next-run", "{time}", plugin.getCountdownDisplay());
        } else if (state == DeltaSchedulerPlugin.SchedulerState.NO_TIMES) {
            plugin.send(sender, "scheduler-no-times");
        } else if (state == DeltaSchedulerPlugin.SchedulerState.NO_COMMANDS) {
            plugin.send(sender, "scheduler-no-commands");
        } else {
            plugin.send(sender, "reload-failed");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }

        List<String> options = new ArrayList<>();
        options.add("reload");
        options.add("time");
        List<String> matches = new ArrayList<>();
        StringUtil.copyPartialMatches(args[0], options, matches);
        return matches;
    }
}
