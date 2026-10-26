$jdkHome = Join-Path $env:USERPROFILE '.jdks\devfield-21\jdk-21.0.12.1+1'
if (-not (Test-Path -LiteralPath (Join-Path $jdkHome 'bin\java.exe'))) {
    throw 'The local DevField Java 21 installation is missing.'
}

$env:JAVA_HOME = $jdkHome
$env:Path = "$(Join-Path $jdkHome 'bin');$env:Path"
