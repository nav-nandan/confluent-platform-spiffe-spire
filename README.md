# confluent-platform-spiffe-spire
Using SPIRE for Confluent Platform Auth. Well, not entirely. This example uses a SPIRE Server to **issue** and **renew/rotate** X.509 cert bundle for mTLS. SPIRE Agent and SPIFFE helper fetch the cert chain **.pem** and extract CA, key and certs to be imported into a PKCS12 format keystore and truststore to be used by Kafka Broker and Clients for mTLS. The **cert verification** and **hostname validation** in this case is still carried out by the CP server SSL Context Engine and not delegated to the SPIRE Server.

## Steps to setup
## 1. Clone repo and set as working directory
```
git clone https://github.com/nav-nandan/confluent-platform-spiffe-spire.git
cd confluent-platform-spiffe-spire
```

## 2. Create project structure
```
mkdir spire/data
mkdir spire/agent/run
mkdir kafka-data
mkdir kafka
mkdir kafka/secrets
```

## 3. Start up SPIRE Server and check logs if service is healthy and ready to issue X.509 certificates
```
docker compose up -d spire-server
docker compose logs -f spire-server
```

## 4. Generate bootstrap.crt to be used by SPIRE Agent
```
docker compose exec spire-server \
  /opt/spire/bin/spire-server bundle show -format pem \
  > spire/agent/bootstrap.crt
```

## 5. Generate token to be used by SPIRE Agent
```
docker compose exec -T spire-server \
  /opt/spire/bin/spire-server token generate \
    -spiffeID spiffe://confluent.io/agent/kafka
```

This should return something like:
```
Token: eabe1a4d-4f2b-449d-b363-c7186589145a
```

## 6. Pass token via env variable
```
echo "JOIN_TOKEN=<copy-and-paste-output-token-from-previous-step" >> .env
cat .env
```

## 7. Start up SPIRE Agent and check logs if service has connected to SPIRE Server and is healthy
```
docker compose up -d spire-agent
docker compose logs -f spire-agent
```

## 8. Create identity for cp-server using hostname to include SVID and DN as part of SAN
```
docker compose exec spire-server \
  /opt/spire/bin/spire-server entry create \
    -spiffeID spiffe://confluent.io/kafka \
    -parentID spiffe://confluent.io/agent/kafka \
    -selector unix:uid:0 \
    -dns kafka \
    -x509SVIDTTL 3600
```

This should return something like:
```
Entry ID         : bc5ffa48-21c3-409d-8e35-85d12a81f6b4
SPIFFE ID        : spiffe://confluent.io/kafka
Parent ID        : spiffe://confluent.io/agent/kafka
Revision         : 0
X509-SVID TTL    : 3600
JWT-SVID TTL     : default
Selector         : unix:uid:0
DNS name         : kafka
```

## 9. Start up SPIFFE helper to use Trust Bundle (.pem) fetched by SPIRE Agent from SPIRE Server
```
docker compose up -d spiffe-helper-kafka
docker compose logs -f spiffe-helper-kafka
ls -l kafka/secrets
```

## 10. Check if certs are available under kafka/secrets and import certs (CA, kafka and leaf (used as client cert) from cert chain) into Keystore and Truststore
```
cd kafka/secrets

echo 'changeit' > creds

# Build PKCS12 keystore: key + cert + bundle
openssl pkcs12 -export \
  -inkey kafka-key.pem \
  -in kafka-cert.pem \
  -certfile kafka-bundle.pem \
  -name kafka-svid \
  -out kafka-keystore.p12 \
  -password pass:changeit

# Build PKCS12 truststore from the SPIRE bundle (Root CA)
keytool -importcert -noprompt \
  -alias spire-bundle \
  -file kafka-bundle.pem \
  -keystore kafka-truststore.p12 \
  -storetype PKCS12 \
  -storepass changeit

# Export leaf cert from keystore
keytool -exportcert -rfc \
  -alias kafka-svid \
  -keystore kafka-keystore.p12 \
  -storetype PKCS12 \
  -storepass changeit \
  -file kafka-leaf.pem

# Import leaf cert into truststore as another trustedCertEntry
keytool -importcert -noprompt \
  -alias kafka-leaf \
  -file kafka-leaf.pem \
  -keystore kafka-truststore.p12 \
  -storetype PKCS12 \
  -storepass changeit

ls -l 
```

## 11. Verify imported cert entries in Keystore and Truststore
```
keytool -list -v -keystore kafka-keystore.p12
keytool -list -v -keystore kafka-truststore.p12
```

## 12. Start up cp-server (in KRaft combined mode) with SSL Broker Listener, mTLS for AuthN and hostname verification enabled
```
cd ../..
docker compose up -d kafka
docker compose logs -f kafka
```

## 13. Verify the cluster is up and running with mTLS using kcat (librdkafka client)
```
kcat -b kafka:9092 -L \
  -X security.protocol=SSL \
  -X ssl.ca.location=./kafka/secrets/kafka-bundle.pem \
  -X ssl.certificate.location=./kafka/secrets/kafka-cert.pem \
  -X ssl.key.location=./kafka/secrets/kafka-key.pem \
  -X enable.ssl.certificate.verification=true
```

This should return something like:
```
Metadata for all topics (from broker 1: ssl://kafka:9092/1):
 1 brokers:
  broker 1 at kafka:9092 (controller)
 0 topics:
```

## 14. Verify Java-based client with mTLS
```
kafka-topics --bootstrap-server kafka:9092 \
  --command-config client-ssl.properties \
  --create --topic test --partitions 3 --replication-factor 1

kafka-topics --bootstrap-server kafka:9092 \
  --command-config client-ssl.properties \
  --list
```
