# ============================================================
# AutoOp 纯 javac 构建（不需要 Maven / Gradle / 网络也能跑）
#
# 用法（推荐第一种）:
#   pwsh -ExecutionPolicy Bypass -File build-javac.ps1 -ServerDir "E:\MC\paper\26.2"
#   pwsh -ExecutionPolicy Bypass -File build-javac.ps1 -ApiJar "lib\paper-api-26.2.build.129-stable.jar"
#   pwsh -ExecutionPolicy Bypass -File build-javac.ps1 -ServerDir "..." -JavaHome "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
#
# 说明:
#   -ServerDir 传服务端所在目录，脚本自动用其 libraries\ 下的全部 jar 当 classpath。
#     最省事，也最不容易出错。
#   -ServerJar 只传单个 jar 时注意：paper.jar 是 paperclip 启动器，里面只有
#     io/papermc/paperclip/*，直接当 -cp 会报「程序包 org.bukkit 不存在」。
#     真正的 org.bukkit.* API 在服务端 libraries\ 目录里。
#   -ApiJar 传单独的 paper-api jar 时，若报"找不到符号"，说明缺传递依赖
#     （guava / adventure / gson …），改用 -ServerDir 更快。
# ============================================================

[CmdletBinding()]
param(
    # 服务端目录（推荐）：自动用其 libraries\ 下的全部 jar 作为 classpath。
    # 已知坑：paper.jar 是 paperclip 启动器，只有 io/papermc/paperclip/*，
    # 直接当 -cp 会报「程序包 org.bukkit 不存在」——真正的 API 在服务端 libraries\ 里。
    [string]$ServerDir = "",
    [string]$ServerJar = "",
    [string]$ApiJar = "",
    [string]$JavaHome = "",
    [string]$OutputName = "AutoOp-1.0.0.jar"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$OutDir = Join-Path $ProjectRoot "target"
$ClassesDir = Join-Path $OutDir "classes"
$SrcDir = Join-Path $ProjectRoot "src\main\java"
$ResDir = Join-Path $ProjectRoot "src\main\resources"

function Step($m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }
function Ok($m)   { Write-Host "[OK]   $m" -ForegroundColor Green }
function Bad($m)  { Write-Host "[FAIL] $m" -ForegroundColor Red }
function Warn($m) { Write-Host "[WARN] $m" -ForegroundColor Yellow }

# ---------- 找 JDK ----------
function Resolve-Javac {
    if ($JavaHome -ne "") {
        $candidate = Join-Path $JavaHome "bin\javac.exe"
        if (Test-Path $candidate) { return $candidate }
        Warn "-JavaHome 下没有 bin\javac.exe: $JavaHome"
    }
    $onPath = Get-Command javac -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    # 自动扫常见 JDK 安装位置，优先高版本（Paper 26.2 需要 21+）
    $roots = @(
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Java",
        "C:\Program Files\Microsoft",
        "C:\Program Files\Amazon Corretto",
        "C:\Program Files\Zulu",
        "C:\Program Files\BellSoft"
    )
    $found = @()
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        $found += Get-ChildItem -Path $root -Recurse -Filter "javac.exe" -ErrorAction SilentlyContinue |
                  Select-Object -ExpandProperty FullName
    }
    if ($found.Count -eq 0) { return $null }
    # 路径里版本号越大越优先
    return ($found | Sort-Object -Descending | Select-Object -First 1)
}

Step "定位 JDK"
$javac = Resolve-Javac
if (-not $javac) {
    Bad "找不到 javac.exe。请装 JDK 21+，或用 -JavaHome 指定 JDK 目录。"
    exit 1
}
$jdkBin = Split-Path -Parent $javac
$jarExe = Join-Path $jdkBin "jar.exe"
Ok "javac: $javac"

# ---------- 确定 classpath ----------
Step "确定编译 classpath"
$cp = ""
if ($ServerDir -ne "") {
    # 已知坑：paper.jar 是 paperclip 启动器（只有 io/papermc/paperclip/*），
    # 真正的 org.bukkit.* API 在服务端 libraries\ 目录下的 140+ 个 jar 里。
    $libDir = Join-Path (Resolve-Path $ServerDir).Path "libraries"
    if (-not (Test-Path $libDir)) { Bad "服务端目录下没有 libraries\: $libDir"; exit 1 }
    $all = @(Get-ChildItem -Path $libDir -Recurse -Filter "*.jar" -ErrorAction SilentlyContinue)
    if ($all.Count -eq 0) { Bad "$libDir 下没有 jar"; exit 1 }

    # Paper 26.3+ 的 libraries\ 里会同时存在多个 paper-api 版本
    # (26.3.build.5-alpha ... 26.3.build.49-alpha ... 26.3.local-SNAPSHOT)。
    # 全部拼进 -cp 会出现重复类, javac 可能取到旧版本的类。规则:
    #   1) paper-api 只取一个: 优先 local-SNAPSHOT (服务端实际运行的那个),
    #      否则取 build 号最大的;
    #   2) 其它 jar (guava / adventure / gson ...) 全带上, 它们是传递依赖。
    $apis = @($all | Where-Object { $_.Name -like "paper-api*.jar" })
    $pick = $null
    if ($apis.Count -gt 0) {
        $pick = $apis | Where-Object { $_.FullName -like "*local-SNAPSHOT*" } | Select-Object -First 1
        if (-not $pick) {
            $pick = $apis |
                Sort-Object { if ($_.Name -match "-([0-9]+)\.jar") { [int]$Matches[1] } else { -1 } } |
                Select-Object -Last 1
        }
        if ($apis.Count -gt 1) {
            Warn "libraries\ 下有 $($apis.Count) 个 paper-api, 只用: $($pick.Name)"
        }
    }
    $apiName = "未找到"
    if ($pick) { $apiName = $pick.Name }
    $jars = @($all | Where-Object { $_.Name -notlike "paper-api*.jar" } | ForEach-Object { $_.FullName })
    if ($pick) { $jars += $pick.FullName }
    $cp = $jars -join ";"
    Ok "使用服务端 libraries\: $($jars.Count) 个 jar (paper-api: $apiName)"
} elseif ($ServerJar -ne "") {
    if (-not (Test-Path $ServerJar)) { Bad "找不到服务端 jar: $ServerJar"; exit 1 }
    $cp = (Resolve-Path $ServerJar).Path
    Ok "使用服务端 jar: $cp"
    if ((Split-Path -Leaf $cp) -like "paper*.jar") {
        Warn "paper.jar 是 paperclip 启动器，可能不含 org.bukkit.* —— 若报「程序包 org.bukkit 不存在」，改用 -ServerDir"
    }
} elseif ($ApiJar -ne "") {
    if (-not (Test-Path $ApiJar)) { Bad "找不到 paper-api jar: $ApiJar"; exit 1 }
    $cp = (Resolve-Path $ApiJar).Path
    # 顺带把同目录下的其它 jar 也拼上，容错传递依赖
    $extra = Get-ChildItem -Path (Split-Path -Parent $cp) -Filter "*.jar" -ErrorAction SilentlyContinue |
             Where-Object { $_.FullName -ne (Resolve-Path $cp).Path } |
             Select-Object -ExpandProperty FullName
    if ($extra) {
        $cp = ($cp, ($extra -join ";")) -join ";"
        Warn "已附带同目录 $(($extra | Measure-Object).Count) 个额外 jar"
    }
    Ok "使用 paper-api jar: $cp"
} else {
    # 没传参数：自动找 lib\ 下的 jar，再找项目附近的服务端 jar
    $auto = @()
    $libDir = Join-Path $ProjectRoot "lib"
    if (Test-Path $libDir) {
        $auto += Get-ChildItem -Path $libDir -Filter "*.jar" -ErrorAction SilentlyContinue |
                 Select-Object -ExpandProperty FullName
    }
    if ($auto.Count -eq 0) {
        Bad "没指定 -ServerDir / -ApiJar，$libDir 下也没有 jar。"
        Write-Host @"
请任选一种:
  1) 指向服务端目录（推荐）:
       pwsh -ExecutionPolicy Bypass -File build-javac.ps1 -ServerDir "E:\MC\paper\26.2"
  2) 下载 paper-api 放进 lib\:
       https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/26.2.build.129-stable/paper-api-26.2.build.129-stable.jar
"@
        exit 1
    }
    $cp = $auto -join ";"
    Ok "自动使用 lib\ 下的 jar: $cp"
}

# ---------- 编译 ----------
Step "编译"
if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $ClassesDir | Out-Null

$sources = @(Get-ChildItem -Path $SrcDir -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName)
if ($sources.Count -eq 0) { Bad "没找到 Java 源码: $SrcDir"; exit 1 }
Write-Host "源文件 $($sources.Count) 个"

& $javac -encoding UTF-8 --release 21 -cp $cp -d $ClassesDir $sources
if ($LASTEXITCODE -ne 0) {
    Bad "javac 编译失败（exit $LASTEXITCODE）。把上面的完整报错发我。"
    exit $LASTEXITCODE
}
Ok "编译通过"

# ---------- 资源 + 打包 ----------
Step "打包"
Copy-Item (Join-Path $ResDir "plugin.yml") $ClassesDir -Force
Copy-Item (Join-Path $ResDir "config.yml") $ClassesDir -Force
Ok "已放入 plugin.yml / config.yml"

$jarPath = Join-Path $OutDir $OutputName
& $jarExe --create --file $jarPath -C $ClassesDir .
if ($LASTEXITCODE -ne 0) { Bad "jar 打包失败（exit $LASTEXITCODE）"; exit $LASTEXITCODE }

$size = [math]::Round((Get-Item $jarPath).Length / 1KB, 1)
Write-Host ""
Ok "构建完成: $jarPath  ($size KB)"
Write-Host "把它放进服务端 plugins\ 目录，重启服务器即可。" -ForegroundColor Green
exit 0
