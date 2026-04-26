param(
    [string]$Ip,
    [int]$BackendPort = 8080
)

$ErrorActionPreference = "Stop"

function Get-ActiveLanIp {
    try {
        $interfaces = [System.Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces() |
            Where-Object {
                $_.OperationalStatus -eq [System.Net.NetworkInformation.OperationalStatus]::Up -and
                $_.NetworkInterfaceType -ne [System.Net.NetworkInformation.NetworkInterfaceType]::Loopback -and
                $_.NetworkInterfaceType -ne [System.Net.NetworkInformation.NetworkInterfaceType]::Tunnel
            }

        foreach ($interface in $interfaces) {
            $properties = $interface.GetIPProperties()
            $hasGateway = $properties.GatewayAddresses |
                Where-Object { $_.Address.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork } |
                Select-Object -First 1

            if (-not $hasGateway) {
                continue
            }

            $address = $properties.UnicastAddresses |
                Where-Object {
                    $_.Address.AddressFamily -eq [System.Net.Sockets.AddressFamily]::InterNetwork -and
                    $_.Address.ToString() -notmatch "^(127|169\.254|0)\."
                } |
                Select-Object -First 1

            if ($address) {
                return $address.Address.ToString()
            }
        }
    } catch {
        Write-Host "Primary IP detection failed, trying ipconfig fallback..."
    }

    $ipconfig = ipconfig | Out-String
    $matches = [regex]::Matches($ipconfig, "IPv4[^:`r`n]*:\s*((?:\d{1,3}\.){3}\d{1,3})")
    foreach ($match in $matches) {
        $candidate = $match.Groups[1].Value
        if ($candidate -notmatch "^(127|169\.254|0)\.") {
            return $candidate
        }
    }

    throw "Could not detect active LAN IPv4 address. Pass it manually: .\scripts\update-mobile-ip.ps1 -Ip 192.168.1.10"
}

function Set-EnvValue {
    param(
        [string]$Path,
        [string]$Key,
        [string]$Value
    )

    $line = "$Key=$Value"

    if (Test-Path $Path) {
        $content = Get-Content -Raw -LiteralPath $Path
        if ($content -match "(?m)^$Key=") {
            $content = $content -replace "(?m)^$Key=.*$", $line
        } else {
            $content = $content.TrimEnd() + [Environment]::NewLine + $line + [Environment]::NewLine
        }
    } else {
        $content = $line + [Environment]::NewLine
    }

    Write-Utf8NoBom -Path $Path -Content $content
}

function Write-Utf8NoBom {
    param(
        [string]$Path,
        [string]$Content
    )

    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $encoding)
}

if (-not $Ip) {
    $Ip = Get-ActiveLanIp
}

$backendRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $backendRoot
$mobileRoot = Join-Path $workspaceRoot "courier-platform-mobile"
$apiBaseUrl = "http://${Ip}:${BackendPort}"

$envFiles = @(
    (Join-Path $mobileRoot "apps\user\.env"),
    (Join-Path $mobileRoot "apps\courier-native\.env")
)

foreach ($envFile in $envFiles) {
    $envDir = Split-Path -Parent $envFile
    if (Test-Path $envDir) {
        Set-EnvValue -Path $envFile -Key "EXPO_PUBLIC_API_BASE_URL" -Value $apiBaseUrl
        Write-Host "Updated $envFile -> $apiBaseUrl"
    } else {
        Write-Host "Skipped missing app directory: $envDir"
    }
}

$corsPath = Join-Path $backendRoot "api-gateway\src\main\java\kz\courier\apigateway\config\CorsConfig.java"
if (Test-Path $corsPath) {
    $cors = Get-Content -Raw -LiteralPath $corsPath
    $newOrigin = "`"http://${Ip}:*`""

    if ($cors -match '"http://(?:\d{1,3}\.){3}\d{1,3}:\*"') {
        $cors = $cors -replace '"http://(?:\d{1,3}\.){3}\d{1,3}:\*"', $newOrigin
    } else {
        $cors = $cors -replace '("0\.0\.0\.0",)', "`$1`r`n                $newOrigin,"
    }

    Write-Utf8NoBom -Path $corsPath -Content $cors
    Write-Host "Updated $corsPath -> http://${Ip}:*"
}

Write-Host ""
Write-Host "Done. Restart api-gateway and Expo/Metro so they reload the new IP."
