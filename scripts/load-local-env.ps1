$envFile = Join-Path $PSScriptRoot '..\.env.local'
if (-not (Test-Path -LiteralPath $envFile)) {
    throw 'RepoQ/.env.local is missing. Copy .env.example and set local values.'
}

foreach ($line in Get-Content -LiteralPath $envFile) {
    $entry = $line.Trim()
    if ($entry.Length -eq 0 -or $entry.StartsWith('#')) { continue }
    $separator = $entry.IndexOf('=')
    if ($separator -lt 1) { throw 'Invalid local environment entry.' }
    $key = $entry.Substring(0, $separator)
    $value = $entry.Substring($separator + 1)
    if ($key -notmatch '^[A-Z][A-Z0-9_]*$') { throw 'Invalid local environment key.' }
    [Environment]::SetEnvironmentVariable($key, $value, 'Process')
}
