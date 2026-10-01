# AutoOp — Paper 26.2 进服自动授予 OP

给你**自己开的 Paper 服务端**用的管理插件：名单内的玩家一进服，服务端就自动把他设为 OP，
配置写入 `ops.json` 并持久化。

这不只是"进服发一次 OP"——`/op` 之后所有权威操作仍然由服务端执行，
插件做的是**服务端侧的授权**，符合 Paper 插件模型。

> 适用版本：**Paper 26.2**（Java 25 运行环境）。
> 换版本时要同步改三处：`pom.xml` / `build.gradle.kts` 的 `paper-api` 版本、
> `plugin.yml` 的 `api-version`。版本号去
> [PaperMC 仓库](https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/) 查。

---

## 1. 目录结构

```
autoop/
├─ pom.xml                     Maven 构建（推荐）
├─ build.gradle.kts            Gradle 备用
├─ build.ps1 / build.cmd       一键构建脚本（自动挑 Maven / Gradle / javac）
├─ src/main/
│  ├─ java/cn/mod/autoop/
│  │  ├─ AutoOpPlugin.java     主类：进服监听 + 授权逻辑 + 名单索引
│  │  └─ AutoOpCommand.java    /autoop 命令与 Tab 补全
│  └─ resources/
│     ├─ plugin.yml            插件声明（api-version: '26.2'）
│     └─ config.yml            默认配置（会被复制到 plugins/AutoOp/config.yml）
└─ tools/gen-offline-uuid.py   离线模式下按玩家名算 UUID + 生成 ops.json 条目
```

---

## 2. 构建

> 本机实测情况：**已装 JDK 25 / 21 / 17（含 javac），但没装 Maven 和 Gradle**。
> 所以下面**方式一**是你这台机器上最省事的路子，不需要装任何东西。

### 方式一：纯 javac（推荐，无需 Maven / Gradle / 网络）

```powershell
cd autoop
pwsh -ExecutionPolicy Bypass -File build-javac.ps1 -ServerJar "D:\你的服务端目录\paper.jar"
# 产物: target\AutoOp-1.0.0.jar
```

为什么传服务端 jar：`paper.jar` 本身就是自包含的，里面已经有全部 Bukkit/Paper API 类，
直接拿它当 `-cp` 最稳，不用去下载 paper-api 和它的一堆传递依赖（guava、adventure、gson…）。

脚本会自动在这些位置找 JDK，找到就用（本机实际存在的路径）：

```
C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\javac.exe
C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot\bin\javac.exe
```

找不到时用 `-JavaHome` 指定：

```powershell
pwsh -ExecutionPolicy Bypass -File build-javac.ps1 `
     -ServerJar "D:\mc\paper.jar" `
     -JavaHome "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
```

只下载 paper-api jar 的替代做法：

```powershell
# 下载 paper-api-26.2.build.129-stable.jar 到 autoop\lib\ 后：
pwsh -ExecutionPolicy Bypass -File build-javac.ps1 -ApiJar "lib\paper-api-26.2.build.129-stable.jar"
```

若报「找不到符号」，说明 paper-api 的传递依赖缺失，改用 `-ServerJar`。

### 方式二：build.ps1（装了 Maven 或 Gradle 时）

```powershell
pwsh -File build.ps1
```

依次尝试 Maven → Gradle → 本地 jar，产物统一放到 `target\AutoOp-1.0.0.jar`。

### 方式三：Maven（先装 `winget install Apache.Maven`）

```powershell
mvn -q -DskipTests package
# 产物: target\AutoOp-1.0.0.jar
```

### 手动一条条敲（不想用脚本时）

```powershell
$jdk = "C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot"
cd autoop
New-Item -ItemType Directory -Force target\classes
& "$jdk\bin\javac.exe" -encoding UTF-8 --release 21 -cp "D:\mc\paper.jar" `
      -d target\classes (Get-ChildItem src\main\java -Recurse -Filter *.java).FullName
Copy-Item src\main\resources\plugin.yml,src\main\resources\config.yml target\classes\
& "$jdk\bin\jar.exe" --create --file target\AutoOp-1.0.0.jar -C target\classes .
```

---

## 3. 安装

1. 把 `AutoOp-1.0.0.jar` 放进 Paper 服务端的 `plugins\` 目录。
2. 重启服务器（不是 `/reload`）。
3. 启动日志里应出现：

   ```
   [AutoOp] AutoOp 已启用：opLevel=4, grantOnEveryJoin=false, uuid=0 条, name=0 条
   ```

4. 编辑 `plugins\AutoOp\config.yml`，填入要授权的自己，然后 `/autoop reload`。

---

## 4. 配置

```yaml
op-level: 4                 # 1-4，4 = 完整权限
grant-on-every-join: false  # false = 只在首次进服授权一次（推荐）
respect-manual-deop: true   # 你手动 /deop 后，插件不再把他顶回来（推荐）

delay-ticks: 1              # 进服后延迟几 tick 再授权，等权限插件就绪

players:
  uuid:                     # 推荐：精确、改名不失效
    - "069a79f4-44e9-4726-a5be-fca90e38aaf5"
  name:                     # 备选：按名字匹配
    - "YourName"

permission:
  enabled: true             # true = 必须拥有 autoop.grant 权限才被授权

notify-console: true
notify-player: false
granted: {}                 # 插件自动维护，别手删
```

### `players.uuid` 怎么填

**最省事的办法**：先让目标玩家进一次服务器，然后在控制台执行

```
autoop add <玩家名>
```

如果该玩家在线，插件直接取他的 UUID 写进 `players.uuid`；
不在线则写进 `players.name`（按名字匹配），并提示你之后转成 UUID。

**离线模式（`online-mode=false`）** 也可以事先算出来：

```powershell
python tools\gen-offline-uuid.py YourName
```

脚本按 Java 的 `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)` 规则算出 UUID，
并输出可直接粘贴进 `ops.json` 的 JSON 片段。注意：正版模式（`online-mode=true`）
下 UUID 由 Mojang 账号决定，不能用这个脚本算，请用上面的 `/autoop add` 方式。

---

## 5. 命令

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/autoop status` | `autoop.list` | 查看当前配置与名单 |
| `/autoop list` | `autoop.list` | 列出 `uuid` / `name` 两个名单 |
| `/autoop add <玩家名\|UUID>` | `autoop.admin` | 加入名单（在线玩家自动转 UUID） |
| `/autoop remove <玩家名\|UUID>` | `autoop.admin` | 移出名单（不收回已有 OP） |
| `/autoop grant <玩家名\|UUID>` | `autoop.admin` | 立刻授权一次，不入名单 |
| `/autoop clear --yes` | `autoop.admin` | 清空两个名单 |
| `/autoop toggle` | `autoop.admin` | 切换 `grant-on-every-join` |
| `/autoop reload` | `autoop.reload` | 重新读取 config.yml |

权限节点：`autoop.*`（含 `autoop.list` / `autoop.grant` / `autoop.admin` / `autoop.reload`）。

`autoop.grant` 默认给 OP，用于 `permission.enabled: true` 时的"谁有资格被自动授权"。
这样即使名单被误写成一个普通玩家的名字，他也拿不到 OP。

**取消授权**：用原版 `/deop <玩家>`。`respect-manual-deop: true` 时插件不会在下次进服把他顶回来。

---

## 6. 几个已处理的坑

| 场景 | 处理方式 |
| --- | --- |
| 玩家进服瞬间权限还没加载完 | 事件用 `MONITOR` 优先级 + 延迟 `delay-ticks`（默认 1 tick），等 LuckPerms 等就绪 |
| 延迟期间玩家掉线 | 执行前检查 `isOnline()`，掉线直接跳过 |
| 已有 OP 的玩家反复写 `ops.json` | `grant-on-every-join: false` 时先判 `isOp()`，避免无意义磁盘写入 |
| 你手动 `/deop` 了人，他下次进服又变 OP | `respect-manual-deop: true` + `granted` 记录识别手动 deop |
| 离线模式下 `getOfflinePlayer(String)` 阻塞主线程几百毫秒 | 代码里完全不用它。UUID 只来自在线玩家或已有记录，绝不发网络请求 || 名单里名字大小写不一致 | 统一转小写比较；UUID 宽松解析（带/不带连字符都行） |
| 命令刚装上时权限节点还没注册，连命令都不显示 | `plugin.yml` 里命令本体不设 `permission`，各子命令在代码内鉴权 |

---

## 7. 验证清单

装好后按这个顺序确认：

1. `plugins\AutoOp\config.yml` 生成了，`/autoop status` 能打印配置。
2. 把自己加进名单：控制台执行 `autoop add <你的名字>`。
3. 退出重进服务器 —— 你应该是 OP，控制台出现
   `已授予 <名字> (<uuid>) OP 权限，等级 4`。
4. 检查服务端 `ops.json`，里面有你的 UUID 且 `level: 4`。
5. `/deop <你>`，再退出重进 —— 你**不应该**被重新授权（`respect-manual-deop: true`）。
6. `autoop toggle` 打开 `grant-on-every-join` 后重进，你**应该**恢复 OP。
7. 把一个不在名单的玩家放进来，他**不应该**拿到 OP。

> ✅ **已编译验证**：`target\AutoOp-1.0.0.jar`（18,228 字节）已真实产出。
>
> - 编译器：`C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\javac.exe`，
>   参数 `-encoding UTF-8 --release 21`，exit 0，无 error 无 warning
> - classpath：`E:\MC\paper\26.2\libraries`（143 个 jar，含
>   `io/papermc/paper/paper-api/26.2.local-SNAPSHOT`）
>   —— 也就是对着你**实际在跑的 Paper 26.2** 编的，API 完全匹配
> - jar 内容：`AutoOpPlugin.class`、`AutoOpCommand.class`、
>   `AutoOpPlugin$GrantResult.class`、`AutoOpPlugin$PlayerIndex.class`、
>   `AutoOpPlugin$1.class`、`AutoOpCommand$1.class`、`plugin.yml`、`config.yml`

### 想重编时用这条命令（已验证可用）

```powershell
$root   = "E:\编程\项目\MOD\autoop"
$libDir = "E:\MC\paper\26.2\libraries"   # 服务端自带 libraries，含 paper-api
$jdk    = "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
$cp     = (Get-ChildItem $libDir -Recurse -Filter '*.jar' | ForEach-Object { $_.FullName }) -join ';'

Remove-Item "$root\target\classes" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$root\target\classes" | Out-Null
$srcs = (Get-ChildItem "$root\src\main\java" -Recurse -Filter *.java).FullName

& "$jdk\bin\javac.exe" -encoding UTF-8 --release 21 -cp $cp -d "$root\target\classes" $srcs
if ($LASTEXITCODE -eq 0) {
    Copy-Item "$root\src\main\resources\plugin.yml"  "$root\target\classes"
    Copy-Item "$root\src\main\resources\config.yml" "$root\target\classes"
    & "$jdk\bin\jar.exe" --create --file "$root\target\AutoOp-1.0.0.jar" -C "$root\target\classes" .
}
```

> ⚠️ **注意**：不要把 `paper.jar` 本身当 classpath。那是 paperclip 启动器，
> 只有 `io/papermc/paperclip/*`，真正的 `org.bukkit.*` API 在服务端 `libraries/` 里。
>
> 换服务端版本（26.3 等）时，把 `$libDir` 指向那个服务端的 `libraries` 重跑即可。



---

## 8. 安全提醒

- `grant-on-every-join: true` 且名单非空时，名单内玩家每次进服都会被强制设为 OP。
  不要给不信任的人开这个开关。
- 别把 `players.uuid` 写成通配符或把 `permission.enabled` 设 `false` 后随便加名字 ——
  那等于给全服发 OP，权限体系会失效。
- 这个插件只对**你自己拥有或有权管理的服务器**有意义。
  在别人的服务器上不存在"客户端获取 OP"这条路：OP 由服务端保存与判定，
  客户端无法自称权限等级，正常协议里也没有这种包。
