[CmdletBinding()]
param(
    [string]$Region = "us-east-1",
    [ValidateSet("t3.large", "t3.xlarge", "t3.2xlarge")]
    [string]$InstanceType = "t3.xlarge",
    [string]$SshCidr,
    [string]$PublicKeyPath = "$HOME\.ssh\payflux-server-ed25519.pub",
    [string]$Domain = ""
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
Write-Host "AWS Free plan can restrict this launch. AWS Free plan also does not include MSK; this deployment runs Kafka locally in Docker."
Write-Host "The selected $InstanceType EC2 instance and storage are billable. Confirm the expected cost before continuing."

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
    "t3.large" { 0.0832 }
    "t3.xlarge" { 0.1664 }
    "t3.2xlarge" { 0.3328 }
}
Write-Host ("Published us-east-1 Linux On-Demand reference for {0}: ${1:N4}/hour, roughly ${2:N2} per 730-hour month before disk, public IPv4, data, taxes, and any Free-plan credits. Verify account pricing and credits first." -f $InstanceType, $ExpectedInstanceHourly, ($ExpectedInstanceHourly * 730))
$FinalCostConfirm = Read-Host "This plan launches a billed $InstanceType server. Type COST to acknowledge and continue"
if ($FinalCostConfirm -cne "COST") { throw "Cancelled. No infrastructure has been applied." }

    terraform apply -input=false -auto-approve -var-file=$VarFile
    if ($LASTEXITCODE -ne 0) { throw "terraform apply failed. Check AWS console and Terraform state before retrying." }

    $ServerIp = terraform output -raw server_public_ip
    if ($LASTEXITCODE -ne 0) { throw "Failed to read Terraform server_public_ip output." }

    $RepoUrl = "https://github.com/Rajj23/PayPal-Clone.git"
    Write-Host "Waiting for the server to finish installing Docker (up to 10 minutes)..."
    $Ready = $false
    for ($Attempt = 0; $Attempt -lt 30; $Attempt++) {
        try {
            $null = ssh -o BatchMode=yes -o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new -i $PrivateKeyPath "ubuntu@$ServerIp" "sudo test -x /usr/bin/docker && docker compose version >/dev/null"
            if ($LASTEXITCODE -eq 0) { $Ready = $true; break }
        } catch { }
        Start-Sleep -Seconds 20
    }
    if (-not $Ready) {
        throw "Server created at $ServerIp, but Docker did not become available in time. Check cloud-init at /var/log/cloud-init-output.log; do not run terraform apply again."
    }

    # Bootstrap script creates /opt/payflux for ubuntu. Clone as ubuntu to keep
    # the checkout writable by the SSH user and preserve the private server env.
    $null = ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -i $PrivateKeyPath "ubuntu@$ServerIp" "git clone --branch main --depth 1 '$RepoUrl' /opt/payflux"
    if ($LASTEXITCODE -ne 0) { throw "Remote source checkout failed. The server is still available at $ServerIp; inspect it before retrying." }
    $DomainArgs = ""
    if ($Domain) {
        Write-Host "Point DNS A record $Domain to $ServerIp before automatic TLS provisioning."
        $DomainArgs = "DEPLOY_PROFILE=tls APP_DOMAIN='$Domain'"
    }
    $RemoteCommand = "sudo env $DomainArgs bash /opt/payflux/deploy.sh"
    $null = ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -i $PrivateKeyPath "ubuntu@$ServerIp" $RemoteCommand
    if ($LASTEXITCODE -ne 0) { throw "App deploy failed. SSH to $ServerIp and inspect /opt/payflux logs before retrying." }

    Write-Host "PayFlux is deployed. Server IP: $ServerIp"
    if ($Domain) { Write-Host "Application URL: https://$Domain" }
    else { Write-Host "Application URL: http://$ServerIp" }
    Write-Host "Private SSH key: $PrivateKeyPath"
    Write-Host "Keep that key secret. To remove infrastructure later, inspect the plan and run: terraform -chdir=terraform/server destroy -var-file='$VarFile'"
}
finally {
    Pop-Location
}