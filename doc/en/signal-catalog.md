# Signal Catalog

The SDK's built-in observability signals are grouped into three chapters — Workflow, A2A-T, and Agent — and each chapter is split into Metric, Trace, and Log tables. Signal attributes are broken out into individual rows that give the type, value range, and meaning of each attribute.

## Signal Count Summary

By signal category:

| Category | Metric | Trace | Log | Total |
|---|---:|---:|---:|---:|
| Workflow | 4 | 2 | 2 | 8 |
| A2A-T | 10 | 16 | 1 | 27 |
| Agent | 4 | 1 | 2 | 7 |
| **Total** | **18** | **19** | **5** | **42** |

By signal type and source (source definitions in Conventions below):

| Signal type | gen_ai | a2a | openan (custom) | Total |
|---|---:|---:|---:|---:|
| Metric | 3 | 1 | 14 | 18 |
| Trace | 0 | 16 | 3 | 19 |
| Log | 0 | 0 | 5 | 5 |
| **Total** | **3** | **17** | **22** | **42** |

By signal category and source:

| Category | gen_ai | a2a | openan (custom) | Total |
|---|---:|---:|---:|---:|
| Workflow | 0 | 0 | 8 | 8 |
| A2A-T | 1 | 17 | 9 | 27 |
| Agent | 2 | 0 | 5 | 7 |
| **Total** | **3** | **17** | **22** | **42** |

Note: all 16 A2A-T Traces are A2A protocol operation spans (aligned with the a2a-java source, including `SubscribeToTask-event` / `SubscribeToTask-end`; `SendPushNotification` is recorded by the server only).

**Conventions**:

- Each signal's attribute rows list the full whitelist; the SDK rejects a signal and emits a diagnostic log (without business values) when a required attribute is missing, an attribute outside the whitelist is passed, or an attribute type does not match.
- An empty leading cell (signal / source, etc.) in an attribute row means the row belongs to the signal on the line above.
- The "Source" column marks where a signal is defined: **gen_ai** = standard signals from the OTel GenAI semantic conventions (`gen_ai.*`); **a2a** = A2A protocol signals (metrics marked `protocol: a2a` in YAML and the 16 A2A protocol operation spans); **openan** = custom signals of this project (`gen_ai.agent.a2at.*` metrics and log events, `a2at.*` spans).
- The 16 A2A protocol operation spans match the a2a-java implementation: `GetExtendedAgentCard` is recorded by the client only; `SendPushNotification` is recorded by the server only (a server-side outbound push call, Span Kind CLIENT); every other operation is recorded by the client and the server, one span per side. Streaming (`SendStreamingMessage`) and subscription (`SubscribeToTask`) calls produce `-event` / `-end` suffix spans on both sides; these are independent root spans linked to the initiator via Span Link.
- Except for `extension.name` (a YAML-enforced enum), string attributes are only validated for type and non-blankness; the "Value range" column is a recommended vocabulary.
- API calls use attribute short names; on export they map to the OTel full names declared in YAML (prefixes `gen_ai.agent.a2at.`, `gen_ai.`, or `a2a.`).
- `workflow.step.status` / `substep.status` are terminal states `success` / `failed` in **metric dimensions** and runtime states (`active`, `running`, `completed`) in **Trace / Log**.
- Signals with identical attribute sets are referenced with "same as …" instead of being repeated: "A2A common span attributes" = the 22-item whitelist of `SendMessage`; "A2A event span attributes" = the common 21 items (excluding `push_notification.event_kind`) plus 5 event attributes, 26 items in total.

## Workflow

**Signal profile**: 8 in total — 4 Metrics, 2 Traces, 2 Logs; by source all openan (custom).

### Metric

| Metric | OTel name | Source | Metric type | Description | Attribute | Attribute type | Value range | Required | Meaning |
|---|---|---|---|---|---|---|---|---|---|
| `workflow.duration` | `gen_ai.agent.a2at.workflow.duration` | openan | Histogram (ms) | End-to-end duration distribution of a single workflow run, from start to terminal state | `workflow.type` | string | Low-cardinality type enum (e.g. `fault-diagnosis`) | Required | Execution duration per workflow type |
| | | | | | `workflow.status` | string | Conventional `running` / `completed` / `failed` / `cancelled` | Required | Duration split by execution state (e.g. success / failure) |
| `workflow.count` | `gen_ai.agent.a2at.workflow.count` | openan | Counter | Cumulative count of workflow executions | `workflow.type` | string | Low-cardinality type enum | Required | Execution count per workflow type |
| | | | | | `workflow.status` | string | Conventional `running` / `completed` / `failed` / `cancelled` | Required | Count split by state, yielding success / failure counts |
| `step.duration` | `gen_ai.agent.a2at.step.duration` | openan | Histogram (ms) | Workflow step duration distribution | `workflow.step.status` | string | Conventional `success` / `failed` | Required | Duration of successful / failed steps |
| | | | | | `gen_ai.agent.name` | string | Deployed agent name set (low cardinality) | Required | Step duration per agent |
| `step.count` | `gen_ai.agent.a2at.step.count` | openan | Counter | Cumulative count of workflow step executions | `workflow.step.status` | string | Conventional `success` / `failed` | Required | Count of successful / failed steps; step failure rate can be derived |
| | | | | | `gen_ai.agent.name` | string | Deployed agent name set (low cardinality) | Required | Step count per agent |

### Trace

| Trace (span name) | Source | Span kind | Description | Attribute | Attribute type | Value range | Required | Meaning |
|---|---|---|---|---|---|---|---|---|
| `a2at.workflow` | openan | INTERNAL | Root span of one workflow execution, covering the full interval from start to terminal state | `workflow.id` | string | Instance-unique identifier (high cardinality) | Required | Workflow instance ID, used to correlate one execution across signals |
| | | | | `workflow.name` | string | Low-cardinality workflow name | Optional | Workflow name |
| | | | | `workflow.type` | string | Low-cardinality type enum (e.g. `fault-diagnosis`) | Required | Workflow type |
| | | | | `workflow.status` | string | Runtime state (`running` / `completed` / `failed` …) | Required | Workflow execution state |
| | | | | `workflow.session_id` | string | Session identifier | Optional | Owning session ID |
| `a2at.step.<name>` | openan | INTERNAL | Workflow step span; the name is the prefix plus the step name | `workflow.step.name` | string | Step name (high cardinality) | Required | Step name |
| | | | | `workflow.step.id` | string | Step identifier | Required | Step ID |
| | | | | `workflow.step.status` | string | Runtime state (`active` / `running` / `completed`) | Required | Step execution state |
| | | | | `workflow.step.owner_agent` | string | Agent name | Optional | Agent responsible for the step |

### Log

| Log (event name) | Source | Description | Attribute | Attribute type | Value range | Required | Meaning |
|---|---|---|---|---|---|---|---|
| `gen_ai.agent.a2at.workflow` | openan | Workflow event log (start, state change, completion) | `workflow.id` | string | Instance-unique identifier (high cardinality) | Required | Workflow instance ID |
| | | | `workflow.name` | string | Low-cardinality workflow name | Optional | Workflow name |
| | | | `workflow.type` | string | Low-cardinality type enum | Required | Workflow type |
| | | | `workflow.status` | string | Runtime state (`running` / `completed` / `failed` …) | Required | Workflow execution state |
| | | | `workflow.session_id` | string | Session identifier | Optional | Owning session ID |
| | | | `business.domain` | string | Business domain (low cardinality, e.g. `transport`) | Optional | Business domain |
| | | | `business.customer` | string | Customer identifier | Optional | Customer identifier |
| `gen_ai.agent.a2at.step` | openan | Workflow step event log | `workflow.step.name` | string | Step name (high cardinality) | Required | Step name |
| | | | `workflow.step.id` | string | Step identifier | Required | Step ID |
| | | | `workflow.step.status` | string | Runtime state | Required | Step execution state |
| | | | `workflow.step.owner_agent` | string | Agent name | Optional | Agent responsible for the step |

## A2A-T

**Signal profile**: 27 in total — 10 Metrics, 16 Traces, 1 Log; by source: 1 gen_ai, 17 a2a (1 metric + 16 protocol operation spans), 9 openan (custom).

### Metric

| Metric | OTel name | Source | Metric type | Description | Attribute | Attribute type | Value range | Required | Meaning |
|---|---|---|---|---|---|---|---|---|---|
| `gen_ai.client.operation.duration` | `gen_ai.client.operation.duration` | gen_ai | Histogram (s) | Duration distribution of single A2A client operations | `operation.name` | string | A2A operation name (e.g. `SendMessage`) | Required | Duration per A2A operation |
| | | | | | `gen_ai.system` | string | AI system identifier (e.g. `a2a`) | Optional | Split operation duration by AI system |
| | | | | | `error.type` | string | Low-cardinality error code (e.g. `timeout`) | Optional | Duration per error type |
| `gen_ai.agent.a2a.streaming.duration` | `gen_ai.agent.a2a.streaming.duration` | a2a | Histogram (s) | Duration distribution of whole A2A streaming calls | `operation.name` | string | A2A operation name | Required | Streaming duration per operation |
| | | | | | `gen_ai.system` | string | AI system identifier | Optional | Split streaming duration by AI system |
| | | | | | `error.type` | string | Low-cardinality error code | Optional | Streaming duration per error type |
| `task.duration` | `gen_ai.agent.a2at.task.duration` | openan | Histogram (ms) | Task-T task duration distribution | `task.type` | string | Low-cardinality task type (e.g. `diagnosis`) | Required | Duration per task type |
| | | | | | `task.object` | string | Free string | Optional | Split task duration by task object |
| | | | | | `task.status` | string | Conventional `completed` / `failed` / `cancelled` | Optional | Task duration per terminal state; supports the task success rate |
| `task.count` | `gen_ai.agent.a2at.task.count` | openan | Counter | Task-T cumulative task execution count | `task.type` | string | Low-cardinality task type | Required | Execution count per task type |
| | | | | | `task.object` | string | Free string | Optional | Split task count by task object |
| | | | | | `task.status` | string | Conventional `completed` / `failed` / `cancelled` | Optional | Task count per terminal state; supports the task success rate |
| `negotiation.rounds` | `gen_ai.agent.a2at.negotiation.rounds` | openan | Histogram ({round}) | Negotiation-T round-count distribution of a single negotiation | `negotiation.id` | string | Negotiation process identifier | Optional | Round distribution per negotiation process |
| | | | | | `negotiation.result` | string | Conventional `accepted` / `rejected`, etc. | Optional | Round distribution per negotiation result |
| `negotiation.count` | `gen_ai.agent.a2at.negotiation.count` | openan | Counter | Negotiation-T cumulative negotiation count | `negotiation.result` | string | Conventional `accepted` / `rejected`, etc. | Optional | Negotiation count per result |
| `notification.count` | `gen_ai.agent.a2at.notification.count` | openan | Counter | Notification-T cumulative notification count | `notification.topic` | string | Low-cardinality notification topic | Optional | Notification count per topic |
| | | | | | `notification.result` | string | Conventional `sent` / `failed` | Optional | Notification delivery success rate |
| `authorization.preauthorize.count` | `gen_ai.agent.a2at.authorization.preauthorize.count` | openan | Counter | Authorization-T cumulative pre-authorization count | `authorization.policy.operation.type` | string | Low-cardinality policy operation type | Optional | Pre-authorization count per policy operation type |
| | | | | | `authorization.result` | string | Conventional `allowed` / `denied` (`error` for failed execution) | Optional | Pre-authorization pass rate |
| `authorization.preauthorize.duration` | `gen_ai.agent.a2at.authorization.preauthorize.duration` | openan | Histogram (ms) | Authorization-T pre-authorization duration distribution | `authorization.policy.operation.type` | string | Low-cardinality policy operation type | Optional | Pre-authorization duration per policy operation type |
| | | | | | `authorization.result` | string | Conventional `allowed` / `denied` (`error` for failed execution) | Optional | Pre-authorization duration per decision result |
| `authorization.execute.count` | `gen_ai.agent.a2at.authorization.execute.count` | openan | Counter | Authorization-T cumulative authorization-execution count | `authorization.policy.operation.type` | string | Low-cardinality policy operation type | Optional | Authorization execution count per policy operation type |
| | | | | | `authorization.result` | string | Conventional `allowed` / `denied` (`error` for failed execution) | Optional | Authorization execution pass rate |

### Trace

**Signal profile**: 16 spans in total — all A2A protocol operations (a2a, aligned with the a2a-java source). Among the protocol operations, `GetExtendedAgentCard` is recorded by the client only and `SendPushNotification` by the server only; streaming (`SendStreamingMessage`) and subscription (`SubscribeToTask`) calls produce `-event` / `-end` child spans on both sides. The 4 extension attribute groups in the table (`extension.name` = `task-t`, etc.) are extension forms of `SendMessage` / `SendStreamingMessage`; they are counted within the protocol spans and not separately.

| Trace (span name) | Source | Span kind | Description | Attribute | Attribute type | Value range | Required | Meaning |
|---|---|---|---|---|---|---|---|---|
| `SendMessage` / `SendStreamingMessage` | a2a | CLIENT / SERVER | Synchronous inter-agent messages and streaming calls (`SendStreamingMessage` is the streaming parent span and also produces `-event` / `-end` child spans); when `extension.name` is present, the matching extension attributes are merged in (see the extension rows below) | `operation.name` | string | A2A operation name (e.g. `SendMessage`) | Optional | Operation name |
| | | | | `task_id` | string | Task ID | Optional | A2A task ID |
| | | | | `context_id` | string | Context ID | Optional | A2A context ID |
| | | | | `message_id` | string | Message ID | Optional | A2A message ID |
| | | | | `role` | string | `user` / `agent` | Optional | Message role |
| | | | | `extensions` | string | Extension identifier list | Optional | A2A extensions carried by the request |
| | | | | `parts.number` | integer | Non-negative integer | Optional | Number of message parts |
| | | | | `config_id` | string | Config ID | Optional | Push notification config ID |
| | | | | `request` | string | Redacted content | Optional | Request content |
| | | | | `response` | string | Redacted content | Optional | Response content |
| | | | | `error.type` | string | Low-cardinality error code | Optional | Error type |
| | | | | `gen_ai.provider.name` | string | Provider name (e.g. `openai`) | Optional | Model provider |
| | | | | `gen_ai.operation.name` | string | E.g. `chat` / `generate_content` | Optional | GenAI operation name |
| | | | | `gen_ai.agent.name` | string | Agent name (low cardinality) | Optional | Agent name |
| | | | | `gen_ai.agent.id` | string | Agent identifier | Optional | Agent ID |
| | | | | `gen_ai.agent.version` | string | Version string | Optional | Agent version |
| | | | | `push_notification.event_kind` | string | E.g. `task-status` | Optional | Push notification event type |
| | | | | `transport` | string | E.g. `jsonrpc` / `http` | Optional | Transport |
| | | | | `protocol.version` | string | E.g. `0.3` | Optional | A2A protocol version |
| | | | | `gen_ai.response.model` | string | Model name | Optional | Response model |
| | | | | `usage.input_tokens` | integer | Non-negative integer | Optional | Input tokens of a single call |
| | | | | `usage.output_tokens` | integer | Non-negative integer | Optional | Output tokens of a single call |
| `SendStreamingMessage-event` | a2a | CLIENT / SERVER | A single event in a streaming response; independent root span linked to the initiator via Span Link | Same as "A2A common span attributes" (excluding `push_notification.event_kind`) | — | — | — | 21 items, all optional |
| | | | | `streaming-event` | string | Event type (e.g. `status update`) | Optional | Streaming event type |
| | | | | `task.status.state` | string | A2A task state (`submitted` / `working` / `completed` / `failed` …) | Optional | Task state |
| | | | | `artifact.name` | string | Artifact name | Optional | Artifact name |
| | | | | `artifact.append` | boolean | `true` / `false` | Optional | Whether appended to an existing artifact |
| | | | | `artifact.last_chunk` | boolean | `true` / `false` | Optional | Whether this is the last chunk |
| `SendStreamingMessage-end` | a2a | CLIENT / SERVER | Streaming call end or abnormal termination; independent root span linked to the initiator via Span Link | Same as "A2A common span attributes" | — | — | — | 22 items, all optional, same as `SendMessage` |
| `SubscribeToTask` | a2a | CLIENT / SERVER | A2A task subscription (subscription parent span; also produces `-event` / `-end` child spans) | Same as "A2A common span attributes" | — | — | — | 22 items, all optional, same as `SendMessage` |
| `SubscribeToTask-event` | a2a | CLIENT / SERVER | A single event pushed by a subscription; independent root span linked to the initiator via Span Link | Same as "A2A event span attributes" | — | — | — | 26 items, all optional, same as `SendStreamingMessage-event` |
| `SubscribeToTask-end` | a2a | CLIENT / SERVER | Subscription end or abnormal termination; independent root span linked to the initiator via Span Link | Same as "A2A common span attributes" | — | — | — | 22 items, all optional, same as `SendMessage` |
| `GetTask` / `CancelTask` / `ListTasks` | a2a | CLIENT / SERVER | Query, cancel, and list A2A tasks | Same as "A2A common span attributes" | — | — | — | 22 items, all optional, same as `SendMessage` |
| `CreateTaskPushNotificationConfig` / `GetTaskPushNotificationConfig` / `ListTaskPushNotificationConfigs` / `DeleteTaskPushNotificationConfig` | a2a | CLIENT / SERVER | Management of task push notification configs | Same as "A2A common span attributes" | — | — | — | 22 items, all optional, same as `SendMessage` |
| `GetExtendedAgentCard` | a2a | CLIENT only | Extended agent card query, client only | Same as "A2A common span attributes" | — | — | — | 22 items, all optional, same as `SendMessage` |
| `SendPushNotification` | a2a | CLIENT | Server pushes a task notification to the client (outbound push call); recorded by the server only | Same as "A2A common span attributes" | — | — | — | 22 items, all optional, same as `SendMessage` |
| `SendMessage` / `SendStreamingMessage` (`extension.name` = `task-t`) | a2a | CLIENT / SERVER | Task-T task message | `extension.name` | string | `task-t` | Required | Extension name (enum) |
| | | | | `task.type` | string | Low-cardinality task type (e.g. `diagnosis`) | Required | Task type |
| | | | | `task.object` | string | Free string | Optional | Task object / goal |
| `SendMessage` / `SendStreamingMessage` (`extension.name` = `negotiation-t`) | a2a | CLIENT / SERVER | Negotiation-T negotiation message | `extension.name` | string | `negotiation-t` | Required | Extension name (enum) |
| | | | | `negotiation.id` | string | Negotiation process identifier | Optional | Negotiation ID |
| | | | | `negotiation.round` | integer | Positive integer | Optional | Current negotiation round |
| | | | | `negotiation.total_rounds` | integer | Positive integer | Optional | Actual total negotiation rounds |
| | | | | `negotiation.result` | string | Conventional `accepted` / `rejected`, etc. | Optional | Negotiation result |
| | | | | `negotiation.performative` | string | E.g. `propose` / `accept` / `reject` | Optional | Negotiation speech act |
| `SendMessage` / `SendStreamingMessage` (`extension.name` = `notification-t`) | a2a | CLIENT / SERVER | Notification-T notification message | `extension.name` | string | `notification-t` | Required | Extension name (enum) |
| | | | | `notification.topic` | string | Low-cardinality notification topic | Optional | Notification topic |
| | | | | `push.notification.url` | string | URL | Optional | Push notification callback URL |
| `SendMessage` / `SendStreamingMessage` (`extension.name` = `authorization-t`) | a2a | CLIENT / SERVER | Authorization-T authorization message | `extension.name` | string | `authorization-t` | Required | Extension name (enum) |
| | | | | `authorization.stage` | string | E.g. `preauthorize` / `execute` / `manual-review` | Optional | Authorization stage |
| | | | | `authorization.policy.operation.type` | string | Low-cardinality policy operation type | Optional | Authorization policy operation type |

### Log

**Signal profile**: 1 event in total — `gen_ai.agent.a2at.message` (openan); it must carry `extension.name` to select the attribute whitelist. The table below splits it into 4 attribute groups by extension, all under the same event name.

| Log (event name) | Source | Description | Attribute | Attribute type | Value range | Required | Meaning |
|---|---|---|---|---|---|---|---|
| `gen_ai.agent.a2at.message`<br>(`extension.name` = `task-t`) | openan | Task-T task message log | `extension.name` | string | `task-t` | Required | Extension name (enum) |
| | | | `task.type` | string | Low-cardinality task type | Required | Task type |
| | | | `task.object` | string | Free string | Optional | Task object / goal |
| `gen_ai.agent.a2at.message`<br>(`extension.name` = `negotiation-t`) | openan | Negotiation-T negotiation message log | `extension.name` | string | `negotiation-t` | Required | Extension name (enum) |
| | | | `negotiation.id` | string | Negotiation process identifier | Optional | Negotiation ID |
| | | | `negotiation.round` | integer | Positive integer | Optional | Current negotiation round |
| | | | `negotiation.total_rounds` | integer | Positive integer | Optional | Actual total negotiation rounds |
| | | | `negotiation.max_rounds` | integer | Positive integer | Optional | Negotiation round limit |
| | | | `negotiation.result` | string | Conventional `accepted` / `rejected`, etc. | Optional | Negotiation result |
| | | | `negotiation.performative` | string | E.g. `propose` / `accept` / `reject` | Optional | Negotiation speech act |
| `gen_ai.agent.a2at.message`<br>(`extension.name` = `notification-t`) | openan | Notification-T notification message log | `extension.name` | string | `notification-t` | Required | Extension name (enum) |
| | | | `notification.topic` | string | Low-cardinality notification topic | Optional | Notification topic |
| | | | `push.notification.url` | string | URL | Optional | Push notification callback URL |
| `gen_ai.agent.a2at.message`<br>(`extension.name` = `authorization-t`) | openan | Authorization-T authorization message log | `extension.name` | string | `authorization-t` | Required | Extension name (enum) |
| | | | `authorization.stage` | string | E.g. `preauthorize` / `execute` / `manual-review` | Optional | Authorization stage |
| | | | `authorization.policy.operation.type` | string | Low-cardinality policy operation type | Optional | Authorization policy operation type |

## Agent

**Signal profile**: 7 in total — 4 Metrics, 1 Trace, 2 Logs; by source: 2 gen_ai, 5 openan (custom).

### Metric

| Metric | OTel name | Source | Metric type | Description | Attribute | Attribute type | Value range | Required | Meaning |
|---|---|---|---|---|---|---|---|---|---|
| `substep.duration` | `gen_ai.agent.a2at.substep.duration` | openan | Histogram (ms) | Agent substep duration distribution | `substep.status` | string | Conventional `success` / `failed` | Required | Duration of successful / failed substeps |
| | | | | | `gen_ai.agent.name` | string | Deployed agent name set (low cardinality) | Required | Substep duration per agent |
| `substep.count` | `gen_ai.agent.a2at.substep.count` | openan | Counter | Agent substep cumulative execution count | `substep.status` | string | Conventional `success` / `failed` | Required | Count of successful / failed substeps; failure rate can be derived |
| | | | | | `gen_ai.agent.name` | string | Deployed agent name set (low cardinality) | Required | Substep count per agent |
| `gen_ai.client.inference.usage.input_tokens` | `gen_ai.client.inference.usage.input_tokens` | gen_ai | Counter ({token}) | Cumulative input token consumption of model calls (current OTel GenAI semantic conventions) | `gen_ai.operation.name` | string | E.g. `chat` / `generate_content` | Required | Input token consumption per GenAI operation |
| | | | | | `gen_ai.provider.name` | string | Provider name (e.g. `openai`) | Required | Input token consumption per provider |
| | | | | | `gen_ai.token.modality` | string | Conventional `text` / `image` / `audio` / `unknown` | Required | Input token consumption by modality |
| | | | | | `gen_ai.agent.name` | string | Deployed agent name set (low cardinality) | Optional | Input token consumption per agent |
| | | | | | `gen_ai.request.model` | string | Model name (low cardinality) | Optional | Input token consumption per model |
| `gen_ai.client.inference.usage.output_tokens` | `gen_ai.client.inference.usage.output_tokens` | gen_ai | Counter ({token}) | Cumulative output token consumption of model calls (current OTel GenAI semantic conventions) | `gen_ai.operation.name` | string | E.g. `chat` / `generate_content` | Required | Output token consumption per GenAI operation |
| | | | | | `gen_ai.provider.name` | string | Provider name (e.g. `openai`) | Required | Output token consumption per provider |
| | | | | | `gen_ai.token.modality` | string | Conventional `text` / `image` / `audio` / `unknown` | Required | Output token consumption by modality |
| | | | | | `gen_ai.agent.name` | string | Deployed agent name set (low cardinality) | Optional | Output token consumption per agent |
| | | | | | `gen_ai.request.model` | string | Model name (low cardinality) | Optional | Output token consumption per model |

### Trace

| Trace (span name) | Source | Span kind | Description | Attribute | Attribute type | Value range | Required | Meaning |
|---|---|---|---|---|---|---|---|---|
| `a2at.substep.<name>` | openan | INTERNAL | Agent substep span; the name is the prefix plus the substep name | `gen_ai.agent.name` | string | Agent name (low cardinality) | Required | Agent name |
| | | | | `substep.name` | string | Substep name (defined by agent code) | Required | Substep name |
| | | | | `substep.status` | string | Runtime state (`active` / `running` / `completed`) | Required | Substep execution state |

### Log

| Log (event name) | Source | Description | Attribute | Attribute type | Value range | Required | Meaning |
|---|---|---|---|---|---|---|---|
| `gen_ai.agent.a2at.agent` | openan | Agent lifecycle event log | `gen_ai.agent.name` | string | Agent name (low cardinality) | Required | Agent name |
| | | | `agent.role` | string | Agent role | Optional | Agent role |
| | | | `agent.skills` | string[] | String list (non-empty elements) | Optional | Agent skill list |
| | | | `agent.state` | string | E.g. `running` / `completed` | Required | Agent lifecycle state |
| `gen_ai.agent.a2at.substep` | openan | Agent substep event log | `gen_ai.agent.name` | string | Agent name (low cardinality) | Required | Agent name |
| | | | `substep.name` | string | Substep name | Required | Substep name |
| | | | `substep.status` | string | Runtime state | Required | Substep execution state |
| | | | `workflow.step.name` | string | Step name | Optional | Related workflow step name |
| | | | `workflow.step.id` | string | Step identifier | Optional | Related workflow step ID |
