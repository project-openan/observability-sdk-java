# OpenAN Observability SDK Log4j2 bridge

`observability-sdk-log4j2` is an optional bridge for applications that already use Log4j2. It forwards selected Log4j2 events to an existing OpenAN `ObservabilitySdk` instance as OpenTelemetry log records.

It is intended for application logs that should enter the observability pipeline. Ordinary Log4j2 output keeps its existing appenders and is not exported unless it carries the configured Marker.

## What the bridge does

For every matching event, the bridge:

1. checks whether the event Marker matches the configured Marker name;
2. copies MDC (`ThreadContext`) entries as SDK log attributes;
3. maps the Log4j2 level to SDK `LogSeverity`;
4. uses the formatted Log4j2 message as the log body;
5. uses `traceId` and `spanId` from MDC for correlation when both are valid W3C hexadecimal IDs; otherwise it uses the current OpenTelemetry context.

The bridge does not export events without the Marker. It does not alter normal Log4j2 appenders, redact exception content, or infer SDK attributes. Attribute validation is still performed by the SDK YAML definitions.

## Prerequisites

- JDK 17 or later.
- An application using Log4j2 Core.
- An initialized `ObservabilitySdk`; configure its OTLP endpoint and Collector as described in the root [README](../README.md).
- An SDK log signal name whose required attributes can be supplied by MDC.

The bridge has a `provided` dependency on `log4j-core`. The consuming application must provide its own compatible Log4j2 implementation.

## Add the dependency

After the artifacts are published, use the same released version for the SDK and bridge:

```xml
<dependencies>
    <dependency>
        <groupId>net.openan</groupId>
        <artifactId>observability-sdk</artifactId>
        <version>${openan.observability.version}</version>
    </dependency>
    <dependency>
        <groupId>net.openan</groupId>
        <artifactId>observability-sdk-log4j2</artifactId>
        <version>${openan.observability.version}</version>
    </dependency>
</dependencies>
```

For local development before release, install the core SDK first, then build the bridge from this directory:

```powershell
Set-Location ..
mvn -B -ntp clean install
Set-Location observability-sdk-log4j2
mvn -B -ntp clean package
```

The bridge is currently an independent Maven module; it is not included by the root SDK build.

## Register the Appender

Create and register the Appender after the SDK starts and before application logging begins. Registration is programmatic: the current bridge does not expose a Log4j2 plugin for `log4j2.xml`.

```java
import java.time.Duration;
import net.openan.observability.ObservabilitySdk;
import net.openan.observability.SdkConfig;
import net.openan.observability.log4j2.OpenanObservabilityLog4j2Appender;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;

public final class Application {
    public static void main(String[] args) {
        ObservabilitySdk sdk = ObservabilitySdk.start(SdkConfig.builder()
            .serviceName("diagnosis-agent")
            .endpoint("http://localhost:4317")
            .protocol("grpc")
            .metricInterval(Duration.ofSeconds(10))
            .build());

        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        LoggerConfig rootLogger = context.getConfiguration().getRootLogger();
        var appender = OpenanObservabilityLog4j2Appender.create(
            "openan-observability",
            sdk.observe(),
            "A2A_OBSERVABILITY",
            "gen_ai.agent.a2at.agent");
        appender.start();
        rootLogger.addAppender(appender, null, null);
        context.updateLoggers();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sdk.flush(10, java.util.concurrent.TimeUnit.SECONDS);
            sdk.close();
        }));
    }
}
```

`create(name, observe, markerName, eventName)` rejects null or blank arguments. Use a unique `name` when an application registers more than one bridge.

## Emit an observable log

The following example uses the Agent lifecycle log signal. Its required MDC attributes are `gen_ai.agent.name` and `agent.state`.

```java
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.ThreadContext;

Logger logger = LogManager.getLogger(Worker.class);
Marker observable = MarkerManager.getMarker("A2A_OBSERVABILITY");

ThreadContext.put("gen_ai.agent.name", "diagnosis-agent");
ThreadContext.put("agent.state", "running");
try {
    logger.info(observable, "alarm retrieval started");
} finally {
    ThreadContext.clearMap();
}
```

The event is sent as the SDK log signal `gen_ai.agent.a2at.agent` with severity `INFO`, body `alarm retrieval started`, and the MDC values as attributes. The same event still reaches other Log4j2 appenders configured by the application.

An event without `A2A_OBSERVABILITY` is ignored by this bridge:

```java
logger.info("ordinary application log");
```

Marker inheritance is supported. For example, a Marker that has `A2A_OBSERVABILITY` as a parent also matches the bridge.

## Choose the event name and MDC attributes

`eventName` selects one SDK log definition. The attributes copied from MDC must satisfy that definition; otherwise the SDK rejects the log before export. Do not place arbitrary MDC values on observable events unless they are allowed by the selected signal.

| `eventName` | Required MDC attributes | Optional MDC attributes |
|---|---|---|
| `gen_ai.agent.a2at.agent` | `gen_ai.agent.name`, `agent.state` | `agent.role` |
| `gen_ai.agent.a2at.substep` | `gen_ai.agent.name`, `substep.name`, `substep.status` | `workflow.step.name`, `workflow.step.id` |
| `gen_ai.agent.a2at.workflow` | `workflow.id`, `workflow.type`, `workflow.status` | `workflow.session_id`, `business.domain`, `business.customer` |
| `gen_ai.agent.a2at.step` | `workflow.step.name`, `workflow.step.id`, `workflow.step.status` | `workflow.step.owner_agent` |
| `gen_ai.agent.a2at.message` | `extension.name`; Task-T also requires `task.type` | Attributes permitted by the selected A2A-T extension |

For the message signal, `extension.name` must be one of `task-t`, `negotiation-t`, `notification-t`, or `authorization-t`. The selected value determines the allowed MDC attribute names. See [`a2at.yaml`](../src/main/resources/observability/en/a2at.yaml) for the complete list and [`agent.yaml`](../src/main/resources/observability/en/agent.yaml) and [`workflow.yaml`](../src/main/resources/observability/en/workflow.yaml) for Agent and Workflow definitions.

Values copied from MDC are strings. This is suitable for string attributes, but it cannot supply integer attributes such as `negotiation.round`, nor string-array attributes such as `agent.skills`. For signals requiring those values, call `sdk.observe().log(...)` directly with typed values instead of routing that event through the bridge.

## Trace correlation

When the application logs inside an active SDK span, the bridge uses that current OpenTelemetry context when MDC does not contain both correlation values. For logging in a thread where no active context is available, add both values to MDC:

```java
ThreadContext.put("traceId", "0af7651916cd43dd8448eb211c80319c");
ThreadContext.put("spanId", "b7ad6b7169203331");
```

`traceId` and `spanId` are used for correlation only and are removed before SDK attribute validation and export. Both must be valid W3C hexadecimal identifiers. If either one is absent or invalid, the bridge uses the current context instead.

## Level mapping

| Log4j2 level | SDK severity |
|---|---|
| `FATAL` | `FATAL` |
| `ERROR` | `ERROR` |
| `WARN` | `WARN` |
| `INFO` | `INFO` |
| `DEBUG` | `DEBUG` |
| `TRACE` and lower levels | `TRACE` |

## Exceptions and sensitive data

The bridge exports only `event.getMessage().getFormattedMessage()` as the body. It does not serialize `Throwable` stack traces. If stack traces, request payloads, account identifiers, or other sensitive data are added to the message or MDC, the application is responsible for masking or excluding them before logging.

## Shutdown and troubleshooting

Keep the `ObservabilitySdk` alive for as long as the Appender can receive events. At shutdown, stop producing logs, flush the SDK, then close it. Do not close the SDK while Log4j2 worker threads are still using the bridge.

If an expected event is not exported, check the following in order:

1. The bridge was started and added to the intended `LoggerConfig` before the event was emitted.
2. The event has the configured Marker (or a Marker inheriting from it).
3. The selected `eventName` is correct and its required MDC values are present with supported types.
4. The SDK is enabled and its Collector endpoint is reachable.
5. Call `sdk.flush(...)` in short-lived programs so the LogRecord batch can be exported before process exit.

For local transport inspection, start the sample Collector with `examples/collector-config.yaml` from the SDK repository root; it prints received logs to its output.
