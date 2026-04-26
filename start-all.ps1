# Courier Platform - Start All Services in VS Code Terminals
# Run ONCE from the project root to generate .vscode/tasks.json
# Usage: .\start-all.ps1
# Then in VS Code: Ctrl+Shift+P -> "Tasks: Run Task" -> "Start All Services"

$ROOT = Split-Path -Parent $MyInvocation.MyCommand.Path

$services = @(
    @{ name = "eureka-server";  dir = "eureka-server"  },
    @{ name = "auth-service";   dir = "auth-service"   },
    @{ name = "api-gateway";    dir = "api-gateway"    },
    @{ name = "user-service";   dir = "user-service"   },
    @{ name = "order-service";  dir = "order-service"  },
    @{ name = "companyservice"; dir = "companyservice" },
    @{ name = "notification";   dir = "notification"   }
)

$tasks = [System.Collections.Generic.List[object]]::new()

foreach ($svc in $services) {
    $tasks.Add(@{
        label          = "Run: $($svc.name)"
        type           = "shell"
        command        = ".\\mvnw.cmd"
        args           = @("spring-boot:run")
        options        = @{ cwd = "$ROOT\$($svc.dir)" }
        presentation   = @{
            reveal           = "always"
            panel            = "new"
            label            = $svc.name
            showReuseMessage = $false
            close            = $false
        }
        problemMatcher = @()
    })
}

$allLabels = $tasks | ForEach-Object { $_.label }

$compound = @{
    label          = "Start All Services"
    dependsOn      = @($allLabels)
    dependsOrder   = "parallel"
    problemMatcher = @()
}

$tasksJson = @{
    version = "2.0.0"
    tasks   = @($tasks + $compound)
}

$vscodeDir = "$ROOT\.vscode"
if (-not (Test-Path $vscodeDir)) {
    New-Item -ItemType Directory -Path $vscodeDir | Out-Null
}

$outputPath = "$vscodeDir\tasks.json"
$tasksJson | ConvertTo-Json -Depth 10 | Set-Content $outputPath -Encoding UTF8

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  tasks.json generated successfully!" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "File written to: $outputPath" -ForegroundColor Green
Write-Host ""
Write-Host "Next steps:" -ForegroundColor White
Write-Host "  1. Open this project in VS Code" -ForegroundColor Gray
Write-Host "  2. Press Ctrl+Shift+P" -ForegroundColor Gray
Write-Host "  3. Type: Tasks: Run Task" -ForegroundColor Gray
Write-Host "  4. Select: Start All Services" -ForegroundColor Gray
Write-Host ""
Write-Host "Each service opens in its own named terminal tab." -ForegroundColor White
foreach ($svc in $services) {
    Write-Host "  - $($svc.name)" -ForegroundColor Gray
}
Write-Host ""
Write-Host "Tip: Run individual services via 'Run: <service-name>'" -ForegroundColor DarkCyan