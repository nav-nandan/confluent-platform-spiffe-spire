exit_after_auth = false
pid_file = "/tmp/agent.pid"

vault {
  address = "http://vault-server:8200"
}

auto_auth {
  method "approle" {
    mount_path = "auth/approle"
    config = {
      role_id_file_path   = "/scripts/roleid"
      secret_id_file_path = "/scripts/secretid"
      remove_secret_id_file_after_reading = false
    }
  }
}

# 1. Broker Keystore (Identity)
template {
  contents = <<EOH
{{- with pkiCert "pki/issue/kafka-role" "common_name=kafka" "uri_sans=spiffe://confluent.io/kafka" -}}
{{ .Cert }}
{{ .CA }}
{{- .Key | writeToFile "/secrets/kafka.key" "" "" "0600" -}}
{{- end -}}
EOH
  destination = "/secrets/kafka.crt"
  # Logic: Combine CRT and KEY into PKCS12 for Kafka
  command = "sleep 10 && openssl pkcs12 -export -in /secrets/kafka.crt -inkey /secrets/kafka.key -out /secrets/tmp-keystore.p12 -name kafka -passout pass:changeit && keytool -importkeystore -destkeystore /secrets/kafka-keystore.p12 -deststoretype PKCS12 -srckeystore /secrets/tmp-keystore.p12 -srcstoretype PKCS12 -srcstorepass changeit -deststorepass changeit -noprompt && rm -f /secrets/tmp-keystore.p12 && chmod 644 /secrets/kafka-keystore.p12"
}

# 2. Truststore (CA)
template {
  contents = <<EOH
{{- with secret "pki/cert/ca" -}}{{ .Data.certificate }}{{- end -}}
EOH
  destination = "/secrets/ca.crt"
  # Logic: Kafka 8.1.2 can use PKCS12 for truststores too. 
  # It's cleaner to use openssl to create a p12 truststore to match your docker-compose KAFKA_SSL_TRUSTSTORE_TYPE: "PKCS12"
  command = "sleep 5 && keytool -importcert -alias vault-ca -file /secrets/ca.crt -keystore /secrets/kafka-truststore.p12 -storetype PKCS12 -storepass changeit -noprompt && chmod 644 /secrets/kafka-truststore.p12"
}
