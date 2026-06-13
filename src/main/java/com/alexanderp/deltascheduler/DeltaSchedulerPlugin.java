package com.alexanderp.deltascheduler;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class DeltaSchedulerPlugin extends JavaPlugin {
    private static final String PLACEHOLDER_TIME_LEFT = "time_left";
    private static final String PLACEHOLDER_TIME_DAYS = "time_days";
    private static final String PLACEHOLDER_TIME_HOURS = "time_hours";
    private static final String PLACEHOLDER_TIME_MINUTES = "time_minutes";
    private static final String PLACEHOLDER_TIME_SECONDS = "time_seconds";
    private static final DateTimeFormatter[] TIME_FORMATTERS = {
        DateTimeFormatter.ofPattern("H:mm:ss", Locale.ROOT),
        DateTimeFormatter.ofPattern("H:mm", Locale.ROOT)
    };

    private volatile FileConfiguration lang;
    private volatile BukkitTask scheduledTask;
    private volatile long nextExecutionEpochMs = -1L;
    private volatile List<ScheduleEntry> schedules = List.of();
    private volatile SchedulerState schedulerState = SchedulerState.DISABLED;
    private final Map<String, Long> placeholderIntervalMs = new ConcurrentHashMap<>();
    private final Map<String, CachedPlaceholderValue> placeholderCache = new ConcurrentHashMap<>();
    private final ZoneId schedulerZone = ZoneId.systemDefault();
    private DeltaSchedulerPlaceholders placeholders;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadLang();

        CommandExecutor command = new DeltaSchedulerCommand(this);
        if (getCommand("deltascheduler") != null) {
            getCommand("deltascheduler").setExecutor(command);
            getCommand("deltascheduler").setTabCompleter((DeltaSchedulerCommand) command);
        }

        if (!reloadPlugin()) {
            getLogger().severe("DeltaScheduler could not start. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        registerPlaceholders();
        getLogger().info("DeltaScheduler enabled.");
    }

    @Override
    public void onDisable() {
        cancelScheduledTask();
        if (placeholders != null) {
            try {
                placeholders.unregister();
            } catch (Throwable ignored) {
            }
            placeholders = null;
        }
    }

    public synchronized boolean reloadPlugin() {
        try {
            reloadConfig();
            loadLang();

            List<ScheduleEntry> parsedSchedules = new ArrayList<>();
            ConfigurationSection timesSection = getConfig().getConfigurationSection("scheduler.times");
            if (timesSection != null) {
                for (String key : timesSection.getKeys(false)) {
                    List<String> list = timesSection.getStringList(key);
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    String rawTimes = list.get(0);
                    List<String> rawCommands = list.subList(1, list.size());

                    // Parse times
                    String[] splitTimes = rawTimes.split(",");
                    Set<LocalTime> times = new TreeSet<>();
                    for (String rawTime : splitTimes) {
                        String candidate = rawTime.trim();
                        if ((candidate.startsWith("\"") && candidate.endsWith("\"")) || (candidate.startsWith("'") && candidate.endsWith("'"))) {
                            candidate = candidate.substring(1, candidate.length() - 1).trim();
                        }
                        if (candidate.isEmpty()) {
                            continue;
                        }
                        LocalTime parsed = parseTime(candidate);
                        if (parsed != null) {
                            times.add(parsed);
                        } else {
                            getLogger().warning("Invalid scheduler time '" + candidate + "' in schedule '" + key + "'. Use HH:mm or HH:mm:ss.");
                        }
                    }

                    // Parse commands
                    List<String> commands = new ArrayList<>();
                    for (String rawCommand : rawCommands) {
                        if (rawCommand == null) {
                            continue;
                        }
                        String cmd = rawCommand.trim();
                        if (cmd.toLowerCase(Locale.ROOT).startsWith("[command]")) {
                            cmd = cmd.substring("[command]".length()).trim();
                        }
                        if ((cmd.startsWith("\"") && cmd.endsWith("\"")) || (cmd.startsWith("'") && cmd.endsWith("'"))) {
                            cmd = cmd.substring(1, cmd.length() - 1).trim();
                        }
                        if (!cmd.isEmpty()) {
                            commands.add(cmd);
                        }
                    }

                    if (!times.isEmpty() && !commands.isEmpty()) {
                        parsedSchedules.add(new ScheduleEntry(key, List.copyOf(times), commands));
                    } else {
                        if (times.isEmpty()) {
                            getLogger().warning("Schedule '" + key + "' has no valid times.");
                        }
                        if (commands.isEmpty()) {
                            getLogger().warning("Schedule '" + key + "' has no valid commands.");
                        }
                    }
                }
            }

            schedules = parsedSchedules;

            cancelScheduledTask();
            configurePlaceholderIntervals();
            clearPlaceholderCache();

            if (schedules.isEmpty()) {
                schedulerState = SchedulerState.NO_TIMES;
                getLogger().warning("No valid schedules configured.");
                return true;
            }

            schedulerState = SchedulerState.RUNNING;
            for (ScheduleEntry schedule : schedules) {
                schedule.scheduleNextExecution(Instant.now(), schedulerZone);
            }
            recalculateNextExecutionEpochMs();
            startScheduleMonitor();
            getLogger().info(
                "Scheduler armed with "
                    + schedules.size()
                    + " schedule(s), next run in "
                    + formatDuration(getMillisUntilNextExecution())
                    + "."
            );
            return true;
        } catch (Exception exception) {
            schedules = List.of();
            schedulerState = SchedulerState.DISABLED;
            cancelScheduledTask();
            clearPlaceholderCache();
            getLogger().log(Level.SEVERE, "Could not reload DeltaScheduler.", exception);
            return false;
        }
    }

    public SchedulerState getSchedulerState() {
        return schedulerState;
    }

    public ZoneId getSchedulerZone() {
        return schedulerZone;
    }

    public long getMillisUntilNextExecution() {
        if (schedulerState != SchedulerState.RUNNING || nextExecutionEpochMs < 0L) {
            return -1L;
        }
        return Math.max(0L, nextExecutionEpochMs - System.currentTimeMillis());
    }

    public String getCountdownDisplay() {
        return buildCountdownData(getMillisUntilNextExecution()).timeLeft();
    }

    public String getCountdownDaysDisplay() {
        return String.valueOf(buildCountdownData(getMillisUntilNextExecution()).days());
    }

    public String getCountdownHoursDisplay() {
        return String.valueOf(buildCountdownData(getMillisUntilNextExecution()).hours());
    }

    public String getCountdownMinutesDisplay() {
        return String.valueOf(buildCountdownData(getMillisUntilNextExecution()).minutes());
    }

    public String getCountdownSecondsDisplay() {
        return String.valueOf(buildCountdownData(getMillisUntilNextExecution()).seconds());
    }

    public String resolvePlaceholderValue(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }

        String normalized = key.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        CachedPlaceholderValue cached = placeholderCache.get(normalized);
        if (cached != null && cached.expiresAtMs() >= now) {
            return cached.value();
        }

        CountdownData countdown = buildCountdownData(getMillisUntilNextExecution());
        String value = switch (normalized) {
            case PLACEHOLDER_TIME_LEFT -> countdown.timeLeft();
            case PLACEHOLDER_TIME_DAYS -> String.valueOf(countdown.days());
            case PLACEHOLDER_TIME_HOURS -> String.valueOf(countdown.hours());
            case PLACEHOLDER_TIME_MINUTES -> String.valueOf(countdown.minutes());
            case PLACEHOLDER_TIME_SECONDS -> String.valueOf(countdown.seconds());
            default -> null;
        };

        if (value == null) {
            return null;
        }

        long intervalMs = placeholderIntervalMs.getOrDefault(normalized, 1000L);
        if (intervalMs > 0L) {
            placeholderCache.put(normalized, new CachedPlaceholderValue(value, now + intervalMs));
        }
        return value;
    }

    public void send(CommandSender sender, String key, String... replacements) {
        sender.sendMessage(message(key, replacements));
    }

    public String message(String key, String... replacements) {
        String prefix = lang == null ? "" : lang.getString("prefix", "");
        String value = lang == null ? key : lang.getString(key, key);
        if (value == null) {
            value = "";
        }

        for (int index = 0; index + 1 < replacements.length; index += 2) {
            value = value.replace(replacements[index], replacements[index + 1]);
        }

        String output = value;
        if (!"prefix".equals(key) && prefix != null && !prefix.isBlank()) {
            output = prefix + " " + value;
        }
        return TextUtil.color(output);
    }

    public String formatDuration(long milliseconds) {
        return buildCountdownData(milliseconds).timeLeft();
    }

    private void executeSchedule(ScheduleEntry schedule) {
        for (String command : schedule.getCommands()) {
            try {
                boolean dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                if (!dispatched) {
                    getLogger().warning("Console command returned false for schedule '" + schedule.getName() + "': /" + command);
                }
            } catch (Exception exception) {
                getLogger().log(Level.SEVERE, "Could not execute scheduled command for schedule '" + schedule.getName() + "': /" + command, exception);
            }
        }
    }

    private synchronized void cancelScheduledTask() {
        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
        }
        nextExecutionEpochMs = -1L;
        clearPlaceholderCache();
    }

    private void loadLang() {
        File langFile = new File(getDataFolder(), "lang.yml");
        if (!langFile.exists()) {
            saveResource("lang.yml", false);
        }
        lang = YamlConfiguration.loadConfiguration(langFile);
    }

    private void registerPlaceholders() {
        Plugin papi = getServer().getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null) {
            getLogger().info("PlaceholderAPI not found. Placeholder support disabled.");
            return;
        }

        if (placeholders != null) {
            return;
        }

        DeltaSchedulerPlaceholders expansion = new DeltaSchedulerPlaceholders(this);
        boolean registered = expansion.register();
        if (!registered) {
            getLogger().warning("Failed to register PlaceholderAPI expansion 'deltascheduler'.");
            return;
        }

        placeholders = expansion;
        getLogger().info("Registered PlaceholderAPI expansion: deltascheduler");
    }

    private synchronized void startScheduleMonitor() {
        if (scheduledTask != null) {
            return;
        }

        scheduledTask = getServer().getScheduler().runTaskTimer(this, this::checkForDueExecutions, 1L, 1L);
    }

    private synchronized void checkForDueExecutions() {
        if (schedulerState != SchedulerState.RUNNING) {
            return;
        }

        if (schedules.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean executedAny = false;

        for (ScheduleEntry schedule : schedules) {
            long scheduledEpochMs = schedule.getNextExecutionEpochMs();
            if (scheduledEpochMs < 0L) {
                schedule.scheduleNextExecution(Instant.now(), schedulerZone);
                scheduledEpochMs = schedule.getNextExecutionEpochMs();
                executedAny = true;
            }

            if (scheduledEpochMs >= 0L && now >= scheduledEpochMs) {
                executeSchedule(schedule);
                schedule.scheduleNextExecution(Instant.now().plusMillis(1L), schedulerZone);
                executedAny = true;
            }
        }

        if (executedAny) {
            recalculateNextExecutionEpochMs();
            clearPlaceholderCache();
        }
    }

    private void recalculateNextExecutionEpochMs() {
        long min = -1L;
        for (ScheduleEntry schedule : schedules) {
            long epoch = schedule.getNextExecutionEpochMs();
            if (epoch >= 0L) {
                if (min < 0L || epoch < min) {
                    min = epoch;
                }
            }
        }
        nextExecutionEpochMs = min;
    }

    private LocalTime parseTime(String value) {
        for (DateTimeFormatter formatter : TIME_FORMATTERS) {
            try {
                return LocalTime.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private void configurePlaceholderIntervals() {
        placeholderIntervalMs.clear();
        placeholderIntervalMs.put(PLACEHOLDER_TIME_LEFT, getPlaceholderIntervalMs(PLACEHOLDER_TIME_LEFT, 1));
        placeholderIntervalMs.put(PLACEHOLDER_TIME_DAYS, getPlaceholderIntervalMs(PLACEHOLDER_TIME_DAYS, 1));
        placeholderIntervalMs.put(PLACEHOLDER_TIME_HOURS, getPlaceholderIntervalMs(PLACEHOLDER_TIME_HOURS, 1));
        placeholderIntervalMs.put(PLACEHOLDER_TIME_MINUTES, getPlaceholderIntervalMs(PLACEHOLDER_TIME_MINUTES, 1));
        placeholderIntervalMs.put(PLACEHOLDER_TIME_SECONDS, getPlaceholderIntervalMs(PLACEHOLDER_TIME_SECONDS, 1));
    }

    private long getPlaceholderIntervalMs(String key, int defaultSeconds) {
        ConfigurationSection section = getConfig().getConfigurationSection("placeholders.update-intervals");
        String fullToken = toFullPlaceholder(key);
        int seconds = defaultSeconds;
        if (section != null && (section.isInt(fullToken) || section.isString(fullToken))) {
            seconds = section.getInt(fullToken, defaultSeconds);
        }
        return Math.max(0L, seconds) * 1000L;
    }

    private String toFullPlaceholder(String key) {
        return "%deltascheduler_" + key + "%";
    }

    private CountdownData buildCountdownData(long remainingMs) {
        if (remainingMs < 0L) {
            return CountdownData.zero();
        }

        long totalSeconds = Math.max(0L, (remainingMs + 999L) / 1000L);
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        List<String> parts = new ArrayList<>(4);
        if (days > 0L) {
            parts.add(days + "d");
        }
        if (hours > 0L) {
            parts.add(hours + "h");
        }
        if (minutes > 0L) {
            parts.add(minutes + "m");
        }
        if (seconds > 0L || parts.isEmpty()) {
            parts.add(seconds + "s");
        }

        return new CountdownData(String.join(" ", parts), days, hours, minutes, seconds);
    }

    private void clearPlaceholderCache() {
        placeholderCache.clear();
    }

    private record CountdownData(String timeLeft, long days, long hours, long minutes, long seconds) {
        private static CountdownData zero() {
            return new CountdownData("0s", 0L, 0L, 0L, 0L);
        }
    }

    private record CachedPlaceholderValue(String value, long expiresAtMs) {
    }

    public enum SchedulerState {
        RUNNING("running"),
        DISABLED("disabled"),
        NO_COMMANDS("no-commands"),
        NO_TIMES("no-times");

        private final String key;

        SchedulerState(String key) {
            this.key = key;
        }

        public String getKey() {
            return key;
        }
    }

    public static final class ScheduleEntry {
        private final String name;
        private final List<LocalTime> times;
        private final List<String> commands;
        private long nextExecutionEpochMs = -1L;

        public ScheduleEntry(String name, List<LocalTime> times, List<String> commands) {
            this.name = name;
            this.times = times;
            this.commands = commands;
        }

        public String getName() {
            return name;
        }

        public List<LocalTime> getTimes() {
            return times;
        }

        public List<String> getCommands() {
            return commands;
        }

        public long getNextExecutionEpochMs() {
            return nextExecutionEpochMs;
        }

        public void scheduleNextExecution(Instant referenceInstant, ZoneId schedulerZone) {
            ZonedDateTime nextRun = computeNextRunForSchedule(referenceInstant, schedulerZone);
            this.nextExecutionEpochMs = nextRun.toInstant().toEpochMilli();
        }

        private ZonedDateTime computeNextRunForSchedule(Instant referenceInstant, ZoneId schedulerZone) {
            ZonedDateTime now = referenceInstant.atZone(schedulerZone);
            LocalDate today = now.toLocalDate();

            for (LocalTime scheduledTime : times) {
                ZonedDateTime candidate = today.atTime(scheduledTime).atZone(schedulerZone);
                if (!candidate.isBefore(now)) {
                    return candidate;
                }
            }

            return today.plusDays(1).atTime(times.get(0)).atZone(schedulerZone);
        }
    }
}
