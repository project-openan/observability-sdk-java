package net.openan.observability.example;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.openan.observability.ObservabilitySdk;
import net.openan.observability.SdkConfig;
import net.openan.observability.SignalKind;

/** Fixed inputs for checking exported A2A traces and metrics. */
public final class A2AExample {
    public static void main(String[] args) {
        String service = args.length == 0 ? "a2a-demo" : args[0];
        try (var sdk = ObservabilitySdk.start(SdkConfig.builder().serviceName(service).build())) {
            var observe = sdk.observe();
            try (var span = observe.trace("message/send", SignalKind.CLIENT, Map.of(
                "operation.name", "message/send", "task_id", "demo-task", "context_id", "demo-context",
                "message_id", "demo-message", "Role", "user", "Extensions", "task-t", "parts.number", 1,
                "Request", "Demo request"))) {
                span.attributes(Map.of("Response", "Demo response"));
                span.ok();
            }
            try (var stream = observe.trace("message/stream", SignalKind.CLIENT, Map.of(
                "operation.name", "message/stream", "extension.name", "task-t", "task.type", "diagnosis"))) {
                try (var event = observe.trace("message/stream-event", SignalKind.CLIENT,
                    Map.of("streaming-event", "Demo status update"), stream)) {
                    event.ok();
                }
                try (var error = observe.trace("message/stream-error", SignalKind.CLIENT,
                    Map.of("error.type", "demo_timeout"), stream)) {
                    error.error();
                }
                stream.error();
            }
            try (var span = observe.trace("tasks/pushNotificationConfig/get", SignalKind.SERVER,
                Map.of("config_id", "demo-config"))) {
                span.ok();
            }
            observe.metric("gen_ai.client.operation.duration", 0.125, Map.of("operation.name", "message/send"));
            observe.metric("gen_ai.agent.a2a.streaming.duration", 0.5,
                Map.of("operation.name", "message/stream", "error.type", "demo_timeout"));
            if (!sdk.flush(10, TimeUnit.SECONDS)) throw new IllegalStateException("Flush failed or timed out");
        }
    }
}
