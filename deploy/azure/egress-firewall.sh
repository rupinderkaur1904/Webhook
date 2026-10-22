#!/usr/bin/env bash
# Outbound limits for the Relay containers, enforced by the VM's kernel rather than the application.
#
# The platform already refuses private, loopback, link-local and reserved destinations (docs/SECURITY.md).
# This is the second layer for what it allows: any port, and public addresses that reach cloud-internal
# services. Traffic leaving the Compose subnet may only go to:
#   - the Azure DNS resolver (168.63.129.16) on port 53;
#   - the PostgreSQL subnet on port 5432;
#   - public addresses on TCP 80 and 443.
# Everything else is rejected, including private and link-local ranges (instance metadata
# 169.254.169.254), the rest of the Azure platform address 168.63.129.16, other ports, and UDP.
# Replies to inbound connections (visitors talking to Caddy) are not affected.
# The containers also may not open connections to the VM itself (SSH, anything listening on the host):
# that traffic goes to the INPUT chain, not through Docker, so it gets a chain of its own.
#
#   sudo ./egress-firewall.sh            # apply (idempotent)
#   sudo ./egress-firewall.sh --remove   # take the rules out
#   sudo ./egress-firewall.sh --show     # list the rules with packet counters
#
# Settings (environment variables):
#   RELAY_SUBNET   Compose network, default 172.30.0.0/24 (compose.yml)
#   DB_SUBNET      delegated PostgreSQL subnet, default 10.20.2.0/24 (docs/DEPLOYMENT-AZURE.md)
#   DNS_RESOLVER   default 168.63.129.16 (Azure-provided DNS)
#
# Docker keeps the DOCKER-USER chain across restarts but this script's rules are not saved by the
# kernel: relay-egress.service re-applies them at boot, after Docker starts.
set -euo pipefail

RELAY_SUBNET="${RELAY_SUBNET:-172.30.0.0/24}"
DB_SUBNET="${DB_SUBNET:-10.20.2.0/24}"
DNS_RESOLVER="${DNS_RESOLVER:-168.63.129.16}"
CHAIN=RELAY-EGRESS
HOST_CHAIN=RELAY-HOST
IPT="iptables -w"

BLOCKED=(
  0.0.0.0/8 10.0.0.0/8 100.64.0.0/10 127.0.0.0/8 169.254.0.0/16 172.16.0.0/12 192.0.0.0/24
  192.0.2.0/24 192.168.0.0/16 198.18.0.0/15 198.51.100.0/24 203.0.113.0/24 224.0.0.0/4 240.0.0.0/4
  168.63.129.16/32
)

jump=(DOCKER-USER -s "$RELAY_SUBNET" ! -d "$RELAY_SUBNET" -j "$CHAIN")
host_jump=(INPUT -s "$RELAY_SUBNET" -j "$HOST_CHAIN")

remove() {
  while $IPT -C "${jump[@]}" 2>/dev/null; do $IPT -D "${jump[@]}"; done
  while $IPT -C "${host_jump[@]}" 2>/dev/null; do $IPT -D "${host_jump[@]}"; done
  local c
  for c in "$CHAIN" "$HOST_CHAIN"; do
    if $IPT -n -L "$c" >/dev/null 2>&1; then
      $IPT -F "$c"
      $IPT -X "$c"
    fi
  done
}

case "${1:-}" in
  --remove) remove; echo "Removed $CHAIN."; exit 0 ;;
  --show) $IPT -n -v -L "$CHAIN"; $IPT -n -v -L "$HOST_CHAIN"; exit 0 ;;
  "") ;;
  *) echo "usage: $0 [--remove|--show]" >&2; exit 2 ;;
esac

[[ $EUID -eq 0 ]] || { echo "Run as root (sudo)." >&2; exit 1; }
$IPT -n -L DOCKER-USER >/dev/null 2>&1 || { echo "No DOCKER-USER chain: start Docker first." >&2; exit 1; }

$IPT -N "$CHAIN" 2>/dev/null || true
$IPT -F "$CHAIN"
# Replies on connections that are already allowed (including visitors' connections to Caddy).
$IPT -A "$CHAIN" -m conntrack --ctstate ESTABLISHED,RELATED -j RETURN
# Name resolution through the VM's resolver (also resolves the database's private DNS name).
$IPT -A "$CHAIN" -d "$DNS_RESOLVER" -p udp --dport 53 -j RETURN
$IPT -A "$CHAIN" -d "$DNS_RESOLVER" -p tcp --dport 53 -j RETURN
# The database.
$IPT -A "$CHAIN" -d "$DB_SUBNET" -p tcp --dport 5432 -j RETURN
# Private, link-local, platform and reserved destinations.
for cidr in "${BLOCKED[@]}"; do
  $IPT -A "$CHAIN" -d "$cidr" -p tcp -j REJECT --reject-with tcp-reset
  $IPT -A "$CHAIN" -d "$cidr" -j REJECT --reject-with icmp-admin-prohibited
done
# Webhook receivers, Let's Encrypt and certificate status checks: public HTTP(S) only.
$IPT -A "$CHAIN" -p tcp -m multiport --dports 80,443 -j RETURN
# Anything else: other ports, UDP (including QUIC), ICMP.
$IPT -A "$CHAIN" -p tcp -j REJECT --reject-with tcp-reset
$IPT -A "$CHAIN" -j REJECT --reject-with icmp-admin-prohibited

# Connections from the containers to the VM itself (its SSH daemon, the bridge gateway address).
$IPT -N "$HOST_CHAIN" 2>/dev/null || true
$IPT -F "$HOST_CHAIN"
$IPT -A "$HOST_CHAIN" -m conntrack --ctstate ESTABLISHED,RELATED -j RETURN
$IPT -A "$HOST_CHAIN" -p tcp -j REJECT --reject-with tcp-reset
$IPT -A "$HOST_CHAIN" -j REJECT --reject-with icmp-admin-prohibited

# Hook the chains in once, at the top of DOCKER-USER and INPUT.
$IPT -C "${jump[@]}" 2>/dev/null || $IPT -I "${jump[@]}"
$IPT -C "${host_jump[@]}" 2>/dev/null || $IPT -I "${host_jump[@]}"
echo "Applied $CHAIN for $RELAY_SUBNET (database $DB_SUBNET, resolver $DNS_RESOLVER)."
