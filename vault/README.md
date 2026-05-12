# confluent-platform-spiffe-vault
Using Vault for Confluent Platform Auth. This example uses Vault to **issue** and **renew/rotate** X.509 certs for mTLS. Vault Agent runs as a side-car and fetches the key and certs from the Vault PKI Secrets Engine to be imported into a PKCS12 format keystore and truststore to be used by Kafka Broker and Clients for mTLS. The **cert verification** and **hostname validation** in this case is still carried out by the CP server SSL Context Engine and not delegated to Vault.

## Steps to setup
## 1. Clone repo and set as working directory
```
git clone https://github.com/nav-nandan/confluent-platform-spiffe.git
cd confluent-platform-spiffe/vault
```

## 2. Verify project structure
```
mkdir kafka-data
ls
client-ssl.properties	config			docker-compose.yml	kafka-data		scripts			shared-secrets
```

## 3. Start up Vault and check logs if service is healthy and ready to issue certificates
```
docker compose up -d vault-server
docker compose logs -f vault-server
```

## 4. Start up Vault Agent and check logs if service has connected to Vault Server and is healthy
```
docker compose up -d vault-agent
docker compose logs -f vault-agent
```

## 5. Check if certs, keystore and truststore are made available under shared-secrets/ by Vault Agent
```
ls shared-secrets
ca.crt			creds			kafka-keystore.p12	kafka-truststore.p12	kafka.crt		kafka.key
```

## 6. Verify imported cert entries in Keystore and Truststore
```
keytool -list -v -keystore kafka-keystore.p12
keytool -list -v -keystore kafka-truststore.p12
```

## 7. Start up cp-server (in KRaft combined mode) with SSL Broker Listener, mTLS for AuthN and hostname verification enabled
```
cd ../..
docker compose up -d kafka
docker compose logs -f kafka
```

## 8. Verify the cluster is up and running with mTLS using kafka-topics
```
kafka-topics --bootstrap-server kafka:9092 \
  --command-config client-ssl.properties \
  --list

__internal_confluent_only_broker_info
```
