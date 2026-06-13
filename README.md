# DeltaScheduler

[![Platform](https://img.shields.io/badge/Platform-Paper%20%2F%20Spigot-green.svg)](https://papermc.io)
[![Java Version](https://img.shields.io/badge/Java-17%2B-blue.svg)](https://www.oracle.com/java/technologies/downloads/)
[![API](https://img.shields.io/badge/API-1.21-orange.svg)](https://papermc.io)

A highly efficient and customizable Paper/Spigot Minecraft plugin that executes console commands on daily schedules. Designed for performance and ease of use, with full PlaceholderAPI integration and configurable timezone/cache structures.

---

## 🌟 Features
* **Individual Schedules:** Each schedule is completely independent, having its own execution times and commands.
* **Flexible Time Formats:** Supports both `HH:mm` (e.g. `14:30`) and `HH:mm:ss` (e.g. `23:59:59`) time formats.
* **PlaceholderAPI Integration:** Includes placeholders showing the remaining time to the next execution (days, hours, minutes, seconds).
* **Configurable Placeholder Cache:** Customize update intervals for placeholders in `config.yml` to prevent CPU overhead.
* **Fully Translatable:** All messages can be customized inside the `lang.yml` translation file.

---

## 📁 Configuration

### `config.yml`
```yaml
scheduler:
  # Daily execution times. Accepted formats: HH:mm or HH:mm:ss
  # Each named schedule contains its trigger times (comma-separated first element)
  # followed by the command(s) to execute.
  times:
    africa_keys:
      - "00:00, 01:00, 02:00, 03:00, 04:00, 05:00, 06:00, 07:00, 08:00, 09:00, 10:00, 11:00, 12:00, 13:00, 14:00, 15:00, 16:00, 17:00, 18:00, 19:00, 20:00, 21:00, 22:00, 23:00"
      - "[command] crates key giveall africa_key 1"

placeholders:
  # Update intervals (seconds) for each placeholder cache.
  # Format: %placeholder%: delay in seconds
  # Set to 0 to update on every request (useful for real-time seconds).
  update-intervals:
    "%deltascheduler_time_left%": 0
    "%deltascheduler_time_days%": 1
    "%deltascheduler_time_hours%": 1
    "%deltascheduler_time_minutes%": 1
    "%deltascheduler_time_seconds%": 1
```

### `lang.yml`
```yaml
prefix: ""
usage: "&eUsage: /deltascheduler <reload|time>"
no-permission: "&cYou do not have permission."
reload-success: "&aReloaded successfully."
reload-failed: "&cReload could not be completed. Check console for details."
next-run: "&7Next scheduled execution in &e{time}&7."
scheduler-no-commands: "&eScheduler has no commands configured."
scheduler-no-times: "&eScheduler has no valid execution times configured."
server-time: "&7Current server time: &e{time} &7(Zone: &e{zone}&7)"
```

---

## 🛠️ Commands & Permissions

| Command | Permission | Description |
| :--- | :--- | :--- |
| `/deltascheduler reload` / `/ds reload` | `deltascheduler.admin` | Reloads the configuration and language files. |
| `/deltascheduler time` / `/ds time` | `deltascheduler.admin` | Displays the current server time and system timezone. |

---

## 📊 Placeholders (PlaceholderAPI)

These placeholders return the remaining time until the next nearest execution among **all** active schedules:

| Placeholder | Output Example | Description |
| :--- | :--- | :--- |
| `%deltascheduler_time_left%` | `1d 4h 32m 15s` | Complete countdown string. |
| `%deltascheduler_time_days%` | `1` | Days remaining. |
| `%deltascheduler_time_hours%` | `4` | Hours remaining. |
| `%deltascheduler_time_minutes%` | `32` | Minutes remaining. |
| `%deltascheduler_time_seconds%` | `15` | Seconds remaining. |

---

## ⚙️ How to Build

To build the plugin from source, you must have **Java Development Kit (JDK) 17** and **Maven** installed.

1. Clone or download the repository.
2. Open terminal in the directory and run:
   ```bash
   mvn clean package
   ```
3. The built JAR file will be located inside the `target/` directory:
   `target/DeltaScheduler-1.21.11.jar`
