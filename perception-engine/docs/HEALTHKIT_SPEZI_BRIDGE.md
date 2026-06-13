# HealthKit Spezi Bridge

This document defines the recommended iOS bridge shape for connecting Apple
HealthKit to RealityEngine_CPP through Perception Engine.

## Recommendation

Use Stanford SpeziHealthKit as the native iOS bridge foundation. The bridge is
a Swift app/module outside this C++ repo. It owns Apple HealthKit access and
posts already-authorized, normalized, read-only samples to:

```text
POST /api/integrations/healthkit/ingest
```

RealityEngine_CPP remains the PE/RE service boundary. It does not link
HealthKit, perform Apple authorization, or run OAuth flows for HealthKit.

## iOS Responsibilities

The iOS bridge must:

- Enable the HealthKit capability and include the required privacy strings.
- Request read-only authorization for blood pressure, workout/exercise, and
  sleep sample types.
- Use observer/anchored collection so duplicate samples are not replayed.
- Normalize HealthKit quantities and categories to PE vector values.
- Attach provenance metadata such as HealthKit UUID, source revision, start/end
  timestamps, unit, and optional FHIR code/profile hints.
- POST only data the user authorized to the PE ingest endpoint.

No HealthKit write access is required for the current bridge.

## Read Types

The initial read-only scope is:

```text
HKCorrelationTypeIdentifierBloodPressure
HKQuantityTypeIdentifierBloodPressureSystolic
HKQuantityTypeIdentifierBloodPressureDiastolic
HKWorkoutTypeIdentifierWorkout
HKQuantityTypeIdentifierStepCount
HKQuantityTypeIdentifierAppleExerciseTime
HKQuantityTypeIdentifierActiveEnergyBurned
HKCategoryTypeIdentifierSleepAnalysis
```

## PE Configuration

Use `config/integrations.healthkit-spezi.example.json` as the reference PE
registry. It maps the three normalized sample families into fixed perceptual
regions:

```text
healthkit:HKCorrelationTypeIdentifierBloodPressure -> [4320:4324]
healthkit:HKWorkoutTypeIdentifierWorkout           -> [4330:4334]
healthkit:HKCategoryTypeIdentifierSleepAnalysis    -> [4340:4344]
```

The bridge may either omit `sourceMappingId` and rely on the `healthkit:<type>`
registry key, or send an explicit `sourceMappingId`.

## App Configuration

Use `config/healthkit-spezi-app.example.env` as the bridge app configuration
template:

```text
PE_BASE_URL=http://<mac-lan-ip>:5000
PE_HEALTHKIT_ENDPOINT=/api/integrations/healthkit/ingest
HEALTHKIT_BRIDGE_ID=healthkit-ios-bridge
HEALTHKIT_BRIDGE_TOKEN=<dev-token>
```

Do not use `localhost` from the simulator or iPhone unless PE runs inside that
same runtime. For simulator-to-Mac with the Scala PE, `http://127.0.0.1:5000`
may work. For a physical iPhone, use the Mac's LAN IP. The deprecated
compatibility example is `http://<mac-lan-ip>:3300` only when PE is explicitly
started on that compatibility port.

The full ingest URL is:

```text
${PE_BASE_URL}${PE_HEALTHKIT_ENDPOINT}
```

## Post Payloads To PE

The bridge should normalize each metric into a four-value PE vector and send it
to `${PE_BASE_URL}${PE_HEALTHKIT_ENDPOINT}`.

Sleep analysis example:

```json
{
  "bridgeId": "healthkit-ios-bridge",
  "bridgeToken": "your-shared-dev-token",
  "type": "HKCategoryTypeIdentifierSleepAnalysis",
  "unit": "normalized",
  "values": [0.82, 0.12, 0.18, 0.96],
  "metadata": {
    "standard": "SpeziHealthKit",
    "fhirCode": "93832-4"
  }
}
```

Blood pressure example:

```json
{
  "bridgeId": "healthkit-ios-bridge",
  "bridgeToken": "your-shared-dev-token",
  "type": "HKCorrelationTypeIdentifierBloodPressure",
  "unit": "mm[Hg]",
  "values": [0.72, 0.48, 0.24, 0.99],
  "metadata": {
    "standard": "SpeziHealthKit",
    "fhirProfile": "http://hl7.org/fhir/us/core/StructureDefinition/us-core-blood-pressure",
    "fhirCode": "85354-9"
  }
}
```

Exercise example:

```json
{
  "bridgeId": "healthkit-ios-bridge",
  "bridgeToken": "your-shared-dev-token",
  "type": "HKWorkoutTypeIdentifierWorkout",
  "unit": "normalized",
  "values": [0.65, 0.58, 0.42, 0.97],
  "metadata": {
    "standard": "SpeziHealthKit",
    "fhirCode": "55411-3"
  }
}
```

With `config/integrations.healthkit-spezi.example.json`, the configured type to
PE sensor mapping is:

```text
HKCorrelationTypeIdentifierBloodPressure -> healthkit.blood-pressure
HKWorkoutTypeIdentifierWorkout           -> healthkit.exercise
HKCategoryTypeIdentifierSleepAnalysis    -> healthkit.sleep
```

The vector values are PE-normalized values, not raw clinical measurements.
The bridge should retain raw HealthKit/FHIR units in metadata if downstream
audit needs them.

## Verify

Run:

```bash
make e2e-healthkit-spezi
```

That proves the PE config, bridge token, BP/exercise/sleep ingest, and
downstream RE transitions.

## Sources

- SpeziHealthKit: https://github.com/StanfordSpezi/SpeziHealthKit
- SpeziHealthKit package: https://swiftpackageindex.com/StanfordSpezi/SpeziHealthKit
- Apple HealthKit setup: https://developer.apple.com/documentation/healthkit/setting_up_healthkit
- Apple authorization: https://developer.apple.com/documentation/HealthKit/authorizing-access-to-health-data
