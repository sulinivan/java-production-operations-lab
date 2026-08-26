# Generate self-signed SSL certificates for localhost on Windows PowerShell
Write-Host "Generating self-signed SSL certificates for localhost..."
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

if (Get-Command openssl -ErrorAction SilentlyContinue) {
    $env:OPENSSL_CONF=""
    openssl req -x509 -nodes -days 365 -newkey rsa:2048 `
      -keyout "$scriptDir/localhost.key" `
      -out "$scriptDir/localhost.crt" `
      -subj "/C=US/ST=State/L=City/O=Organization/CN=localhost"
    Write-Host "Certificates generated successfully using OpenSSL: localhost.crt and localhost.key"
} else {
    Write-Warning "OpenSSL command line tool was not found in your PATH."
    Write-Host "Please install OpenSSL (e.g. via 'winget install Sharkoon.OpenSSL' or Git for Windows) or run generate-certs.sh in Git Bash / WSL to generate the required PEM key and certificate files." -ForegroundColor Red
}
