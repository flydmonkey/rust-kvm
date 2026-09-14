<#
.SYNOPSIS
    启动 rust-kvm。默认走 Windows 服务，-Dev 则在当前窗口前台跑。

.DESCRIPTION
    两种跑法不是等价的，选哪个取决于你要干什么：

      服务（默认）  跑在 session 0。**只有这里能枚举到物理音频设备**——远程桌面
                    会话里采集卡的音频口根本不可见。要听被控机的声音就得用它。
                    日志没有 stdout，落在 rust-kvm.log。

      -Dev          跑在当前会话，日志直接打在这个窗口，改代码调试用。代价是
                    上面那条：音频设备可能枚举不到，视频和键鼠不受影响。

.PARAMETER Dev
    前台跑，日志打在当前窗口。Ctrl+C 停。

.PARAMETER Build
    先 cargo build --release。会先停掉在跑的实例，否则链接器删不掉 exe。

.EXAMPLE
    .\start.ps1              # 装成服务的日常用法
    .\start.ps1 -Dev -Build  # 改完代码，重编译并前台跑
#>
[CmdletBinding()]
param(
    [switch]$Dev,
    [switch]$Build
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$ServiceName = "rust-kvm"
$Exe = Join-Path $PSScriptRoot "target\release\rust-kvm.exe"

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal $id).IsInRole(
        [Security.Principal.WindowsBuiltinRole]::Administrator)
}

# 提权跑一条 rust-kvm 子命令（install/start/stop 都要管理员）。
# 单独开窗口看不到输出，所以把结果重定向到临时文件再读回来。
function Invoke-Elevated([string[]]$ExeArgs) {
    $out = [System.IO.Path]::GetTempFileName()
    $p = Start-Process $Exe -ArgumentList $ExeArgs -Verb RunAs -Wait -PassThru `
        -WorkingDirectory $PSScriptRoot -RedirectStandardOutput $out `
        -RedirectStandardError "$out.err"
    # 同样要指明 UTF8：Get-Content 在 5.1 上对没有 BOM 的文件默认按 GBK 读
    Get-Content $out, "$out.err" -Encoding UTF8 -ErrorAction SilentlyContinue | Where-Object { $_ } | ForEach-Object {
        Write-Host "  $_"
    }
    Remove-Item $out, "$out.err" -ErrorAction SilentlyContinue
    return $p.ExitCode
}

# --- 编译 -------------------------------------------------------------------
if ($Build) {
    Write-Host "先停掉在跑的实例（否则链接器删不掉 exe）..."
    & (Join-Path $PSScriptRoot "stop.ps1") | Out-Null

    # CARGO_TARGET_DIR 必须钉死。不钉的话 build 可能落到别处的缓存目录，
    # 编译明明成功，跑起来的还是 target\release 里那个旧的——这个坑很难看出来。
    $env:CARGO_TARGET_DIR = Join-Path $PSScriptRoot "target"
    Write-Host "cargo build --release ..."
    # cargo 把进度和「Finished」都写在 stderr。$ErrorActionPreference = Stop 下，
    # PowerShell 会把原生命令的 stderr 当成错误抛出来——编译明明成功，屏幕上却是
    # 一大片红色的 NativeCommandError。只看退出码，别看它写在哪个流。
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { cargo build --release } finally { $ErrorActionPreference = $prevEap }
    if ($LASTEXITCODE -ne 0) { throw "编译失败" }
}

if (-not (Test-Path $Exe)) {
    throw "找不到 $Exe，先跑一次 .\start.ps1 -Build"
}

# --- 前台调试 ---------------------------------------------------------------
if ($Dev) {
    $svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
    if ($svc -and $svc.Status -eq 'Running') {
        throw "服务正在跑，端口会撞车。先 .\stop.ps1"
    }
    Write-Host "前台启动（Ctrl+C 停）。注意：这个会话里可能枚举不到物理音频设备。" -ForegroundColor Yellow
    Write-Host ""
    # 日志直接流到这个终端，而 exe 写的是 UTF-8；不把控制台代码页切成 65001
    # 的话，满屏日志的中文全是乱码。这之后不再有脚本自己的输出，不用还原。
    try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}
    & $Exe
    exit $LASTEXITCODE
}

# --- 服务 -------------------------------------------------------------------
$stray = @(Get-Process -Name rust-kvm -ErrorAction SilentlyContinue)
if ($stray) {
    throw "已经有 $($stray.Count) 个 rust-kvm 进程在跑（前台实例？），端口会撞车。先 .\stop.ps1"
}

$svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if (-not $svc) {
    Write-Host "还没装成服务，现在注册（要管理员）..."
    if ((Invoke-Elevated @('install')) -ne 0) { throw "注册服务失败" }
}

if ((Get-Service -Name $ServiceName).Status -eq 'Running') {
    Write-Host "服务已经在跑了"
} else {
    Write-Host "启动服务 ..."
    if (Test-Admin) {
        Start-Service -Name $ServiceName
        (Get-Service -Name $ServiceName).WaitForStatus('Running', '00:00:20')
    } elseif ((Invoke-Elevated @('start')) -ne 0) {
        throw "启动服务失败，看看 rust-kvm.log"
    }
}

# --- 等到真能访问 -----------------------------------------------------------
# 服务状态 Running 只说明进程起来了，不等于端口在听。
#
# 故意探明文的 8080 而不是 8443：PowerShell 5.1 那套 .NET Framework 的 TLS 栈
# 连不上自签名证书，报「未能创建 SSL/TLS 安全通道」，跳过证书校验也没用——
# 拿它当就绪判据会把好好的服务误判成起不来。8080 只绑回环，服务的是同一个
# axum app，探它一样能说明问题。
$deadline = (Get-Date).AddSeconds(20)
$ok = $false
while ((Get-Date) -lt $deadline) {
    try {
        $r = Invoke-WebRequest -Uri "http://127.0.0.1:8080/" -UseBasicParsing -TimeoutSec 3
        if ($r.StatusCode -eq 200) { $ok = $true; break }
    } catch {
        Start-Sleep -Milliseconds 500
    }
}

Write-Host ""
if (-not $ok) {
    Write-Warning "服务在跑，但 http://127.0.0.1:8080/ 还没应答，看 rust-kvm.log"
    exit 1
}

Write-Host "已启动。局域网地址：" -ForegroundColor Green
Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
    ForEach-Object { Write-Host "  https://$($_.IPAddress):8443/" }
Write-Host ""
Write-Host "首次访问证书会报警，点「高级」→「继续前往」，或装一下 /cert.crt"
Write-Host "日志：$(Join-Path $PSScriptRoot 'rust-kvm.log')"
