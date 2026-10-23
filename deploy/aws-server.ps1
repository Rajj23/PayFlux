[CmdletBinding()]
param(
    [string]$Region = "us-east-1",
    [ValidateSet("m7i-flex.large")]
    [string]$InstanceType = "m7i-flex.large",
    [string]$SshCidr,
    [string]$PublicKeyPath = "$HOME\.ssh\payflux-server-ed25519.pub"
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$TerraformDir = Join-Path $RepoRoot "terraform\server"
$VarFile = Join-Path $RepoRoot "deploy\server-config.tfvars"
$PrivateKeyPath = $PublicKeyPath -replace '\.pub$', ''

function Require-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        if ($Name -eq "terraform") {
            throw "Terraform is not installed. Install the Terraform CLI, add terraform.exe to PATH, and retry."
        }
        throw "Required command '$Name' was not found. Install it, then retry."
    }
}

function Get-PublicIPv4 {
    try {
        $Response = Invoke-RestMethod -Uri "https://checkip.amazonaws.com" -TimeoutSec 15
        return "$($Response.ToString().Trim())/32"
    }
    catch {
        throw "Cannot determine your public IPv4 automatically. Retry with -SshCidr YOUR.PUBLIC.IP/32."
    }
}

Require-Command "aws"
Require-Command "terraform"
Require-Command "ssh-keygen"

$Identity = aws sts get-caller-identity --output json | ConvertFrom-Json
if (-not $Identity.Account) { throw "AWS CLI could not retrieve the configured account identity." }
Write-Host "Using AWS account $($Identity.Account), region $Region"
Write-Host "The selected EC2 host runs Kafka, PostgreSQL, and Redis for the EKS workloads."

if (-not (Test-Path $PublicKeyPath)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $PublicKeyPath) | Out-Null
    ssh-keygen -t ed25519 -C "payflux-server" -f $PrivateKeyPath
}
if (-not (Test-Path $PublicKeyPath)) { throw "SSH public key does not exist: $PublicKeyPath" }

if (-not $SshCidr) { $SshCidr = Get-PublicIPv4 }
try { $ParsedAddress = [System.Net.IPAddress]::Parse(($SshCidr -split "/")[0]) }
catch { throw "SshCidr '$SshCidr' is not a valid IPv4 CIDR." }
if ($ParsedAddress.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork -or $SshCidr -notmatch "/32$") {
    throw "SshCidr must be a single IPv4 address with a /32 mask; got '$SshCidr'."
}

$PublicKey = (Get-Content -Raw $PublicKeyPath).Trim()
if ($PublicKey -notmatch "^ssh-(rsa|ed25519) ") { throw "Expected an OpenSSH ssh-rsa or ssh-ed25519 public key in $PublicKeyPath" }
if (-not (Test-Path $PrivateKeyPath)) { throw "Private SSH key is missing: $PrivateKeyPath" }

New-Item -ItemType Directory -Force -Path (Split-Path $VarFile) | Out-Null
$KeyJson = ConvertTo-Json -Compress -InputObject $PublicKey
$CidrJson = ConvertTo-Json -Compress -InputObject $SshCidr
$RegionJson = ConvertTo-Json -Compress -InputObject $Region
$InstanceJson = ConvertTo-Json -Compress -InputObject $InstanceType
$PublicKeyPathJson = ConvertTo-Json -Compress -InputObject $PublicKeyPath
$Contents = @"
aws_region     = $RegionJson
instance_type  = $InstanceJson
ssh_cidr       = $CidrJson
ssh_public_key = $KeyJson
ssh_public_key_path = $PublicKeyPathJson
"@
Set-Content -Path $VarFile -Value $Contents -Encoding utf8NoBOM

Push-Location $TerraformDir
try {
    terraform init -input=false
    if ($LASTEXITCODE -ne 0) { throw "terraform init failed." }
    terraform validate
    if ($LASTEXITCODE -ne 0) { throw "terraform validate failed." }
    Write-Host "Reviewing the Terraform plan. No cloud resources will be created until you confirm at the prompt."
    terraform plan -input=false -var-file=$VarFile
    if ($LASTEXITCODE -ne 0) { throw "terraform plan failed. No infrastructure was applied." }

    $Confirm = Read-Host "Create the $InstanceType EC2 host and networking above? This starts AWS billing. Type YES to continue"
    if ($Confirm -cne "YES") { throw "Cancelled. No infrastructure has been applied." }

$ExpectedInstanceHourly = switch ($InstanceType) {
    "m7i-flex.large" { 0.1008 }
}
Write-Host ("Published us-east-1 Linux On-Demand reference for {0}: ${1:N4}/hour, roughly ${2:N2} per 730-hour month before disk, public IPv4, data, taxes, and any Free-plan credits. Verify account pricing and credits first." -f $InstanceType, $ExpectedInstanceHourly, ($ExpectedInstanceHourly * 730))
$FinalCostConfirm = Read-Host "This plan launches a billed $InstanceType server. Type COST to acknowledge and continue"
if ($FinalCostConfirm -cne "COST") { throw "Cancelled. No infrastructure has been applied." }

    terraform apply -input=false -auto-approve -var-file=$VarFile
    if ($LASTEXITCODE -ne 0) { throw "terraform apply failed. Check AWS console and Terraform state before retrying." }

    $ServerIp = terraform output -raw server_public_ip
    if ($LASTEXITCODE -ne 0) { throw "Failed to read Terraform server_public_ip output." }

    Write-Host "Infrastructure is ready. Start the EC2 data services with deploy.sh, then apply k8s/ after publishing GHCR images."
    Write-Host "Server public IP: $ServerIp"
    Write-Host "Private SSH key: $PrivateKeyPath"
    Write-Host "Keep that key secret. To remove infrastructure later, inspect the plan and run: terraform -chdir=terraform/server destroy -var-file='$VarFile'"
}
finally {
    Pop-Location
}