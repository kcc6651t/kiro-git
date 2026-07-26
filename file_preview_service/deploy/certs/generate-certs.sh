#!/usr/bin/env bash
#
# Generate an internal CA plus mTLS certificates for the file preview system.
#
#   - agent-ca / main-ca: for the MVP a single internal CA signs both sides. You
#     may split into two CAs; adjust the *-ca references accordingly.
#   - main-client: the central service's CLIENT certificate (CN=file-preview-main).
#   - <serverId>-agent: each Agent's SERVER certificate, with the serverId in the
#     CN and the reachable IP/hostname in the SAN.
#
# Requires: openssl. Keys are emitted as PKCS#8 PEM so the Java service can read
# them without BouncyCastle.
#
# Usage:
#   ./generate-certs.sh <serverId> <agent-ip-or-host> [more serverId:ip ...]
#
# Example:
#   ./generate-certs.sh prod-app-01 10.10.1.11 prod-app-02 10.10.1.12
set -euo pipefail

OUT="${OUT_DIR:-./out}"
DAYS="${DAYS:-825}"
mkdir -p "$OUT"

echo ">> Output directory: $OUT"

# ---- 1. Internal CA ---------------------------------------------------------
if [[ ! -f "$OUT/ca.key" ]]; then
  echo ">> Generating internal CA"
  openssl genrsa -out "$OUT/ca.key" 4096
  openssl req -x509 -new -nodes -key "$OUT/ca.key" -sha256 -days 3650 \
    -subj "/CN=file-preview-internal-ca" -out "$OUT/ca.crt"
fi
# Both sides trust this CA in the MVP.
cp "$OUT/ca.crt" "$OUT/agent-ca.crt"
cp "$OUT/ca.crt" "$OUT/main-ca.crt"

sign() {
  # sign <name> <subject-CN> <san>
  local name="$1" cn="$2" san="$3"
  echo ">> Issuing certificate: $name (CN=$cn, SAN=$san)"
  openssl genrsa -out "$OUT/$name.key.rsa" 2048
  # Convert to PKCS#8 (BEGIN PRIVATE KEY) for the Java client / Go agent.
  openssl pkcs8 -topk8 -nocrypt -in "$OUT/$name.key.rsa" -out "$OUT/$name.key"
  rm -f "$OUT/$name.key.rsa"

  openssl req -new -key "$OUT/$name.key" -subj "/CN=$cn" -out "$OUT/$name.csr"
  cat > "$OUT/$name.ext" <<EOF
subjectAltName=$san
extendedKeyUsage=serverAuth,clientAuth
EOF
  openssl x509 -req -in "$OUT/$name.csr" -CA "$OUT/ca.crt" -CAkey "$OUT/ca.key" \
    -CAcreateserial -days "$DAYS" -sha256 -extfile "$OUT/$name.ext" -out "$OUT/$name.crt"
  rm -f "$OUT/$name.csr" "$OUT/$name.ext"
}

# ---- 2. Central service client cert ----------------------------------------
sign "main-client" "file-preview-main" "DNS:file-preview-main"

# ---- 3. Per-agent server certs ---------------------------------------------
if [[ $# -lt 2 ]]; then
  echo "!! No agents specified. Pass pairs: <serverId> <ip> [<serverId> <ip> ...]"
  exit 0
fi

while [[ $# -ge 2 ]]; do
  SERVER_ID="$1"; HOST="$2"; shift 2
  # CN carries the serverId-agent identity the central service pins against.
  if [[ "$HOST" =~ ^[0-9.]+$ ]]; then
    SAN="IP:$HOST,DNS:${SERVER_ID}-agent"
  else
    SAN="DNS:$HOST,DNS:${SERVER_ID}-agent"
  fi
  sign "${SERVER_ID}-agent" "${SERVER_ID}-agent" "$SAN"
done

echo ""
echo ">> Done. Distribute files as follows:"
echo "   Central service (config/certs/):  main-client.crt, main-client.key, agent-ca.crt"
echo "   Each Agent (config/certs/):        <serverId>-agent.crt (as agent-server.crt),"
echo "                                      <serverId>-agent.key (as agent-server.key), main-ca.crt"
echo ""
echo ">> Restrict key permissions: chmod 600 *.key ; own by the running account."
