param([Parameter(Mandatory=$true)][string]$ExePath)
$ErrorActionPreference = 'Stop'
$utf8 = New-Object Text.UTF8Encoding($false)
# Windows PowerShell 5.1 emits a JSON array as a single pipeline object.
# Assign it first, then enumerate it directly instead of wrapping the pipeline.
$parsedEntries = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'GeneratedWebData/payload-files.json') -Raw | ConvertFrom-Json
$entries = @($parsedEntries)
if (!$entries.Count) { throw 'No web files to package.' }
$inputStream = $null
$outputStream = $null
$reader = $null
$writer = $null
$ExePath = [IO.Path]::GetFullPath($ExePath)
$backup = $ExePath + '.backup-' + [Guid]::NewGuid().ToString('N')
$tmp = $ExePath + '.payload-' + [Guid]::NewGuid().ToString('N')
try {
    $inputStream = [IO.File]::Open($ExePath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    $reader = New-Object IO.BinaryReader($inputStream)
    [long]$baseLength = $inputStream.Length
    # Remove a previous payload when an incremental build skips linking.
    if ($baseLength -ge 24) {
        $inputStream.Position = $baseLength - 24
        $magic = [Text.Encoding]::ASCII.GetString($reader.ReadBytes(8))
        if ($magic -eq 'NHTMLPK1') {
            [System.UInt64]$manifestOffset = $reader.ReadUInt64()
            [System.UInt64]$manifestSize = $reader.ReadUInt64()
            if ($manifestSize -gt 2147483647 -or $manifestOffset -gt [System.UInt64]($baseLength - 24) -or $manifestSize -ne [System.UInt64]($baseLength - 24) - $manifestOffset) { throw 'Invalid previous payload.' }
            $inputStream.Position = [long]$manifestOffset
            $oldManifest = $utf8.GetString($reader.ReadBytes([int]$manifestSize))
            $offsets = @($oldManifest.Split([char]10) | Select-Object -Skip 1 | Where-Object { $_ } | ForEach-Object {
                $parts = $_.Split([char]9)
                if ($parts.Count -ne 3) { throw 'Invalid previous manifest.' }
                [long]::Parse($parts[1], [Globalization.CultureInfo]::InvariantCulture)
            })
            if (!$offsets.Count) { throw 'Previous payload has no files.' }
            $baseLength = ($offsets | Measure-Object -Minimum).Minimum
            if ($baseLength -lt 64 -or $baseLength -gt [long]$manifestOffset) { throw 'Invalid original EXE size.' }
        }
    }
    $inputStream.Position = 0
    $outputStream = [IO.File]::Open($tmp, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    $writer = New-Object IO.BinaryWriter($outputStream)
    $buffer = New-Object byte[] 1048576
    [long]$remaining = $baseLength
    while ($remaining -gt 0) {
        $count = $inputStream.Read($buffer, 0, [int][Math]::Min($remaining, $buffer.Length))
        if ($count -eq 0) { throw 'Unexpected end of EXE.' }
        $outputStream.Write($buffer, 0, $count)
        $remaining -= $count
    }
    $manifest = New-Object Text.StringBuilder
    [void]$manifest.Append("https://noon.invalid/dir.html`n")
    foreach ($entry in $entries) {
        if ($entry.path.Contains("`t") -or $entry.path.Contains("`n") -or $entry.path.Contains("`r")) { throw 'Unsupported control character in web filename.' }
        if ($entry.source -isnot [string] -or $entry.path -isnot [string]) { throw 'Invalid payload file entry: source and path must be strings.' }
        $source = Join-Path -Path $PSScriptRoot -ChildPath $entry.source
        $web = [IO.File]::OpenRead($source)
        try {
            if ($web.Length -gt 2147483647) { throw "Web file exceeds 2GB: $source" }
            [void]$manifest.Append($entry.path).Append("`t").Append($outputStream.Position.ToString([Globalization.CultureInfo]::InvariantCulture)).Append("`t").Append($web.Length.ToString([Globalization.CultureInfo]::InvariantCulture)).Append("`n")
            $web.CopyTo($outputStream)
        } finally { $web.Dispose() }
    }
    [System.UInt64]$indexOffset = $outputStream.Position
    $index = $utf8.GetBytes($manifest.ToString())
    $writer.Write($index)
    $writer.Write([Text.Encoding]::ASCII.GetBytes('NHTMLPK1'))
    $writer.Write([System.UInt64]$indexOffset)
    $writer.Write([System.UInt64]$index.Length)
    $writer.Flush()
    $outputStream.Flush($true)
    $writer.Dispose(); $writer = $null; $outputStream = $null
    $reader.Dispose(); $reader = $null; $inputStream = $null
    # Supply a real backup path: Windows PowerShell can bind $null as an
    # empty string for this .NET string parameter, causing Replace to fail.
    [IO.File]::Replace($tmp, $ExePath, $backup)
    try { [IO.File]::Delete($backup) } catch { Write-Warning "Packaged EXE saved; old EXE backup remains at $backup" }
    Write-Host "Appended $($entries.Count) web files directly to EXE. No web RCDATA resources."
} catch {
    [Console]::Error.WriteLine("Web packaging failed: " + $_.Exception.Message)
    [Console]::Error.WriteLine($_.ScriptStackTrace)
    throw
} finally {
    if ($writer) { $writer.Dispose() } elseif ($outputStream) { $outputStream.Dispose() }
    if ($reader) { $reader.Dispose() } elseif ($inputStream) { $inputStream.Dispose() }
    if ([IO.File]::Exists($tmp)) { [IO.File]::Delete($tmp) }
}
