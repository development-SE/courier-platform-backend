$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

cmd /c api-gateway\mvnw.cmd -f pom.xml install -DskipTests
docker compose build
docker compose up -d
docker compose ps
