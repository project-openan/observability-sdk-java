# SDK Consumer Smoke App

This is a separate Maven application that imports the locally installed `net.openan:observability-sdk` artifact.

From the SDK repository root, install the artifact:

```powershell
mvn -B -ntp install
```

Then, from this directory, compile and run the app. Collector connection settings are read from the standard OTLP environment variables:

```powershell
$env:OTEL_EXPORTER_OTLP_PROTOCOL = 'http/protobuf'
$env:OTEL_EXPORTER_OTLP_ENDPOINT = 'http://localhost:4318'
$runId = 'consumer-20261117'
mvn -B -ntp package exec:java '-Dexec.mainClass=net.openan.observability.consumer.ConsumerSmoke' "-Dexec.args=$runId" '-Dexec.cleanupDaemonThreads=false'
```

The app creates one Task-T Span, a linked message Log, and the `task.duration` and `task.count` metrics, waits for `flush()`, then closes the SDK. It sets `service.name` to `sdk-consumer-<runId>` for Collector filtering.
