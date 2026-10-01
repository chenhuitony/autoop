package cn.mod.autoop;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** {@code /autoop} 命令与补全。 */
public final class AutoOpCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of(
            "status", "reload", "toggle", "list", "add", "remove", "clear", "grant");

    private final AutoOpPlugin plugin;

    public AutoOpCommand(AutoOpPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            if (!sender.hasPermission(AutoOpPlugin.PERMISSION_LIST)) {
                return deny(sender);
            }
            showStatus(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> {
                showHelp(sender);
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission(AutoOpPlugin.PERMISSION_RELOAD)) {
                    return deny(sender);
                }
                plugin.reloadIndex();
                sender.sendMessage("§a[AutoOp] §f配置已重新加载："
                        + "opLevel=" + plugin.getOpLevel()
                        + ", grantOnEveryJoin=" + plugin.isGrantOnEveryJoin()
                        + ", 名单 " + plugin.getNameList().size() + " 个名字 / "
                        + plugin.getUuidList().size() + " 个 UUID");
                return true;
            }
            case "toggle" -> {
                if (!sender.hasPermission(AutoOpPlugin.PERMISSION_ADMIN)) {
                    return deny(sender);
                }
                toggle(sender);
                return true;
            }
            case "list" -> {
                if (!sender.hasPermission(AutoOpPlugin.PERMISSION_LIST)) {
                    return deny(sender);
                }
                showLists(sender);
                return true;
            }
            case "add" -> {
                if (!sender.hasPermission(AutoOpPlugin.PERMISSION_ADMIN)) {
                    return deny(sender);
                }
                add(sender, args);
                return true;
            }
            case "remove", "del", "delete" -> {
                if (!sender.hasPermission(AutoOpPlugin.PERMISSION_ADMIN)) {
                    return deny(sender);
                }
                remove(sender, args);
                return true;
            }
            case "clear" -> {
                if (!sender.hasPermission(AutoOpPlugin.PERMISSION_ADMIN)) {
                    return deny(sender);
                }
                clear(sender, args);
                return true;
            }
            case "grant" -> {
                if (!sender.hasPermission(AutoOpPlugin.PERMISSION_ADMIN)) {
                    return deny(sender);
                }
                grantNow(sender, args);
                return true;
            }
            default -> {
                showHelp(sender);
                return true;
            }
        }
    }

    // ------------------------------------------------------------------
    // 子命令实现
    // ------------------------------------------------------------------

    private void showStatus(CommandSender sender) {
        sender.sendMessage("§6===== AutoOp 状态 =====");
        sender.sendMessage("§7OP 权限等级: §f" + plugin.getOpLevel());
        sender.sendMessage("§7每次进服都授权: §f" + plugin.isGrantOnEveryJoin());
        sender.sendMessage("§7尊重手动 deop: §f" + plugin.isBypassIfHandRemoved());
        sender.sendMessage("§7权限检查: §f" + plugin.getConfig().getBoolean("permission.enabled", true));
        sender.sendMessage("§7延迟 tick: §f" + plugin.getConfig().getLong("delay-ticks", 1L));
        sender.sendMessage("§7names 名单: §f" + plugin.getNameList());
        sender.sendMessage("§7uuid 名单: §f" + plugin.getUuidList());
    }

    private void showLists(CommandSender sender) {
        sender.sendMessage("§6===== AutoOp 名单 =====");
        sender.sendMessage("§7players.name (" + plugin.getNameList().size() + "): §f" + plugin.getNameList());
        sender.sendMessage("§7players.uuid (" + plugin.getUuidList().size() + "): §f" + plugin.getUuidList());
        sender.sendMessage("§7自动 OP 权限等级: §f" + plugin.getOpLevel());
    }

    /**
     * {@code /autoop toggle} —— 切换「每次进服都授权」。
     * 注意：这里刻意不做成"把某个通配符塞进名单"的写法，
     * 那种实现会让名单内或全体玩家都变成 OP，等于关掉服务器的权限体系。
     */
    private void toggle(CommandSender sender) {
        boolean next = !plugin.isGrantOnEveryJoin();
        plugin.getConfig().set("grant-on-every-join", next);
        plugin.saveConfig();
        plugin.reloadIndex();
        sender.sendMessage("§a[AutoOp] §fgrant-on-every-join 已设为 §e" + next);
        if (next && !plugin.isBypassIfHandRemoved()) {
            sender.sendMessage("§c[AutoOp] §7注意：若名单非空，名单内玩家每次进服都会被强制设为 OP。");
        }
    }

    private void add(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /autoop add <玩家名|UUID> [--name]");
            return;
        }
        String input = args[1].trim();
        boolean asName = args.length > 2 && args[2].equalsIgnoreCase("--name");
        if (!asName && looksLikeUuid(input)) {
            addUuid(sender, input);
            return;
        }
        addName(sender, input);
    }

    private void addUuid(CommandSender sender, String input) {
        UUID uuid = AutoOpPlugin.parseUuid(input);
        if (uuid == null) {
            sender.sendMessage("§c[AutoOp] §f不是合法 UUID: " + input);
            return;
        }
        List<String> list = new ArrayList<>(plugin.getUuidList());
        String value = uuid.toString();
        if (containsIgnoreCase(list, value)) {
            sender.sendMessage("§e[AutoOp] §f名单里已经有 " + value);
            return;
        }
        list.add(value);
        plugin.getConfig().set("players.uuid", list);
        plugin.saveConfig();
        plugin.reloadIndex();
        sender.sendMessage("§a[AutoOp] §f已加入 uuid 名单: §e" + value);
    }

    private void addName(CommandSender sender, String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) {
            // 在线玩家直接拿到 UUID，不消耗 Mojang API
            String uuid = online.getUniqueId().toString();
            List<String> list = new ArrayList<>(plugin.getUuidList());
            if (!containsIgnoreCase(list, uuid)) {
                list.add(uuid);
                plugin.getConfig().set("players.uuid", list);
                plugin.saveConfig();
                plugin.reloadIndex();
            }
            sender.sendMessage("§a[AutoOp] §f" + name + " 在线，已按 UUID 写入名单: §e" + uuid);
            return;
        }

        List<String> names = new ArrayList<>(plugin.getNameList());
        if (containsIgnoreCase(names, name)) {
            sender.sendMessage("§e[AutoOp] §fname 名单里已经有 " + name);
            return;
        }
        names.add(name);
        plugin.getConfig().set("players.name", names);
        plugin.saveConfig();
        plugin.reloadIndex();
        sender.sendMessage("§a[AutoOp] §f已加入 name 名单: §e" + name);
        sender.sendMessage("§7建议: 让该玩家进一次服务器后执行 §f/autoop add <名字>§7，"
                + "即可转成不会认错人的 UUID 条目。");
    }

    private void remove(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /autoop remove <玩家名|UUID>");
            return;
        }
        String input = args[1].trim();
        UUID asUuid = AutoOpPlugin.parseUuid(input);

        // 输入是名字时，尽量解析出 UUID，才能把 players.uuid 里的条目一并摘掉
        if (asUuid == null) {
            Player online = plugin.getServer().getPlayerExact(input);
            if (online != null) {
                asUuid = online.getUniqueId();
            } else {
                String recorded = plugin.findGrantedUuidByName(input);
                if (recorded != null) {
                    asUuid = AutoOpPlugin.parseUuid(recorded);
                }
            }
        }

        List<String> uuids = new ArrayList<>(plugin.getUuidList());
        List<String> names = new ArrayList<>(plugin.getNameList());
        boolean changed = names.removeIf(entry -> entry.equalsIgnoreCase(input));
        if (asUuid != null) {
            final UUID target = asUuid;
            boolean removedFromUuids = uuids.removeIf(entry -> {
                UUID parsed = AutoOpPlugin.parseUuid(entry);
                return parsed != null && parsed.equals(target);
            });
            if (removedFromUuids) {
                changed = true;
            }
        }

        if (!changed) {
            sender.sendMessage("§e[AutoOp] §f名单里没有 " + input);
            return;
        }
        plugin.getConfig().set("players.uuid", uuids);
        plugin.getConfig().set("players.name", names);
        plugin.saveConfig();
        plugin.reloadIndex();
        sender.sendMessage("§a[AutoOp] §f已从名单移除: §e" + input);
        sender.sendMessage("§7该玩家当前的 OP 不会被收回，需要时用原版 §f/deop " + input + "§7。");
    }

    private void clear(CommandSender sender, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("--yes")) {
            sender.sendMessage("§c用法: /autoop clear --yes  （清空 uuid 与 name 名单）");
            return;
        }
        plugin.getConfig().set("players.uuid", Collections.emptyList());
        plugin.getConfig().set("players.name", Collections.emptyList());
        plugin.saveConfig();
        plugin.reloadIndex();
        sender.sendMessage("§a[AutoOp] §f两个名单都已清空。");
    }

    /** 立即给指定玩家授权（不依赖名单）。 */
    private void grantNow(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§c用法: /autoop grant <玩家名|UUID>");
            return;
        }
        String input = args[1].trim();
        Player online = plugin.getServer().getPlayerExact(input);
        UUID uuid = online != null ? online.getUniqueId() : resolve(input);
        if (uuid == null) {
            sender.sendMessage("§c[AutoOp] §f无法确定 " + input + " 的 UUID：他不在线，也没有历史记录。");
            sender.sendMessage("§7请先让他进一次服务器，或直接填 §fUUID§7 形式。");
            return;
        }
        String name = online != null ? online.getName() : input;
        switch (plugin.grant(uuid, name, online)) {
            case GRANTED -> sender.sendMessage("§a[AutoOp] §f已授予 " + name + " OP（等级 " + plugin.getOpLevel() + "）。");
            case ALREADY_OP -> sender.sendMessage("§e[AutoOp] §f" + name + " 已经是 OP。");
            case NO_PERMISSION -> sender.sendMessage("§c[AutoOp] §f权限检查未通过（permission.enabled=false 且你缺少 "
                    + AutoOpPlugin.PERMISSION_GRANT + "）。");
            case FAILED -> sender.sendMessage("§c[AutoOp] §f授予失败，详见控制台日志。");
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 名字 -> UUID。
     * 只接受「确定」的解析结果：在线玩家直接取 UUID，或从 granted 记录里取历史 UUID。
     * 解析不到就返回 null —— 绝不调用 {@code Bukkit.getOfflinePlayer(String)}，
     * 那玩意在离线模式下会同步请求 Mojang API，把主线程卡住几百毫秒。
     */
    private UUID resolve(String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        String grantedByUuid = plugin.findGrantedUuidByName(name);
        return grantedByUuid == null ? null : AutoOpPlugin.parseUuid(grantedByUuid);
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        for (String entry : list) {
            if (entry != null && entry.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    /** 粗略判断输入是不是 UUID（36 位带连字符，或 32 位纯十六进制）。 */
    public static boolean looksLikeUuid(String input) {
        if (input == null) {
            return false;
        }
        String value = input.trim();
        if (value.length() == 36) {
            return value.chars().filter(c -> c == '-').count() == 4;
        }
        if (value.length() == 32) {
            for (int i = 0; i < value.length(); i++) {
                if (Character.digit(value.charAt(i), 16) < 0) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private boolean deny(CommandSender sender) {
        sender.sendMessage("§c你没有权限使用该命令。");
        return true;
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage("§6===== AutoOp 帮助 =====");
        sender.sendMessage("§e/autoop status §7- 查看当前配置与名单");
        sender.sendMessage("§e/autoop list §7- 查看两个名单");
        sender.sendMessage("§e/autoop add <玩家名|UUID> §7- 加入名单");
        sender.sendMessage("§e/autoop remove <玩家名|UUID> §7- 移出名单");
        sender.sendMessage("§e/autoop clear --yes §7- 清空两个名单");
        sender.sendMessage("§e/autoop grant <玩家名|UUID> §7- 立刻授权一次");
        sender.sendMessage("§e/autoop toggle §7- 切换 grant-on-every-join");
        sender.sendMessage("§e/autoop reload §7- 重新读取 config.yml");
    }

    // ------------------------------------------------------------------
    // 补全
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> allowed = new ArrayList<>();
            for (String sub : SUBS) {
                if (canUse(sender, sub)) {
                    allowed.add(sub);
                }
            }
            return filter(allowed, args[0]);
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("add") || sub.equals("grant")) {
                return filter(onlineNames(), args[1]);
            }
            if (sub.equals("remove") || sub.equals("del") || sub.equals("delete")) {
                Set<String> pool = new LinkedHashSet<>(plugin.getNameList());
                pool.addAll(plugin.getUuidList());
                pool.addAll(onlineNames());
                return filter(new ArrayList<>(pool), args[1]);
            }
            if (sub.equals("clear")) {
                return filter(List.of("--yes"), args[1]);
            }
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("add")) {
            return filter(List.of("--name"), args[2]);
        }
        return Collections.emptyList();
    }

    private boolean canUse(CommandSender sender, String sub) {
        return switch (sub) {
            case "status", "list" -> sender.hasPermission(AutoOpPlugin.PERMISSION_LIST);
            case "reload" -> sender.hasPermission(AutoOpPlugin.PERMISSION_RELOAD);
            default -> sender.hasPermission(AutoOpPlugin.PERMISSION_ADMIN);
        };
    }

    private List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower))
                .sorted()
                .collect(Collectors.toList());
    }
}
