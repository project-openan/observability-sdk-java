package net.openan.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporterBuilder;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporterBuilder;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporterBuilder;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporterBuilder;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporterBuilder;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.MemoryMode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

public final class ObservabilitySdk implements AutoCloseable {
    private final OpenTelemetrySdk otel;
    private final Observability observe;
    private final AtomicLong exportFailures;
    private final AtomicLong reportedExportFailures = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    private ObservabilitySdk(OpenTelemetrySdk otel, AtomicLong exportFailures) {
        this.otel = otel;
        this.observe = new Observability(otel);
        this.exportFailures = exportFailures;
    }

    public static ObservabilitySdk start(SdkConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        String endpoint = config.endpoint.toString().replaceAll("/+$", "");
        SpanExporter spans;
        MetricExporter metrics;
        LogRecordExporter logs;
        AtomicLong exportFailures = new AtomicLong();

        if (config.protocol.equals("grpc")) {
            spans = configureExporter(
                OtlpGrpcSpanExporter.builder().setEndpoint(endpoint), config,
                (builder, header) -> builder.addHeader(header.getKey(), header.getValue()),
                OtlpGrpcSpanExporterBuilder::setTimeout,
                OtlpGrpcSpanExporterBuilder::setTrustedCertificates,
                OtlpGrpcSpanExporterBuilder::build);
            metrics = configureExporter(
                OtlpGrpcMetricExporter.builder().setEndpoint(endpoint), config,
                (builder, header) -> builder.addHeader(header.getKey(), header.getValue()),
                OtlpGrpcMetricExporterBuilder::setTimeout,
                OtlpGrpcMetricExporterBuilder::setTrustedCertificates,
                OtlpGrpcMetricExporterBuilder::build);
            logs = configureExporter(
                OtlpGrpcLogRecordExporter.builder().setEndpoint(endpoint), config,
                (builder, header) -> builder.addHeader(header.getKey(), header.getValue()),
                OtlpGrpcLogRecordExporterBuilder::setTimeout,
                OtlpGrpcLogRecordExporterBuilder::setTrustedCertificates,
                OtlpGrpcLogRecordExporterBuilder::build);
        } else {
            spans = configureExporter(
                OtlpHttpSpanExporter.builder().setEndpoint(endpoint + "/v1/traces"), config,
                (builder, header) -> builder.addHeader(header.getKey(), header.getValue()),
                OtlpHttpSpanExporterBuilder::setTimeout,
                OtlpHttpSpanExporterBuilder::setTrustedCertificates,
                OtlpHttpSpanExporterBuilder::build);
            metrics = configureExporter(
                OtlpHttpMetricExporter.builder().setEndpoint(endpoint + "/v1/metrics"), config,
                (builder, header) -> builder.addHeader(header.getKey(), header.getValue()),
                OtlpHttpMetricExporterBuilder::setTimeout,
                OtlpHttpMetricExporterBuilder::setTrustedCertificates,
                OtlpHttpMetricExporterBuilder::build);
            logs = configureExporter(
                OtlpHttpLogRecordExporter.builder().setEndpoint(endpoint + "/v1/logs"), config,
                (builder, header) -> builder.addHeader(header.getKey(), header.getValue()),
                OtlpHttpLogRecordExporterBuilder::setTimeout,
                OtlpHttpLogRecordExporterBuilder::setTrustedCertificates,
                OtlpHttpLogRecordExporterBuilder::build);
        }

        spans = new TrackingSpanExporter(spans, exportFailures);
        metrics = new TrackingMetricExporter(metrics, exportFailures);
        logs = new TrackingLogRecordExporter(logs, exportFailures);

        Resource resource = Resource.getDefault().toBuilder()
            .put(AttributeKey.stringKey("service.name"), config.serviceName)
            .build();
        SdkTracerProvider tracer = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(BatchSpanProcessor.builder(spans).build())
            .build();
        SdkMeterProvider meter = SdkMeterProvider.builder()
            .setResource(resource)
            .registerMetricReader(PeriodicMetricReader.builder(metrics).setInterval(config.metricInterval).build())
            .build();
        SdkLoggerProvider logger = SdkLoggerProvider.builder()
            .setResource(resource)
            .addLogRecordProcessor(BatchLogRecordProcessor.builder(logs).build())
            .build();
        return new ObservabilitySdk(OpenTelemetrySdk.builder()
            .setTracerProvider(tracer)
            .setMeterProvider(meter)
            .setLoggerProvider(logger)
            .build(), exportFailures);
    }

    private static <B, E> E configureExporter(
        B builder,
        SdkConfig config,
        BiConsumer<B, Map.Entry<String, String>> addHeader,
        BiConsumer<B, Duration> setTimeout,
        BiConsumer<B, byte[]> setTrustedCertificates,
        Function<B, E> build) {
        config.headers.entrySet().forEach(entry -> addHeader.accept(builder, entry));
        setTimeout.accept(builder, config.exportTimeout);
        if (config.trustedCertificates != null) {
            setTrustedCertificates.accept(builder, config.trustedCertificates.clone());
        }
        return build.apply(builder);
    }

    public Observability observe() {
        return observe;
    }

    /**
     * Waits for pending signals to be exported. Returns false if a provider does not complete within the caller's
     * timeout or if an exporter reports a failed export since the previous flush.
     */
    public synchronized boolean flush(long timeout, TimeUnit unit) {
        long previousFailureCount = reportedExportFailures.get();
        CompletableResultCode result = CompletableResultCode.ofAll(List.of(
            otel.getSdkTracerProvider().forceFlush(),
            otel.getSdkMeterProvider().forceFlush(),
            otel.getSdkLoggerProvider().forceFlush()));
        result.join(timeout, unit);
        long currentFailureCount = exportFailures.get();
        reportedExportFailures.set(currentFailureCount);
        return result.isSuccess() && currentFailureCount == previousFailureCount;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            otel.close();
        }
    }

    private static void trackExport(CompletableResultCode result, AtomicLong exportFailures) {
        if (result == null) {
            exportFailures.incrementAndGet();
            return;
        }
        result.whenComplete(() -> {
            if (!result.isSuccess()) {
                exportFailures.incrementAndGet();
            }
        });
    }

    private static final class TrackingSpanExporter implements SpanExporter {
        private final SpanExporter delegate;
        private final AtomicLong exportFailures;

        private TrackingSpanExporter(SpanExporter delegate, AtomicLong exportFailures) {
            this.delegate = delegate;
            this.exportFailures = exportFailures;
        }

        @Override
        public CompletableResultCode export(java.util.Collection<SpanData> spans) {
            try {
                CompletableResultCode result = delegate.export(spans);
                trackExport(result, exportFailures);
                return result;
            } catch (RuntimeException | Error e) {
                exportFailures.incrementAndGet();
                throw e;
            }
        }

        @Override public CompletableResultCode flush() { return delegate.flush(); }
        @Override public CompletableResultCode shutdown() { return delegate.shutdown(); }
        @Override public void close() { delegate.close(); }
    }

    private static final class TrackingMetricExporter implements MetricExporter {
        private final MetricExporter delegate;
        private final AtomicLong exportFailures;

        private TrackingMetricExporter(MetricExporter delegate, AtomicLong exportFailures) {
            this.delegate = delegate;
            this.exportFailures = exportFailures;
        }

        @Override
        public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
            return delegate.getAggregationTemporality(instrumentType);
        }

        @Override
        public Aggregation getDefaultAggregation(InstrumentType instrumentType) {
            return delegate.getDefaultAggregation(instrumentType);
        }

        @Override
        public MemoryMode getMemoryMode() {
            return delegate.getMemoryMode();
        }

        @Override
        public CompletableResultCode export(java.util.Collection<MetricData> metrics) {
            try {
                CompletableResultCode result = delegate.export(metrics);
                trackExport(result, exportFailures);
                return result;
            } catch (RuntimeException | Error e) {
                exportFailures.incrementAndGet();
                throw e;
            }
        }

        @Override public CompletableResultCode flush() { return delegate.flush(); }
        @Override public CompletableResultCode shutdown() { return delegate.shutdown(); }
        @Override public void close() { delegate.close(); }
    }

    private static final class TrackingLogRecordExporter implements LogRecordExporter {
        private final LogRecordExporter delegate;
        private final AtomicLong exportFailures;

        private TrackingLogRecordExporter(LogRecordExporter delegate, AtomicLong exportFailures) {
            this.delegate = delegate;
            this.exportFailures = exportFailures;
        }

        @Override
        public CompletableResultCode export(java.util.Collection<LogRecordData> logs) {
            try {
                CompletableResultCode result = delegate.export(logs);
                trackExport(result, exportFailures);
                return result;
            } catch (RuntimeException | Error e) {
                exportFailures.incrementAndGet();
                throw e;
            }
        }

        @Override public CompletableResultCode flush() { return delegate.flush(); }
        @Override public CompletableResultCode shutdown() { return delegate.shutdown(); }
        @Override public void close() { delegate.close(); }
    }
}
