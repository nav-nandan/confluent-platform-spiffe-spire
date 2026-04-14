# Spire Kafka Principal Builder

PROOF OF CONCEPT, DO NOT USE

The API for the KafkaPrincipalBuilder interface looks like it may have changed between 4.0, 4.1 and 4.2; this is written for 4.1.

Custom `KafkaPrincipalBuilder` implementation for Apache Kafka that extracts principals from X.509 certificates, with support for SPIFFE IDs.

## Building

```bash
cd spire
mvn clean package
```

This will create `target/spire-kafka-auth-1.0.0-SNAPSHOT.jar`

## Deployment

### Option 1: Add JAR to Kafka Classpath

1. Copy the built JAR to your Kafka installation:
   ```bash
   cp target/spire-kafka-auth-1.0.0-SNAPSHOT.jar /path/to/kafka/libs/
   ```

2. The Kafka broker will automatically load JARs from the `libs/` directory.

### Option 2: Docker Image

1. Docker build
   ```bash
   docker build . -t <some-tag>
   ```

## Configuration

Add the following to your Kafka broker's `server.properties`:

```properties
principal.builder.class=com.yourcompany.spire.SpirePrincipalBuilder
```

You'll also need to add all other standard properties necessary for mTLS, including things that tell the broker how to connect to 'other' brokers, including itself (inter-broker listener, as well as controller).

## How It Works

1. When a client connects over SSL, Kafka calls your `SpirePrincipalBuilder.build()` method
2. The builder extracts the client's X.509 certificate from the SSL session
3. It attempts to extract a SPIFFE ID from the certificate's Subject Alternative Name (SAN)
4. If no SPIFFE ID is found, it falls back to the Common Name (CN) from the certificate subject
5. The extracted principal is used for authorization decisions (ACLs, etc.)

## Authorization with ACLs

Once principals are extracted, you can create Kafka ACLs using them:

```bash
# Example with SPIFFE ID
bin/kafka-acls.sh --bootstrap-server localhost:9093 \
  --add --allow-principal User:spiffe://example.com/workload/service1 \
  --operation All --topic test-topic

# Example with CN
bin/kafka-acls.sh --bootstrap-server localhost:9093 \
  --add --allow-principal User:service1 \
  --operation Read --group consumer-group-1
```

## Customization

The provided `SpirePrincipalBuilder` is a template. Customize it based on your needs:

- Extract different fields from certificates
- Parse custom certificate extensions
- Integrate with external identity systems
- Add custom validation logic
- Support multiple authentication contexts (SASL, etc.)

## Dependencies

The `pom.xml` marks Kafka dependencies as `provided` scope since they're already on the Kafka broker's classpath. This keeps your JAR small and avoids version conflicts.

## Troubleshooting

### ClassNotFoundException

If you see `ClassNotFoundException: com.yourcompany.spire.SpirePrincipalBuilder`:
- Verify the JAR is on Kafka's classpath
- Check the fully qualified class name in `server.properties`
- Ensure the JAR was built successfully

### SSL Handshake Failures

Enable SSL debugging:
```bash
export KAFKA_OPTS="-Djavax.net.debug=ssl:handshake"
```

### Principal Not Extracted

Check broker logs for messages from `SpirePrincipalBuilder`. Enable debug logging:
```properties
log4j.logger.com.yourcompany.spire=DEBUG
```
