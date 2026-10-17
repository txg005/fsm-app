# Run directly to prepare Android Studio without starting a JVM.
# Windows PowerShell 5.1, no administrator privileges or policy changes needed.
$ErrorActionPreference = 'Stop'
$jar = Join-Path $PSScriptRoot 'gradle-wrapper.jar'
$temp = $null
try {
    $expected = (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'gradle-wrapper.jar.sha256') -Raw).Trim()
    if ($expected -notmatch '^[0-9a-f]{64}$') { throw 'Invalid pinned wrapper SHA-256.' }
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        $url = (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'gradle-wrapper.jar.url') -Raw).Trim()
        if (-not $url.StartsWith('https://raw.githubusercontent.com/gradle/gradle/')) {
            throw 'Wrapper source must be the official Gradle repository over HTTPS.'
        }
        $temp = Join-Path $PSScriptRoot ('.wrapper-' + [Guid]::NewGuid().ToString('N') + '.tmp')
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $temp -TimeoutSec 60
        if ((Get-FileHash -LiteralPath $temp -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
            throw 'Downloaded wrapper SHA-256 mismatch. Refusing installation.'
        }
        Move-Item -LiteralPath $temp -Destination $jar
    }
    if ((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
        throw 'Local wrapper SHA-256 mismatch. Refusing to run Java.'
    }
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
} finally {
    if ($temp -and (Test-Path -LiteralPath $temp)) { Remove-Item -LiteralPath $temp -Force }
}
