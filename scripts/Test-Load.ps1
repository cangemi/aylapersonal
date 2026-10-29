param(
    [string]$BaseUrl = 'http://host.docker.internal:8080',
    [string]$Network = 'ayla_default',
    [ValidateSet('Readiness','Finance')][string]$Mode = 'Readiness',
    [int[]]$Levels = @(5,10,25,50),
    [string]$RampDuration = '15s',
    [string]$HoldDuration = '45s',
    [string]$ReadinessPath = '',
    [ValidateRange(1,60000)][int]$P95Ms = 1500,
    [Security.SecureString]$AccessToken
)
$ErrorActionPreference = 'Stop'
if ($Levels.Count -lt 1 -or $Levels.Count -gt 12 -or @($Levels | Where-Object {$_ -lt 1 -or $_ -gt 1000}).Count -gt 0) {
    throw 'Informe de 1 a 12 etapas com 1 a 1000 usuários virtuais.'
}
if ($RampDuration -notmatch '^\d+(ms|s|m)$' -or $HoldDuration -notmatch '^\d+(ms|s|m)$') { throw 'Use durações como 15s ou 1m.' }
if ($ReadinessPath -and $ReadinessPath -notmatch '^/[A-Za-z0-9/_-]+$') { throw 'Informe ReadinessPath como /readyz ou /actuator/health/readiness, sem domínio ou parâmetros.' }
if ($Mode -eq 'Finance' -and -not $AccessToken) { $AccessToken = Read-Host 'Access token de um usuário de teste (não será exibido)' -AsSecureString }
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskRun = [DateTime]::Now.ToString('yyyyMMdd-HHmmss-fff')
$taskResults = Join-Path $taskRoot ('target/load-tests/' + $taskRun)
$null = New-Item -ItemType Directory -Force -Path $taskResults
$taskVars = @{
    BASE_URL = $BaseUrl; LEVELS = ($Levels -join ','); RAMP_DURATION = $RampDuration
    HOLD_DURATION = $HoldDuration; P95_MS = [string]$P95Ms; ACCESS_TOKEN = $null; K6_NO_COLOR = 'true'
    READINESS_PATH = $ReadinessPath
}
$taskPrevious = @{}
$taskPreviousConsoleEncoding = [Console]::OutputEncoding
$taskPreviousOutputEncoding = $OutputEncoding
try {
    $taskUtf8 = [Text.UTF8Encoding]::new($false)
    [Console]::OutputEncoding = $taskUtf8
    $OutputEncoding = $taskUtf8
    if ($Mode -eq 'Finance') {
        $taskPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($AccessToken)
        try { $taskVars.ACCESS_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskPointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskPointer) }
    }
    foreach ($taskKey in $taskVars.Keys) {
        $taskPrevious[$taskKey] = [Environment]::GetEnvironmentVariable($taskKey,'Process')
        [Environment]::SetEnvironmentVariable($taskKey,$taskVars[$taskKey],'Process')
    }
    Write-Output ('Carga de leitura: ' + ($Levels -join ' → ') + ' usuários virtuais. Modo: ' + $Mode)
    Write-Output 'Acompanhe no Grafana: http://localhost:3300/d/ayla-vps?from=now-15m&to=now&refresh=5s'
    Write-Output 'Interrompa com Ctrl+C. Nenhuma chamada à IA ou gravação financeira será feita.'
    $taskArgs = @('run','--rm','--network',$Network,'--cpus=0.5','--memory=256m',
        '-v',($taskRoot + '/tests/load:/tests:ro'),'-v',($taskResults + ':/results'))
    foreach ($taskKey in $taskVars.Keys) { $taskArgs += @('--env',$taskKey) }
    $taskArgs += @('grafana/k6:1.4.0','run','--summary-export','/results/summary.json','/tests/capacity.js')
    & docker @taskArgs 2>&1 | Tee-Object -FilePath (Join-Path $taskResults 'console.log')
    $taskExit = $LASTEXITCODE
    Write-Output ('Resultados: ' + $taskResults)
    if ($taskExit -eq 107) { throw 'O teste encontrou uma falha na pré-verificação ou no script (107). Isso não indica o limite de capacidade da API; confira o motivo em console.log.' }
    if ($taskExit -eq 99) { throw 'O teste ultrapassou os limites configurados de erros/latência (99). Consulte os resultados e o Grafana.' }
    if ($taskExit -ne 0) { throw "O teste terminou com código $taskExit. Consulte console.log para identificar a causa." }
} finally {
    foreach ($taskKey in $taskPrevious.Keys) { [Environment]::SetEnvironmentVariable($taskKey,$taskPrevious[$taskKey],'Process') }
    $taskVars.ACCESS_TOKEN = $null
    [Console]::OutputEncoding = $taskPreviousConsoleEncoding
    $OutputEncoding = $taskPreviousOutputEncoding
}
