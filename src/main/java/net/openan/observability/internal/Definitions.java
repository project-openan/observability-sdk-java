package net.openan.observability.internal;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Internal signal definitions; not a supported public API. */
public final class Definitions {
    public static final String PREFIX = "gen_ai.agent.a2at.";
    public static final String A2A_PREFIX = "gen_ai.agent.a2a.";
    public static final String MESSAGE_SPAN = "message/send";
    public static final String STREAM_SPAN = "message/stream";
    public static final String STREAM_EVENT = "message/stream-event";
    public static final String STREAM_ERROR = "message/stream-error";
    public static final String MESSAGE_LOG = PREFIX + "message";
    public record Attribute(String name, String signals, boolean integer, Set<String> values) {}
    public record Metric(boolean histogram, boolean integer, String unit, Set<String> dimensions) {}
    public record Signal(String extension, Set<String> attributes, Set<String> required) {}
    public static final Set<String> A2A_SPAN_NAMES = Set.of(MESSAGE_SPAN, STREAM_SPAN, STREAM_EVENT, STREAM_ERROR,
        "tasks/get", "tasks/cancel", "tasks/list", "tasks/resubscribe", "tasks/pushNotificationConfig/set",
        "tasks/pushNotificationConfig/get", "tasks/pushNotificationConfig/list", "tasks/pushNotificationConfig/delete");
    private static final Set<String> A2A_SPAN_ATTRIBUTES = Set.of("operation.name", "task_id", "context_id",
        "message_id", "Role", "Extensions", "parts.number", "config_id", "Request", "Response", "error.type");
    private static final Signal A2A_SPAN = new Signal(null, A2A_SPAN_ATTRIBUTES, Set.of());
    private static final Signal A2A_EVENT = new Signal(null, union(A2A_SPAN_ATTRIBUTES, Set.of("streaming-event")), Set.of());
    public static final Map<String, Signal> SPANS = Map.of(
        "task-t", signal("task-t", false),
        "negotiation-t", signal("negotiation-t", false),
        "notification-t", signal("notification-t", false),
        "authorization-t", signal("authorization-t", false));
    public static final Map<String, Signal> LOGS = Map.of(
        "task-t", signal("task-t", true),
        "negotiation-t", signal("negotiation-t", true),
        "notification-t", signal("notification-t", true),
        "authorization-t", signal("authorization-t", true));
    private static Signal signal(String extension, boolean log) {
        Set<String> allowed = switch (extension) {
            case "task-t" -> Set.of("extension.name", "task.type", "task.object");
            case "negotiation-t" -> log
                ? Set.of("extension.name", "negotiation.id", "negotiation.round", "negotiation.total_rounds", "negotiation.result", "negotiation.performative", "negotiation.max_rounds")
                : Set.of("extension.name", "negotiation.id", "negotiation.round", "negotiation.total_rounds", "negotiation.result", "negotiation.performative");
            case "notification-t" -> Set.of("extension.name", "notification.topic", "push.notification.url");
            case "authorization-t" -> Set.of("extension.name", "authorization.stage", "authorization.policy.operation.type");
            default -> throw new IllegalArgumentException();
        };
        return new Signal(extension, allowed, extension.equals("task-t") ? Set.of("extension.name", "task.type") : Set.of("extension.name"));
    }
    static final Map<String, Attribute> ATTRIBUTES = Map.ofEntries(
        entry("extension.name", "SML", false, "task-t", "negotiation-t", "notification-t", "authorization-t"),
        entry("task.type", "SML", false), entry("task.object", "SL", false),
        entry("negotiation.id", "SML", false), entry("negotiation.round", "SML", true),
        entry("negotiation.total_rounds", "SL", true), entry("negotiation.max_rounds", "L", true),
        entry("negotiation.result", "SML", false, "accept", "reject", "timeout"),
        entry("negotiation.performative", "SML", false, "propose", "reject", "accept"),
        entry("notification.topic", "SML", false), entry("push.notification.url", "SL", false),
        entry("authorization.stage", "SL", false, "preauthorize", "execute"),
        entry("authorization.policy.operation.type", "SML", false),
        attribute("operation.name", A2A_PREFIX + "operation.name", "SM", false),
        attribute("task_id", A2A_PREFIX + "task_id", "S", false),
        attribute("context_id", A2A_PREFIX + "context_id", "S", false),
        attribute("message_id", A2A_PREFIX + "message_id", "S", false),
        attribute("Role", A2A_PREFIX + "Role", "S", false, "user", "agent"),
        attribute("Extensions", A2A_PREFIX + "Extensions", "S", false),
        attribute("parts.number", A2A_PREFIX + "parts.number", "S", true),
        attribute("config_id", A2A_PREFIX + "config_id", "S", false),
        attribute("Request", A2A_PREFIX + "Request", "S", false),
        attribute("Response", A2A_PREFIX + "Response", "S", false),
        attribute("streaming-event", A2A_PREFIX + "streaming-event", "S", false),
        attribute("error.type", "error.type", "SM", false),
        attribute("gen_ai.system", "gen_ai.system", "M", false));
    public static final Map<String, Metric> METRICS = Map.of(
        "task.duration", new Metric(true, false, "ms", Set.of("task.type")),
        "task.count", new Metric(false, true, "", Set.of("task.type")),
        "negotiation.rounds", new Metric(true, true, "{round}", Set.of("negotiation.id", "negotiation.result")),
        "negotiation.count", new Metric(false, true, "", Set.of("negotiation.result")),
        "notification.count", new Metric(false, true, "", Set.of("notification.topic")),
        "authorization.preauthorize.count", new Metric(false, true, "", Set.of("authorization.policy.operation.type")),
        "authorization.preauthorize.duration", new Metric(true, false, "ms", Set.of("authorization.policy.operation.type")),
        "authorization.execute.count", new Metric(false, true, "", Set.of("authorization.policy.operation.type")));
    public static final String A2A_OPERATION_DURATION = "gen_ai.client.operation.duration";
    public static final String A2A_STREAMING_DURATION = A2A_PREFIX + "streaming.duration";
    private static final Metric A2A_DURATION = new Metric(true, false, "s",
        Set.of("operation.name", "gen_ai.system", "error.type"));
    public static final Map<String, Metric> A2A_METRICS = Map.of(
        A2A_OPERATION_DURATION, A2A_DURATION, A2A_STREAMING_DURATION, A2A_DURATION);

    public static Signal span(String name, Signal extension) {
        if (extension == null) return STREAM_EVENT.equals(name) ? A2A_EVENT : A2A_SPAN;
        if (!MESSAGE_SPAN.equals(name) && !STREAM_SPAN.equals(name)) throw new IllegalArgumentException();
        return new Signal(extension.extension(), union(A2A_SPAN_ATTRIBUTES, extension.attributes()), extension.required());
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> values = new HashSet<>(first);
        values.addAll(second);
        return Set.copyOf(values);
    }

    private static Map.Entry<String, Attribute> entry(String name, String signals, boolean integer, String... values) {
        return attribute(name, PREFIX + name, signals, integer, values);
    }
    private static Map.Entry<String, Attribute> attribute(String key, String name, String signals, boolean integer, String... values) {
        return Map.entry(key, new Attribute(name, signals, integer, Set.of(values)));
    }
    public static String shortName(String name) { return name.startsWith(PREFIX) ? name.substring(PREFIX.length()) : name; }
    private Definitions() {}
}
