package net.openan.observability;

import net.openan.observability.internal.AttributesConverter;
import net.openan.observability.internal.Definitions;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import java.util.HashMap;
import java.util.Map;

/** Receives measurements supplied by instrumented SDKs. */
public final class Observability {
    private static final java.util.logging.Logger DIAGNOSTICS = java.util.logging.Logger.getLogger(Observability.class.getName());
    private final Tracer tracer;
    private final Logger logger;
    private final Map<String, DoubleHistogram> histograms = new HashMap<>();
    private final Map<String, LongCounter> counters = new HashMap<>();
    Observability(OpenTelemetry otel) {
        tracer = otel.getTracer("a2at.observability");
        logger = otel.getLogsBridge().get("a2at.observability");
        Meter meter = otel.getMeter("a2at.observability");
        Definitions.METRICS.forEach((name, metric) -> registerMetric(meter, Definitions.PREFIX + name, metric));
        Definitions.A2A_METRICS.forEach((name, metric) -> registerMetric(meter, name, metric));
    }
    private void registerMetric(Meter meter, String name, Definitions.Metric metric) {
        if (metric.histogram()) histograms.put(name, meter.histogramBuilder(name).setUnit(metric.unit()).build());
        else counters.put(name, meter.counterBuilder(name).setUnit(metric.unit()).build());
    }
    public ObservationSpan trace(String name, SignalKind kind, Map<String, ?> attributes) { return trace(name, kind, attributes, null); }
    /** The explicit span supplies a parent, or a Link for message/stream-event. */
    public ObservationSpan trace(String name, SignalKind kind, Map<String, ?> attributes, ObservationSpan parent) {
        Context context = parent == null ? Context.current() : parent.context();
        try {
            boolean streamEvent = Definitions.STREAM_EVENT.equals(name);
            boolean clientOnly = streamEvent || Definitions.STREAM_ERROR.equals(name);
            if (!Definitions.A2A_SPAN_NAMES.contains(name)
                || (kind != SignalKind.CLIENT && (kind != SignalKind.SERVER || clientOnly))) throw new IllegalArgumentException();
            Definitions.Signal extension = AttributesConverter.selectOptionalSignal(attributes, Definitions.SPANS);
            Definitions.Signal definition = Definitions.span(name, extension);
            Attributes attrs = AttributesConverter.convertSignal(attributes, 'S', definition, true);
            SpanBuilder builder = tracer.spanBuilder(name).setSpanKind(SpanKind.valueOf(kind.name())).setAllAttributes(attrs);
            if (streamEvent) {
                builder.setNoParent();
                SpanContext link = Span.fromContext(context).getSpanContext();
                if (link.isValid()) builder.addLink(link);
            } else {
                builder.setParent(context);
            }
            Span span = builder.startSpan();
            return new ObservationSpan(span, context, definition);
        } catch (RuntimeException e) {
            DIAGNOSTICS.warning("Observation trace rejected");
            return ObservationSpan.noop();
        }
    }
    public void metric(String id, Number value, Map<String, ?> attributes) {
        try {
            if (id == null || value == null) throw new IllegalArgumentException();
            String name = Definitions.shortName(id);
            boolean a2a = Definitions.A2A_METRICS.containsKey(id);
            Definitions.Metric definition = a2a ? Definitions.A2A_METRICS.get(id) : Definitions.METRICS.get(name);
            if (definition == null) throw new IllegalArgumentException();
            double number = value.doubleValue();
            if (!Double.isFinite(number) || number < 0
                || value instanceof java.math.BigDecimal decimal && decimal.signum() < 0
                || value instanceof java.math.BigInteger integer && integer.signum() < 0) throw new IllegalArgumentException();
            if (definition.integer() && !(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException();
            Attributes attrs = AttributesConverter.convert(attributes, 'M', definition.dimensions());
            if (a2a) {
                if (attrs.get(AttributeKey.stringKey(Definitions.A2A_PREFIX + "operation.name")) == null) throw new IllegalArgumentException();
                attrs = attrs.toBuilder().put("gen_ai.system", "A2A").build();
            } else if (name.equals("task.duration") || name.equals("task.count")) {
                String taskType = attrs.get(AttributeKey.stringKey(Definitions.PREFIX + "task.type"));
                if (taskType == null || taskType.isBlank()) throw new IllegalArgumentException();
            }
            String instrumentName = a2a ? id : Definitions.PREFIX + name;
            if (definition.histogram()) histograms.get(instrumentName).record(number, attrs);
            else counters.get(instrumentName).add(value.longValue(), attrs);
        } catch (RuntimeException e) { DIAGNOSTICS.warning("Observation metric rejected"); }
    }
    public void log(String name, LogSeverity severity, String body, Map<String, ?> attributes) { log(name, severity, body, attributes, null); }
    public void log(String name, LogSeverity severity, String body, Map<String, ?> attributes, ObservationSpan span) {
        try {
            if (!Definitions.MESSAGE_LOG.equals(name) || severity == null || body == null) throw new IllegalArgumentException();
            Definitions.Signal definition = AttributesConverter.selectSignal(attributes, Definitions.LOGS);
            Attributes attrs = AttributesConverter.convertSignal(attributes, 'L', definition, true);
            logger.logRecordBuilder().setEventName(name).setSeverity(Severity.valueOf(severity.name()))
                .setBody(AttributesConverter.clean(body)).setAllAttributes(attrs).setContext(span == null ? Context.current() : span.context()).emit();
        } catch (RuntimeException e) { DIAGNOSTICS.warning("Observation log rejected"); }
    }
}
