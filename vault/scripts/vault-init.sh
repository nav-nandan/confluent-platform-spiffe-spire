#!/bin/sh
set -e

export VAULT_ADDR="http://127.0.0.1:8200"
export VAULT_TOKEN="root"

# 1. Setup PKI (Ensure CA is generated)
if ! vault secrets list | grep -q "pki/"; then
    vault secrets enable pki
    vault secrets tune -max-lease-ttl=87600h pki
    vault write pki/root/generate/internal common_name="kafka-ca" ttl=87600h
fi

# 2. Configure the Kafka Role in PKI
vault write pki/roles/kafka-role \
    allowed_domains="kafka,localhost" \
    allow_subdomains=true \
    allow_bare_domains=true \
    allow_localhost=true \
    allow_ip_sans=true \
    allow_uri_sans=true \
    allowed_uri_sans="spiffe://confluent.io/*" \
    allow_any_name=true \
    max_ttl="72h"

# 3. Create a Policy for the Agent
vault policy write kafka-policy - <<EOF
path "pki/issue/kafka-role" {
  capabilities = ["update"]
}
path "pki/cert/ca" {
  capabilities = ["read"]
}
EOF

# 4. Idempotent AppRole Enable
if ! vault auth list | grep -q "approle/"; then
    vault auth enable approle
fi

# 5. Setup Agent Role with the NEW policy
vault write auth/approle/role/agent-role \
    secret_id_ttl=10h \
    token_ttl=1h \
    token_max_ttl=4h \
    policies="kafka-policy" # Attach the policy here

# 6. Generate Credentials
# Fetch IDs using Vault's native -field flag instead of jq
export ROLE_ID=$(vault read -field=role_id auth/approle/role/agent-role/role-id)
export SECRET_ID=$(vault write -f -field=secret_id auth/approle/role/agent-role/secret-id)

if [ -z "$ROLE_ID" ]; then
    echo "ERROR: Failed to fetch RoleID from Vault"
    exit 1
fi

echo "$ROLE_ID" > /scripts/roleid
echo "$SECRET_ID" > /scripts/secretid

echo "Vault initialization complete."
