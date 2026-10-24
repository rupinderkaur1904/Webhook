<#
  Deploys Relay to Azure for Students from your laptop (Windows PowerShell 5.1 or PowerShell 7).
  Needs: Azure CLI signed in (az login), the built-in OpenSSH client, and vm-setup.sh in this folder.

    cd <folder with this script>
    powershell -ExecutionPolicy Bypass -File .\deploy-azure.ps1

  Creates in resource group relay-rg: a VNet, a private PostgreSQL 16 Flexible Server (B1ms, 32 GB),
  an Ubuntu 24.04 VM (B2ats_v2 or B1s) with a Standard public IP and DNS name, and an NSG (22 from your IP only,
  80/443 from anywhere). Then runs vm-setup.sh on the VM. Secrets are written only to
  $HOME\relay-secrets (readable by you only) and /opt/relay/.env on the VM; nothing secret is printed.
  Safe to re-run: existing resources are reused.
#>
param(
  [string]$Region = 'centralindia',
  [string]$DnsLabel = 'relay',
  [string]$FallbackDnsLabel = 'relay-portfolio',
  [string]$Email = 'rupinder1904.1@gmail.com'
)
# 'Continue': Windows PowerShell 5.1 turns a native command's stderr into terminating errors under 'Stop'.
# Failures are checked explicitly (AzOk, $LASTEXITCODE) instead.
$ErrorActionPreference = 'Continue'
$Rg = 'relay-rg'
function Step($m) { Write-Host "`n==== $m" -ForegroundColor Cyan }
function AzOk { & az @args; if ($LASTEXITCODE -ne 0) { throw "az $($args[0..2] -join ' ') failed" } }
function NewSecret([int]$n) {
  $chars = [char[]]'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
  $bytes = New-Object byte[] $n
  [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
  -join ($bytes | ForEach-Object { $chars[$_ % $chars.Length] })
}

Step 'Subscription'
$sub = az account show --query name -o tsv
if ($sub -ne 'Azure for Students') { throw "Signed in to '$sub', expected 'Azure for Students'. Run az login." }
Write-Host "Using $sub in $Region"

Step 'Resource providers'
foreach ($ns in 'Microsoft.Network', 'Microsoft.Compute', 'Microsoft.DBforPostgreSQL') {
  if ((az provider show -n $ns --query registrationState -o tsv) -ne 'Registered') { AzOk provider register --namespace $ns --wait -o none }
}

Step 'Region checks'
Write-Host "Region $Region (VM size is chosen when the VM is created)"

Step 'Secrets folder'
$SecretDir = Join-Path $HOME 'relay-secrets'
New-Item -ItemType Directory -Force -Path $SecretDir | Out-Null
icacls $SecretDir /inheritance:r /grant:r "$($env:USERNAME):(OI)(CI)F" | Out-Null
$PgPwFile = Join-Path $SecretDir 'pg-admin.txt'
if (-not (Test-Path $PgPwFile)) { Set-Content -Path $PgPwFile -Value (NewSecret 32) -NoNewline }
$PgPw = (Get-Content $PgPwFile -Raw).Trim()
$PgNameFile = Join-Path $SecretDir 'pg-server-name.txt'
if (-not (Test-Path $PgNameFile)) { Set-Content -Path $PgNameFile -Value ('relay-pg-' + (NewSecret 6).ToLower()) -NoNewline }
$PgName = (Get-Content $PgNameFile -Raw).Trim()
Write-Host "Secrets folder: $SecretDir"

Step 'Resource group and network'
AzOk group create -n $Rg -l $Region -o none
if (-not (az network vnet show -g $Rg -n relay-vnet --query id -o tsv 2>$null)) {
  AzOk network vnet create -g $Rg -n relay-vnet -l $Region --address-prefixes 10.20.0.0/16 --subnet-name vm --subnet-prefixes 10.20.1.0/24 -o none
  AzOk network vnet subnet create -g $Rg --vnet-name relay-vnet -n pg --address-prefixes 10.20.2.0/24 --delegations Microsoft.DBforPostgreSQL/flexibleServers -o none
}
Write-Host 'relay-vnet ready'

Step "PostgreSQL $PgName (about 10 minutes)"
if (-not (az postgres flexible-server show -g $Rg -n $PgName --query id -o tsv 2>$null)) {
  $vnetId = az network vnet show -g $Rg -n relay-vnet --query id -o tsv
  # Pass the private DNS zone by resource ID: newer az versions reject every zone *name* passed here.
  $Zone = 'relaydb.postgres.database.azure.com'
  if (-not (az network private-dns zone show -g $Rg -n $Zone --query id -o tsv 2>$null)) { AzOk network private-dns zone create -g $Rg -n $Zone -o none }
  if (-not (az network private-dns link vnet show -g $Rg -z $Zone -n relay-vnet-link --query id -o tsv 2>$null)) {
    AzOk network private-dns link vnet create -g $Rg -z $Zone -n relay-vnet-link -v relay-vnet -e false -o none
  }
  $zoneId = az network private-dns zone show -g $Rg -n $Zone --query id -o tsv
  AzOk postgres flexible-server create -g $Rg -n $PgName -l $Region --version 16 `
    --tier Burstable --sku-name Standard_B1ms --storage-size 32 `
    --geo-redundant-backup Disabled --backup-retention 7 `
    --vnet $vnetId --subnet pg --private-dns-zone $zoneId `
    --admin-user relayadmin --admin-password $PgPw --yes -o none
}
AzOk postgres flexible-server parameter set -g $Rg --server-name $PgName --name azure.extensions --value CITEXT -o none
$PgHost = az postgres flexible-server show -g $Rg -n $PgName --query fullyQualifiedDomainName -o tsv
Write-Host "PostgreSQL ready: $PgHost (private access only)"

Step 'Firewall rules (NSG)'
$MyIp = (Invoke-RestMethod -Uri 'https://api.ipify.org').Trim()
if (-not (az network nsg show -g $Rg -n relay-nsg --query id -o tsv 2>$null)) { AzOk network nsg create -g $Rg -n relay-nsg -l $Region -o none }
AzOk network nsg rule create -g $Rg --nsg-name relay-nsg -n ssh-my-ip --priority 100 --access Allow --protocol Tcp --direction Inbound --source-address-prefixes "$MyIp/32" --destination-port-ranges 22 -o none
AzOk network nsg rule create -g $Rg --nsg-name relay-nsg -n http --priority 110 --access Allow --protocol Tcp --direction Inbound --source-address-prefixes Internet --destination-port-ranges 80 -o none
AzOk network nsg rule create -g $Rg --nsg-name relay-nsg -n https --priority 120 --access Allow --protocol Tcp --direction Inbound --source-address-prefixes Internet --destination-port-ranges 443 -o none
Write-Host "SSH allowed from $MyIp only; 80/443 from anywhere"

Step 'Public IP and DNS name'
if (-not (az network public-ip show -g $Rg -n relay-ip --query id -o tsv 2>$null)) {
  & az network public-ip create -g $Rg -n relay-ip -l $Region --sku Standard --allocation-method Static --dns-name $DnsLabel -o none
  if ($LASTEXITCODE -ne 0) {
    Write-Host "DNS name '$DnsLabel' is taken; using '$FallbackDnsLabel'"
    AzOk network public-ip create -g $Rg -n relay-ip -l $Region --sku Standard --allocation-method Static --dns-name $FallbackDnsLabel -o none
  }
}
$Fqdn = az network public-ip show -g $Rg -n relay-ip --query dnsSettings.fqdn -o tsv
Write-Host "Address: $Fqdn"

Step 'SSH key for the VM'
# A dedicated key: --generate-ssh-keys fails with 'Incorrect padding' when an existing ~/.ssh key is in another format.
$Key = Join-Path $HOME '.ssh\relay_vm'
if (-not (Test-Path $Key)) {
  New-Item -ItemType Directory -Force -Path (Split-Path $Key) | Out-Null
  Write-Host 'Creating an SSH key. When asked for a passphrase, just press Enter twice.' -ForegroundColor Yellow
  ssh-keygen -t rsa -b 4096 -f $Key -C relay-vm
  if (-not (Test-Path "$Key.pub")) { throw 'ssh-keygen did not create the key' }
}
Write-Host "Key: $Key"

Step 'VM relay-vm (Ubuntu 24.04)'
# Both sizes are in the Azure free-services allowance. B1s often has no capacity in Indian regions, so try
# B2ats_v2 first, then B1s without and with an availability zone.
if (-not (az vm show -g $Rg -n relay-vm --query id -o tsv 2>$null)) {
  $tries = @(@('Standard_B2ats_v2', ''), @('Standard_B1s', ''), @('Standard_B2ats_v2', '1'), @('Standard_B2ats_v2', '2'), @('Standard_B2ats_v2', '3'), @('Standard_B1s', '1'), @('Standard_B1s', '2'), @('Standard_B1s', '3'))
  $made = $false
  foreach ($t in $tries) {
    $zoneArgs = @(); if ($t[1]) { $zoneArgs = @('--zone', $t[1]) }
    Write-Host "Trying size $($t[0])$(if ($t[1]) { " in zone $($t[1])" })"
    & az vm create -g $Rg -n relay-vm -l $Region --image Ubuntu2404 --size $t[0] @zoneArgs `
      --admin-username azureuser --ssh-key-values "$Key.pub" `
      --vnet-name relay-vnet --subnet vm --public-ip-address relay-ip --nsg relay-nsg `
      --os-disk-size-gb 64 --storage-sku Premium_LRS -o none --only-show-errors 2>&1 |
      Select-String -Pattern 'SkuNotAvailable|Code:|Message:' | Select-Object -First 2 | ForEach-Object { Write-Host "  $_" }
    $state = az vm show -g $Rg -n relay-vm --query provisioningState -o tsv 2>$null
    if ($state -eq 'Succeeded') { $made = $true; break }
    if ($state) { Write-Host "  VM ended up '$state'; removing it before the next try"; az vm delete -g $Rg -n relay-vm --yes -o none }
  }
  if (-not $made) { throw "No free VM size has capacity in $Region right now. Try again later." }
}
Write-Host 'VM running'

Step 'Install Relay on the VM'
$Ssh = @('-i', $Key, '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=accept-new', '-o', 'ServerAliveInterval=30')
for ($i = 0; $i -lt 20; $i++) { ssh @Ssh "azureuser@$Fqdn" true 2>$null; if ($LASTEXITCODE -eq 0) { break }; Start-Sleep 10 }
scp @Ssh (Join-Path $PSScriptRoot 'vm-setup.sh') "azureuser@${Fqdn}:/tmp/vm-setup.sh"
if ($LASTEXITCODE -ne 0) { throw 'Could not copy vm-setup.sh to the VM' }
$PgPw | ssh @Ssh "azureuser@$Fqdn" "sed -i 's/\r`$//' /tmp/vm-setup.sh && sudo bash /tmp/vm-setup.sh '$Fqdn' '$Email' '$PgHost'"
if ($LASTEXITCODE -ne 0) { throw 'vm-setup.sh failed; see the output above. Re-running this script is safe.' }

Step 'Back up the two secrets you need'
$Backup = Join-Path $SecretDir 'relay-env-backup.txt'
ssh @Ssh "azureuser@$Fqdn" "sudo grep -E '^(BOOTSTRAP_EMAIL|BOOTSTRAP_PASSWORD|SECRET_ENCRYPTION_KEY)=' /opt/relay/.env" | Set-Content -Path $Backup
Write-Host "Saved to $Backup (dashboard password and encryption key; copy them to a password manager)"

Step 'Closed ports seen from here (both should be False)'
foreach ($p in 5432, 9090) {
  $r = Test-NetConnection $Fqdn -Port $p -WarningAction SilentlyContinue
  Write-Host "port ${p}: $($r.TcpTestSucceeded)"
}

Step "Done"
Write-Host "Relay: https://$Fqdn  (sign in at https://$Fqdn/overview with $Email and the password in $Backup)"
