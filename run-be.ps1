[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $MavenArguments
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$envPath = Join-Path $projectRoot '.env'
$mavenWrapper = Join-Path $projectRoot 'mvnw.cmd'

if (-not (Test-Path -LiteralPath $envPath)) {
    throw "Không tìm thấy file .env: $envPath"
}

if (-not (Test-Path -LiteralPath $mavenWrapper)) {
    throw "Không tìm thấy Maven Wrapper: $mavenWrapper"
}

Get-Content -LiteralPath $envPath | ForEach-Object {
    $line = $_.Trim()

    if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) {
        return
    }

    $separatorIndex = $line.IndexOf('=')
    if ($separatorIndex -lt 1) {
        throw "Dòng .env không hợp lệ: $line"
    }

    $name = $line.Substring(0, $separatorIndex).Trim()
    $value = $line.Substring($separatorIndex + 1).Trim()

    if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
        ($value.StartsWith("'") -and $value.EndsWith("'"))) {
        $value = $value.Substring(1, $value.Length - 2)
    }

    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}

Push-Location $projectRoot
try {
    & $mavenWrapper 'spring-boot:run' @MavenArguments
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}
finally {
    Pop-Location
}
