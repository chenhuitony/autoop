# ============================================================
# AutoOp 一键构建脚本（Windows / PowerShell）
#
# 用法:
#   pwsh -File build.ps1
#   pwsh -File build.ps1 -SkipGradle      # 只试 Maven
#   pwsh -File build.ps1 -UseLocalApi     # 离线/无网络：用 lib\ 下的 paper-api jar 直接 javac
#   pwsh -File build.ps1 -JavaHome "C:\Program Files\Java\jdk-25"
#
# 依赖（三选一）:
#   A. Maven  (推荐: winget install Apache.Maven)   需要 JDK 21+
#   B. Gradle (winget install Gradle.Gradle)        需要 JDK 21+
#   C. 只要有 JDK + 已下载 lib\paper-api-*.jar      (走 javac/jar 分支)
# ============================================================

[CmdletBinding()]
param(
    [switch]$SkipGradle,
    [switch]$SkipMaven,
    [switch]$UseLocalApi,
    [string]$JavaHome = ""
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$OutDir = Join-Path $ProjectRoot "target"
$LibDir = Join-Path $ProjectRoot "lib"

function Write-Step([string]$m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }
function Write-Ok([string]$m)   { Write-Host "[OK]   $m" -ForegroundColor Green }
function Write-Warn2([string]$m){ Write-Host "[WARN] $m" -ForegroundColor Yellow }
function Write-Err([string]$m)  { Write-Host "[FAIL] $m" -ForegroundColor Red }

# ---------- 定位 java / javac ----------
if ($JavaHome -ne "") {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome "bin") + [IO.Path]::PathSeparator + $env:PATH
}

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Warn2 "PATH 里找不到 java。若已安装 JDK，请用 -JavaHome 指定，或手动设置 JAVA_HOME。"
} else {
    $ver = (& java -version 2>&1 | Select-Object -First 1)
    Write-Ok "java: $ver"
}

if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }

# ---------- 分支 C: 本地 paper-api jar + javac ----------
function Build-WithLocalJar {
    Write-Step "分支 C: javac + 本地 paper-api jar"

    $apiJar = Get-ChildItem -Path $LibDir -Filter "paper-api*.jar" -ErrorAction SilentlyContinue |
              Select-Object -First 1
    if (-not $apiJar) {
        Write-Err "在 $LibDir 里没找到 paper-api*.jar"
        Write-Host @"
下载方式（任选其一）:
  1) 官方仓库目录页:
     https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/26.2.build.129-stable/
     下载 paper-api-26.2.build.129-stable.jar 放进 lib\
  2) 用 Maven 一次性拉取再复制:
     mvn dependency:copy -Dartifact=io.papermc.paper:paper-api:26.2.build.129-stable -DoutputDirectory=lib
"@
        return $false
    }

    if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
        Write-Err "PATH 里找不到 javac，需要 JDK 21+（只装 JRE 不行）。"
        return $false
    }

    $classesDir = Join-Path $OutDir "classes"
    New-Item -ItemType Directory -Force -Path $classesDir | Out-Null

    $sources = Get-ChildItem -Path (Join-Path $ProjectRoot "src\main\java") -Recurse -Filter "*.java" |
               ForEach-Object { $_.FullName }
    if (-not $sources) { Write-Err "找不到 Java 源码。"; return $false }

    Write-Host "编译 $($sources.Count) 个源文件 ..."
    & javac -encoding UTF-8 --release 21 -cp $apiJar.FullName -d $classesDir $sources
    if ($LASTEXITCODE -ne 0) { Write-Err "javac 编译失败。"; return $false }

    Copy-Item (Join-Path $ProjectRoot "src\main\resources\plugin.yml") $classesDir -Force
    Copy-Item (Join-Path $ProjectRoot "src\main\resources\config.yml") $classesDir -Force

    $jarPath = Join-Path $OutDir "AutoOp-1.0.0.jar"
    & jar --create --file $jarPath -C $classesDir .
    if ($LASTEXITCODE -ne 0) { Write-Err "jar 打包失败。"; return $false }

    Write-Ok "构建完成: $jarPath"
    return $true
}

if ($UseLocalApi) {
    if (Build-WithLocalJar) { exit 0 } else { exit 1 }
}

# ---------- 分支 A: Maven ----------
if (-not $SkipMaven) {
    $mvn = Get-Command mvn -ErrorAction SilentlyContinue
    if ($mvn) {
        Write-Step "分支 A: Maven"
        & mvn -f (Join-Path $ProjectRoot "pom.xml") -q -DskipTests package
        if ($LASTEXITCODE -eq 0) {
            $built = Join-Path $OutDir "AutoOp-1.0.0.jar"
            if (Test-Path $built) {
                Write-Ok "构建完成: $built"
                Write-Host "把它放进服务器 plugins\ 目录，然后重启服务器。" -ForegroundColor Green
                exit 0
            }
            Write-Warn2 "Maven 报告成功但没找到 $built"
        } else {
            Write-Warn2 "Maven 构建失败（code $LASTEXITCODE），尝试下一种方式。"
        }
    } else {
        Write-Warn2 "没装 Maven，跳过。"
    }
}

# ---------- 分支 B: Gradle ----------
if (-not $SkipGradle) {
    $gradle = Get-Command gradle -ErrorAction SilentlyContinue
    $gradlew = Join-Path $ProjectRoot "gradlew.bat"
    if ($gradle -or (Test-Path $gradlew)) {
        Write-Step "分支 B: Gradle"
        Push-Location $ProjectRoot
        try {
            if (Test-Path $gradlew) { & $gradlew jar --no-daemon } else { & gradle jar --no-daemon }
            if ($LASTEXITCODE -eq 0) {
                $built = Get-ChildItem -Path (Join-Path $ProjectRoot "build\libs") -Filter "AutoOp*.jar" -ErrorAction SilentlyContinue |
                         Select-Object -First 1
                if ($built) {
                    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
                    Copy-Item $built.FullName $OutDir -Force
                    Write-Ok "构建完成: $(Join-Path $OutDir $built.Name)"
                    exit 0
                }
            }
            Write-Warn2 "Gradle 构建失败，尝试本地 jar 分支。"
        } finally {
            Pop-Location
        }
    } else {
        Write-Warn2 "没装 Gradle，跳过。"
    }
}

# ---------- 兜底: 本地 jar ----------
if (Build-WithLocalJar) { exit 0 }

Write-Err "三种构建方式都不可用。"
Write-Host @"

请任选一种安装后重试:
  Maven :  winget install Apache.Maven
  Gradle:  winget install Gradle.Gradle
  JDK   :  winget install Microsoft.OpenJDK.21   (或 EclipseAdoptium.Temurin.21.JDK)

装完开新的终端再跑一次本脚本。或者按 README.md 的手动步骤执行。
"@
exit 1
