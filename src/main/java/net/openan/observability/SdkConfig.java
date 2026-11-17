package net.openan.observability;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Explicit builder values take precedence over environment variables. */
public final class SdkConfig {
    final URI endpoint;
    final String protocol;
    final String serviceName;
    final Duration metricInterval;
    final Duration exportTimeout;
    final Map<String, String> headers;
    final byte[] trustedCertificates;

    private SdkConfig(Builder builder, Map<String, String> env) {
        protocol = select(builder.protocol, env.get("OTEL_EXPORTER_OTLP_PROTOCOL"), "grpc")
            .toLowerCase(Locale.ROOT);
        if (!protocol.equals("grpc") && !protocol.equals("http/protobuf")) {
            throw new IllegalArgumentException("Invalid OTEL_EXPORTER_OTLP_PROTOCOL");
        }

        String defaultEndpoint = protocol.equals("grpc")
            ? "http://localhost:4317"
            : "http://localhost:4318";
        try {
            endpoint = URI.create(select(builder.endpoint, env.get("OTEL_EXPORTER_OTLP_ENDPOINT"), defaultEndpoint));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid OTEL_EXPORTER_OTLP_ENDPOINT");
        }
        if (!("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
            || endpoint.getHost() == null
            || endpoint.getUserInfo() != null
            || endpoint.getQuery() != null
            || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Invalid OTEL_EXPORTER_OTLP_ENDPOINT");
        }

        serviceName = select(builder.serviceName, env.get("OTEL_SERVICE_NAME"), "unknown_service");
        try {
            metricInterval = builder.metricInterval != null
                ? builder.metricInterval
                : Duration.ofMillis(Long.parseLong(select(null, env.get("OTEL_METRIC_EXPORT_INTERVAL"), "60000")));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid OTEL_METRIC_EXPORT_INTERVAL");
        }
        try {
            if (metricInterval.isZero() || metricInterval.isNegative() || metricInterval.toMillis() < 1) {
                throw new IllegalArgumentException("Invalid OTEL_METRIC_EXPORT_INTERVAL");
            }
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Invalid OTEL_METRIC_EXPORT_INTERVAL");
        }

        try {
            exportTimeout = builder.exportTimeout != null
                ? builder.exportTimeout
                : Duration.ofMillis(Long.parseLong(select(null, env.get("OTEL_EXPORTER_OTLP_TIMEOUT"), "10000")));
            if (exportTimeout.isNegative()) {
                throw new IllegalArgumentException("Invalid OTEL_EXPORTER_OTLP_TIMEOUT");
            }
            // The OTLP exporters use zero to mean no export deadline.
            exportTimeout.toNanos();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid OTLP export timeout");
        }

        Map<String, String> configuredHeaders = builder.headers != null
            ? builder.headers
            : parseHeaders(env.get("OTEL_EXPORTER_OTLP_HEADERS"));
        headers = validateAndNormalizeHeaders(configuredHeaders);

        String certificatePath = builder.trustedCertificate != null
            ? builder.trustedCertificate.toString()
            : select(null, env.get("OTEL_EXPORTER_OTLP_CERTIFICATE"), null);
        if (certificatePath == null) {
            trustedCertificates = null;
        } else {
            if (!"https".equalsIgnoreCase(endpoint.getScheme())) {
                throw new IllegalArgumentException("OTEL_EXPORTER_OTLP_CERTIFICATE requires an https endpoint");
            }
            trustedCertificates = readTrustedCertificates(certificatePath);
        }
    }

    private static String select(String explicit, String environment, String fallback) {
        return explicit != null ? explicit : environment == null || environment.isBlank() ? fallback : environment;
    }

    private static Map<String, String> parseHeaders(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        try {
            for (String pair : value.split(",", -1)) {
                int separator = pair.indexOf('=');
                if (separator <= 0) {
                    throw new IllegalArgumentException();
                }
                String name = pair.substring(0, separator);
                String headerValue = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
                if (headers.putIfAbsent(name, headerValue) != null) {
                    throw new IllegalArgumentException();
                }
            }
        } catch (RuntimeException e) {
            // Header values can contain credentials. Keep configuration errors value-free.
            throw new IllegalArgumentException("Invalid OTEL_EXPORTER_OTLP_HEADERS");
        }
        return headers;
    }

    private static Map<String, String> validateAndNormalizeHeaders(Map<String, String> configuredHeaders) {
        Map<String, String> normalized = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, String> entry : configuredHeaders.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                if (name == null || value == null) {
                    throw new IllegalArgumentException();
                }
                String normalizedName = name.toLowerCase(Locale.ROOT);
                // gRPC metadata keys are lowercase; using the common safe subset works for HTTP too.
                if (!normalizedName.matches("[a-z0-9][a-z0-9_.-]*")
                    || value.chars().anyMatch(character -> character == '\r' || character == '\n' || character == 0x7f
                        || character < 0x20 && character != '\t')) {
                    throw new IllegalArgumentException();
                }
                if (normalized.putIfAbsent(normalizedName, value) != null) {
                    throw new IllegalArgumentException();
                }
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid OTLP request headers");
        }
        return Collections.unmodifiableMap(normalized);
    }

    private static byte[] readTrustedCertificates(String certificatePath) {
        try {
            byte[] certificateBytes = Files.readAllBytes(Path.of(certificatePath));
            var certificates = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(certificateBytes));
            if (certificates.isEmpty()) {
                throw new IOException("No certificates found");
            }
            return certificateBytes;
        } catch (Exception e) {
            // Do not include the path or certificate contents in diagnostics.
            throw new IllegalArgumentException("Invalid OTEL_EXPORTER_OTLP_CERTIFICATE");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String endpoint;
        private String protocol;
        private String serviceName;
        private Duration metricInterval;
        private Duration exportTimeout;
        private Map<String, String> headers;
        private Path trustedCertificate;

        public Builder endpoint(String value) {
            endpoint = value;
            return this;
        }

        public Builder protocol(String value) {
            protocol = value;
            return this;
        }

        public Builder serviceName(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("serviceName is empty");
            }
            serviceName = value;
            return this;
        }

        public Builder metricInterval(Duration value) {
            metricInterval = value;
            return this;
        }

        /** Sets request headers for traces, metrics and logs, replacing the environment value. */
        public Builder headers(Map<String, String> value) {
            if (value == null) {
                throw new IllegalArgumentException("headers are required");
            }
            headers = new LinkedHashMap<>(value);
            return this;
        }

        /** Adds one request header and makes builder headers take precedence over the environment value. */
        public Builder header(String name, String value) {
            if (headers == null) {
                headers = new LinkedHashMap<>();
            }
            headers.put(name, value);
            return this;
        }

        /** Sets the maximum duration for each OTLP export batch. Zero means no exporter deadline. */
        public Builder exportTimeout(Duration value) {
            if (value == null) {
                throw new IllegalArgumentException("exportTimeout is required");
            }
            exportTimeout = value;
            return this;
        }

        /** Sets a PEM or DER X.509 CA certificate used to verify the Collector's TLS certificate. */
        public Builder trustedCertificate(Path value) {
            if (value == null) {
                throw new IllegalArgumentException("trustedCertificate is required");
            }
            trustedCertificate = value;
            return this;
        }

        public SdkConfig build() {
            return new SdkConfig(this, System.getenv());
        }

        SdkConfig build(Map<String, String> env) {
            return new SdkConfig(this, env);
        }
    }
}
