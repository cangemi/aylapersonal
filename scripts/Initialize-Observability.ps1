$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskEnvPath = Join-Path $taskRoot '.env'
if (-not (Test-Path -LiteralPath $taskEnvPath)) { throw 'Crie e configure .env a partir de .env.example antes de continuar.' }
$taskEnvText = [IO.File]::ReadAllText($taskEnvPath)
function Ensure-Setting([string]$name, [string]$value) {
    $pattern = '(?m)^' + [regex]::Escape($name) + '=([^\r\n]*)'
    $found = [regex]::Match($script:taskEnvText, $pattern)
    if ($found.Success -and $found.Groups[1].Value.Trim().Trim('"', "'").Length -gt 0) {
        return $found.Groups[1].Value.Trim().Trim('"', "'")
    }
    if ($found.Success) { $script:taskEnvText = [regex]::Replace($script:taskEnvText, $pattern, ($name + '=' + $value)) }
    else { $script:taskEnvText += "`n$name=$value`n" }
    return $value
}
function New-Secret { return [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant() }
$taskAdmin = Ensure-Setting 'GRAFANA_ADMIN_USER' 'superadmin'
$null = Ensure-Setting 'GRAFANA_ADMIN_PASSWORD' (New-Secret)
$taskToken = Ensure-Setting 'AYLA_METRICS_SCRAPE_KEY' (New-Secret)
if ($taskToken.Length -lt 32) { throw 'AYLA_METRICS_SCRAPE_KEY deve ter pelo menos 32 caracteres.' }
[IO.File]::WriteAllText($taskEnvPath, $taskEnvText)
$taskSecretDir = Join-Path $taskRoot 'docker/observability/secrets'
$null = New-Item -ItemType Directory -Force -Path $taskSecretDir
[IO.File]::WriteAllText((Join-Path $taskSecretDir 'metrics-token'), $taskToken)
Write-Output "Grafana: usuário $taskAdmin. Senha preservada/gerada em .env (GRAFANA_ADMIN_PASSWORD)."
Write-Output 'Chave de coleta preparada sem exibir credenciais.'
