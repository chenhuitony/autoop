package cn.mod.autoop;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * AutoOp — 玩家进服时自动授予 OP。
 *
 * <p>面向服主本人的管理工具。OP 只能由服务端授予，本插件在服务端进程内调用
 * {@link org.bukkit.OfflinePlayer#setOp(boolean)}，结果写入服务端 ops.json 并持久化。
 *
 * <p>两种上线方式：
 * <ul>
 *   <li>UUID 白名单（推荐）：{@code players.uuid} 精确匹配，与正版/离线模式无关。</li>
 *   <li>ID 白名单：{@code players.name} 按名字匹配，会按服务端 online-mode 推导 UUID，
 *       换名或正版/盗版混用可能匹配不到。</li>
 * </ul>
 */
public final class AutoOpPlugin extends JavaPlugin implements Listener {

    /** players.uuid 与 players.name 的并集只解析一次，进服时零反射、零磁盘 IO。 */
    private volatile PlayerIndex index = PlayerIndex.empty();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadIndex();

        getServer().getPluginManager().registerEvents(this, this);
        AutoOpCommand command = new AutoOpCommand(this);
        if (getCommand("autoop") != null) {
            getCommand("autoop").setExecutor(command);
            getCommand("autoop").setTabCompleter(command);
        } else {
            getLogger().warning("plugin.yml 中缺少 autoop 命令注册，/autoop 不可用。");
        }

        getLogger().info("AutoOp 已启用：opLevel=" + getOpLevel()
                + ", grantOnEveryJoin=" + isGrantOnEveryJoin()
                + ", uuid=" + getConfig().getStringList("players.uuid").size() + " 条"
                + ", name=" + getConfig().getStringList("players.name").size() + " 条");
    }

    @Override
    public void onDisable() {
        getLogger().info("AutoOp 已停用。");
    }

    // ------------------------------------------------------------------
    // 进服事件
    // ------------------------------------------------------------------

    /**
     * 用 MONITOR + ignoreCancelled 保证在其它插件改完权限之后再判定，
     * 延迟 1 tick 是为了让权限插件（LuckPerms 等）加载完该玩家的权限。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final PlayerIndex snapshot = this.index;
        if (!snapshot.contains(player.getUniqueId(), player.getName())) {
            return;
        }

        final long delay = Math.max(0L, getConfig().getLong("delay-ticks", 1L));
        if (delay == 0L) {
            handleJoin(player);
            return;
        }
        new BukkitRunnable() {
            @Override
            public void run() {
                handleJoin(player);
            }
        }.runTaskLater(this, delay);
    }

    private void handleJoin(Player player) {
        // 风险 B：玩家可能在延迟期间掉线
        if (!player.isOnline()) {
            return;
        }
        // 风险 C：没开 grant-on-every-join 时，已经是 OP 就不再重复写 ops.json
        if (!isGrantOnEveryJoin() && player.isOp()) {
            return;
        }
        // 风险 A：服主已手动 deop 过这个玩家 —— 尊重手动结果，不把 OP 顶回去。
        // 已记录进 granted 且当前不是 OP（上面刚判过）=> 是手动摘掉的。
        if (isBypassIfHandRemoved() && hasGrantedBefore(player.getUniqueId())) {
            return;
        }
        grant(player.getUniqueId(), player.getName(), player);
    }

    /** 该 UUID 是否曾被本插件授予过 OP（用于识别服主的手动 deop）。 */
    private boolean hasGrantedBefore(UUID uuid) {
        return getConfig().isSet("granted." + uuid);
    }

    // ------------------------------------------------------------------
    // 授予逻辑（事件与命令共用）
    // ------------------------------------------------------------------

    /** 授予结果，供命令回显使用。 */
    public enum GrantResult {
        GRANTED,
        ALREADY_OP,
        NO_PERMISSION,
        FAILED
    }

    public GrantResult grant(UUID uuid, String name, Player onlineForMessage) {
        if (!hasGrantPermission(onlineForMessage)) {
            getLogger().warning("权限未启用（permission.enabled=false 或缺少 " + PERMISSION_GRANT
                    + "），已跳过对 " + name + " 的自动授权。");
            return GrantResult.NO_PERMISSION;
        }

        final OfflinePlayer target = getServer().getOfflinePlayer(uuid);
        if (target.isOp()) {
            return GrantResult.ALREADY_OP;
        }

        try {
            target.setOp(true);
        } catch (RuntimeException ex) {
            getLogger().log(Level.SEVERE, "为 " + name + "(" + uuid + ") 设置 OP 时出错", ex);
            return GrantResult.FAILED;
        }
        if (!target.isOp()) {
            getLogger().warning("setOp(true) 已调用但 " + name + " 仍不是 OP，可能被其它插件拦截。");
            return GrantResult.FAILED;
        }

        rememberGranted(uuid, name);
        announce(name, onlineForMessage);
        getLogger().info("已授予 " + name + " (" + uuid + ") OP 权限，等级 " + getOpLevel() + "。");
        return GrantResult.GRANTED;
    }

    public boolean hasGrantPermission(Player player) {
        if (!getConfig().getBoolean("permission.enabled", true)) {
            return true;
        }
        return player == null || player.hasPermission(PERMISSION_GRANT);
    }

    private void announce(String name, Player onlineForMessage) {
        if (getConfig().getBoolean("notify-player", false) && onlineForMessage != null) {
            onlineForMessage.sendMessage("§a[AutoOp] §f你已被授予服务器 OP 权限。");
        }
        if (getConfig().getBoolean("notify-console", true)) {
            getLogger().info(name + " 已通过 AutoOp 获得 OP。");
        }
    }

    // ------------------------------------------------------------------
    // 配置读取
    // ------------------------------------------------------------------

    public static final String PERMISSION_GRANT = "autoop.grant";
    public static final String PERMISSION_ADMIN = "autoop.admin";
    public static final String PERMISSION_RELOAD = "autoop.reload";
    public static final String PERMISSION_LIST = "autoop.list";

    public int getOpLevel() {
        return Math.max(1, Math.min(4, getConfig().getInt("op-level", 4)));
    }

    public boolean isGrantOnEveryJoin() {
        return getConfig().getBoolean("grant-on-every-join", false);
    }

    public boolean isBypassIfHandRemoved() {
        return getConfig().getBoolean("respect-manual-deop", true);
    }

    public List<String> getUuidList() {
        return getConfig().getStringList("players.uuid");
    }

    public List<String> getNameList() {
        return getConfig().getStringList("players.name");
    }

    /** 保存 granted 记录，用于识别「服主手动 deop」，避免每次重启都重新授权。 */
    private void rememberGranted(UUID uuid, String name) {
        String key = "granted." + uuid;
        if (getConfig().isSet(key)) {
            return;
        }
        getConfig().set(key, name + " @ " + System.currentTimeMillis());
        saveConfig();
    }

    /**
     * 按名字在 granted 记录里反查历史 UUID，返回 UUID 字符串（找不到返回 null）。
     * 只读已记录的数据，不触发任何网络解析。
     */
    public String findGrantedUuidByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        org.bukkit.configuration.ConfigurationSection section = getConfig().getConfigurationSection("granted");
        if (section == null) {
            return null;
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (String uuidKey : section.getKeys(false)) {
            String recorded = section.getString(uuidKey);
            if (recorded == null) {
                continue;
            }
            // 记录格式: "<玩家名> @ <时间戳>"
            int separator = recorded.indexOf(" @ ");
            String recordedName = separator < 0 ? recorded : recorded.substring(0, separator);
            if (recordedName.trim().toLowerCase(Locale.ROOT).equals(wanted)) {
                return uuidKey;
            }
        }
        return null;
    }

    /** 重新构建匹配索引（reload 后调用）。 */
    public void reloadIndex() {
        reloadConfig();
        this.index = PlayerIndex.build(getUuidList(), getNameList());
    }

    /**
     * 匹配索引。名字统一转小写，UUID 统一解析为 {@link UUID}，
     * 避免每次进服做字符串解析与列表扫描。
     */
    public static final class PlayerIndex {

        private final java.util.Set<UUID> uuids;
        private final java.util.Set<String> names;

        private PlayerIndex(java.util.Set<UUID> uuids, java.util.Set<String> names) {
            this.uuids = uuids;
            this.names = names;
        }

        static PlayerIndex empty() {
            return new PlayerIndex(java.util.Set.of(), java.util.Set.of());
        }

        static PlayerIndex build(List<String> rawUuids, List<String> rawNames) {
            java.util.Set<UUID> uuids = new java.util.HashSet<>();
            java.util.Set<String> names = new java.util.HashSet<>();
            for (String raw : rawUuids) {
                UUID parsed = parseUuid(raw);
                if (parsed != null) {
                    uuids.add(parsed);
                }
            }
            for (String raw : rawNames) {
                if (raw != null && !raw.isBlank()) {
                    names.add(raw.trim().toLowerCase(Locale.ROOT));
                }
            }
            return new PlayerIndex(java.util.Set.copyOf(uuids), java.util.Set.copyOf(names));
        }

        public boolean contains(UUID uuid, String name) {
            if (uuid != null && uuids.contains(uuid)) {
                return true;
            }
            return name != null && names.contains(name.toLowerCase(Locale.ROOT));
        }

        public boolean isEmpty() {
            return uuids.isEmpty() && names.isEmpty();
        }

        public int size() {
            return uuids.size() + names.size();
        }
    }

    /** 宽松解析 UUID：允许带连字符或不带连字符，非法值返回 null。 */
    public static UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() == 32) {
            value = value.substring(0, 8) + "-" + value.substring(8, 12) + "-"
                    + value.substring(12, 16) + "-" + value.substring(16, 20) + "-"
                    + value.substring(20);
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
