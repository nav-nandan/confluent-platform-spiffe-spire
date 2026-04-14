package com.yourcompany.spire;

import org.apache.kafka.common.Configurable;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.message.DefaultPrincipalData;
import org.apache.kafka.common.protocol.ByteBufferAccessor;
import org.apache.kafka.common.protocol.MessageUtil;
import org.apache.kafka.common.security.auth.AuthenticationContext;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.apache.kafka.common.security.auth.KafkaPrincipalBuilder;
import org.apache.kafka.common.security.auth.KafkaPrincipalSerde;
import org.apache.kafka.common.security.auth.SslAuthenticationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.security.auth.x500.X500Principal;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom KafkaPrincipalBuilder that extracts principals from X.509 certificates
 * using SPIFFE ID format or other custom logic.
 *
 * Example SPIFFE ID: spiffe://trust-domain/workload/service-name
 */
public class SpirePrincipalBuilder implements KafkaPrincipalBuilder, KafkaPrincipalSerde, Configurable {

    private static final Logger log = LoggerFactory.getLogger(SpirePrincipalBuilder.class);

    // Pattern to extract SPIFFE ID from certificate SAN URI
    private static final Pattern SPIFFE_ID_PATTERN = Pattern.compile("spiffe://([^/]+)/(.+)");

    public SpirePrincipalBuilder() {

    }

    @Override
    public void configure(Map<String, ?> configs) {
        // Currently no configuration needed, but implementing for future extensibility
        // You can add custom config parameters here if needed in the future
    }

    @Override
    public KafkaPrincipal build(AuthenticationContext context) {
        if (context instanceof SslAuthenticationContext) {
            SslAuthenticationContext sslContext = (SslAuthenticationContext) context;
            try {
                return buildFromSsl(sslContext);
            } catch (Exception e) {
                log.error("Failed to build principal from SSL context", e);
                return KafkaPrincipal.ANONYMOUS;
            }
        }

        // For non-SSL contexts, use default behavior
        // debug
        log.warn("Non-SSL authentication context, returning ANONYMOUS");
        return KafkaPrincipal.ANONYMOUS;
    }

    private KafkaPrincipal buildFromSsl(SslAuthenticationContext context) throws SSLPeerUnverifiedException {
        SSLSession sslSession = context.session();

        Certificate[] peerCertificates = sslSession.getPeerCertificates();
        if (peerCertificates == null || peerCertificates.length == 0) {
            // warn
            log.warn("No peer certificates found in SSL session");
            return KafkaPrincipal.ANONYMOUS;
        }

        X509Certificate clientCert = (X509Certificate) peerCertificates[0];

        // Try to extract SPIFFE ID from SAN (Subject Alternative Name)
        String spiffeId = extractSpiffeId(clientCert);
        if (spiffeId != null) {
            // debug
            log.warn("Extracted SPIFFE ID: {}", spiffeId);
            return new KafkaPrincipal(KafkaPrincipal.USER_TYPE, spiffeId);
        }

        // Fallback to CN (Common Name) from certificate subject
        X500Principal principal = clientCert.getSubjectX500Principal();
        String distinguishedName = principal.getName();
        String commonName = extractCN(distinguishedName);

        if (commonName != null) {
            // debug
            log.warn("Extracted CN: {}", commonName);
            return new KafkaPrincipal(KafkaPrincipal.USER_TYPE, commonName);
        }

        log.warn("Could not extract principal from certificate");
        return KafkaPrincipal.ANONYMOUS;
    }

    private String extractSpiffeId(X509Certificate cert) {
        try {
            // SAN type 6 is URI
            var sanCollection = cert.getSubjectAlternativeNames();
            if (sanCollection == null) {
                return null;
            }

            for (var san : sanCollection) {
                Integer type = (Integer) san.get(0);
                if (type == 6) { // URI type
                    String uri = (String) san.get(1);
                    if (uri.startsWith("spiffe://")) {
                        return uri;
                    }
                }
            }
        } catch (Exception e) {
            // debug
            log.warn("Failed to extract SPIFFE ID from certificate", e);
        }
        return null;
    }

    private String extractCN(String distinguishedName) {
        // Simple CN extraction from DN string like "CN=servicename,OU=..."
        Pattern cnPattern = Pattern.compile("CN=([^,]+)");
        Matcher matcher = cnPattern.matcher(distinguishedName);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    @Override
    public byte[] serialize(KafkaPrincipal principal) {
        DefaultPrincipalData data = new DefaultPrincipalData()
                .setType(principal.getPrincipalType())
                .setName(principal.getName())
                .setTokenAuthenticated(principal.tokenAuthenticated());
        return MessageUtil.toVersionPrefixedBytes(DefaultPrincipalData.HIGHEST_SUPPORTED_VERSION, data);
    }

    @Override
    public KafkaPrincipal deserialize(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        short version = buffer.getShort();
        if (version < DefaultPrincipalData.LOWEST_SUPPORTED_VERSION || version > DefaultPrincipalData.HIGHEST_SUPPORTED_VERSION) {
            throw new SerializationException("Invalid principal data version " + version);
        }

        DefaultPrincipalData data = new DefaultPrincipalData(new ByteBufferAccessor(buffer), version);
        return new KafkaPrincipal(data.type(), data.name(), data.tokenAuthenticated());
    }
}
