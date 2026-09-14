<#
.SYNOPSIS
    停掉 rust-kvm：Windows 服务和前台调试实例一起停。

.DESCRIPTION
    「停了没有」有两层意思，这个脚本两层都管：

      1. 服务停了 —— SCM 里状态是 Stopped。
      2. 进程没了、exe 没被占着 —— 这一层才是重新 cargo build 的前提。
         链接器删不掉正在运行的 exe，报错只说「拒绝访问」，不会告诉你是谁占着，
         这个坑踩过不止一次。

    只有停服务那步要管理员，所以到那一步才提权；只有前台调试实例在跑时不弹 UAC。
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$ServiceName = "rust-kvm"
$Exe = Join-Path $PSScriptRoot "target\release\rust-kvm.exe"
$Ports = @(8080, 8443)

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal $id).IsInRole(
        [Security.Principal.WindowsBuiltinRole]::Administrator)
}

# 跑一条 rust-kvm 子命令，并且让它的中文能看。
#
# exe 往 stdout 写 UTF-8，PowerShell 5.1 默认按 OEM 代码页（这里 GBK）解，
# 中文会变成「鏈嶅姟宸插仠姝」。改 [Console]::OutputEncoding 能解对，但它同时
# 也决定本脚本自己的输出怎么编码——全局改掉的话，脚本输出被重定向时又全乱了。
# 所以只在这一次调用期间改，拿到字符串就换回来，两边都对。
function Invoke-Exe([string[]]$ExeArgs) {
    $prev = [Console]::OutputEncoding
    try {
        [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
        $lines = & $Exe @ExeArgs 2>&1
    } finally {
        [Console]::OutputEncoding = $prev
    }
    $lines | Where-Object { $_ } | ForEach-Object { Write-Host "  $_" }
}

# exe 能不能以独占方式打开。进程列表空了不等于文件就放开了——句柄的释放比
# 进程退出晚一点，紧接着 build 照样会失败，所以这里要实测而不是推断。
function Test-ExeFree {
    if (-not (Test-Path $Exe)) { return $true }
    try {
        $f = [System.IO.File]::Open($Exe, 'Open', 'ReadWrite', 'None')
        $f.Close()
        return $true
    } catch {
        return $false
    }
}

function Get-ListeningPorts {
    $listening = @()
    foreach ($port in $Ports) {
        $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
        if ($conn) { $listening += $port }
    }
    return $listening
}

# --- 1. Windows 服务 ---------------------------------------------------------
$svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($svc -and $svc.Status -ne 'Stopped') {
    if (Test-Admin) {
        Write-Host "停止服务 $ServiceName ..."
        # 服务自己会留 3 秒收尾，把串口的「全释放」包发出去——不然被控机上
        # 可能留着按下不放的键。加上采集和音频线程收摊，整个过程好几秒。
        #
        # **Stop-Service 的耐心比这短**：它会抛「停止失败」，而服务其实过一会就
        # 停了。那个异常会把脚本带崩在半路，留下一个正在退出的进程，紧接着
        # start.ps1 又会因为「已经有进程在跑」而拒绝启动。所以这里不把停止命令
        # 的抱怨当真，一律交给最后的复查循环判定。
        try {
            if (Test-Path $Exe) { Invoke-Exe @('stop') } else { Stop-Service -Name $ServiceName -Force }
        } catch {
            Write-Host "  停止命令报了错，不当真：$($_.Exception.Message)"
        }
    } else {
        Write-Host "服务在跑，停它要管理员权限，正在提权 ..."
        $p = Start-Process powershell -Verb RunAs -Wait -PassThru -ArgumentList @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath)
        if ($p.ExitCode -ne 0) {
            Write-Warning "提权后的那次退出码是 $($p.ExitCode)，下面复查一下实际状态"
        }
    }
} elseif ($svc) {
    Write-Host "服务本来就是停的"
} else {
    Write-Host "没装成服务（跳过）"
}

# --- 2. 前台调试实例 ---------------------------------------------------------
# 服务停掉后还剩下的 rust-kvm.exe 就是手工起的那些
$stray = @(Get-Process -Name rust-kvm -ErrorAction SilentlyContinue)
foreach ($p in $stray) {
    Write-Host "停止前台实例 PID $($p.Id)"
    Stop-Process -Id $p.Id -Force
}

# --- 3. 复查：服务状态、进程、文件句柄、端口 ---------------------------------
# 这里才是判据。上面那些命令返回了不等于事情做完了：SCM 的停止是异步的，
# 句柄的释放又比进程退出还晚一步。
$deadline = (Get-Date).AddSeconds(30)
do {
    Start-Sleep -Milliseconds 300
    $state = (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue).Status
    $pending = $state -and ($state -ne 'Stopped')
    $alive = @(Get-Process -Name rust-kvm -ErrorAction SilentlyContinue).Count
    $free = Test-ExeFree
    $listening = Get-ListeningPorts
} while (((Get-Date) -lt $deadline) -and ($pending -or ($alive -gt 0) -or (-not $free) -or $listening))

Write-Host ""
if ($pending) {
    Write-Warning "服务状态还是 $state"
} elseif ($alive -gt 0) {
    Write-Warning "还有 $alive 个 rust-kvm 进程没退"
} elseif (-not $free) {
    Write-Warning "进程没了但 exe 还被占着，这时候 cargo build 会失败"
} elseif ($listening) {
    Write-Warning "端口 $($listening -join ', ') 还有人监听，可能是别的程序占着"
} else {
    Write-Host "已停止：没有进程、exe 可写、$($Ports -join '/') 都空着" -ForegroundColor Green
    exit 0
}
exit 1
