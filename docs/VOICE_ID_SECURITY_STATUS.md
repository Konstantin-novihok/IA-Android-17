# Voice ID security status

This document distinguishes enrollment storage checks from authentication.

## Status at this revision

| # | Capability | Status | Evidence / constraint |
|---|---|---|---|
| 1 | Speaker embedding extraction | NOT IMPLEMENTED | Profile stores encrypted AAC samples only; no runtime/model is integrated. |
| 2 | Compare live voice with enrolled owner | NOT IMPLEMENTED; vector math only | `SpeakerEmbeddingMath` provides cosine similarity and normalized mean for tests/research. No embeddings are extracted from recordings, and no live speaker comparison exists. Command gate rejects all attempts. |
| 3 | Decision threshold | NOT CALIBRATED; analysis utility added | `SpeakerEmbeddingMath.calibrate()` can calculate empirical FAR/FRR/EER estimate from labeled scores. No actual model scores or independent evaluation corpus exist, so no threshold is approved. Utility is not wired to authorization. |
| 4 | Recording quality | PARTIAL; PCM diagnostics utility added | `AudioSignalQuality.inspectPcm16()` estimates duration sufficiency, RMS, peak, clipping fraction, and zero-crossing rate for signed PCM16 mono/16 kHz. It is not connected to MediaRecorder enrollment and does not measure true SNR, VAD accuracy or reverberation. |
| 5 | Replay/synthetic speech detection | NOT IMPLEMENTED | No anti-spoof model or trusted capture path. Name/code checks are not liveness. |
| 6 | Owner-confirmed re-enrollment | IMPLEMENTED WITH LIMITS | Deletion uses Android system biometric/device credential confirmation; re-enrollment begins after old profile is erased. Confirm this flow on target Android devices. |
| 7 | Audit and repeated-attempt protection | PARTIAL | Local bounded event log, sliding failure budget/cooldown, audit events for denied activation. Not tamper-resistant; app data reset clears it. |

## Safety policy

Voice-only protected actions remain disabled. A caller name, scenario number, speech recognition confidence, encrypted enrollment samples, or a similarity heuristic must not unlock the device or authorize sensitive actions.

The current capture/recognition stack is not sufficient to make a reliable identity decision. A production design needs a bundled, licensed and audited speaker embedding model, verified live-audio capture, score normalization, calibrated threshold with documented false accept/false reject rates, and a tested anti-spoof/liveness component. These components must be benchmarked on target devices and threat conditions before the command gate can be opened.

## Quality check caveat

The present file/duration/rate checks catch grossly incomplete containers only. They do not prove signal quality, natural speech, or identity. AAC media metadata is not an acoustic-quality metric.

## Audit and cooldown

- 5 denials trigger a 10-minute cooldown.
- Audit is bounded to the most recent 80 records.
- Event records avoid command text and microphone audio.
- Preferences are local app data, not tamper-proof against a person with device access or data clearing.


## Engineering decision for requested implementation

The upstream sherpa-onnx project publishes Android arm64-v8a builds and a Kotlin/Java speaker embedding extractor API. This confirms the runtime architecture is feasible, but does not itself establish the license/redistribution terms of any particular pretrained weight file or its source training dataset. Do not infer a model license from the runtime's Apache-2.0 license.

Candidate model: choose only after the exact model card, source dataset terms, redistribution permissions, inference input shape, checksum, and provenance are recorded. The 3D-Speaker/CAM++ and WeSpeaker options are not interchangeable; some checkpoint datasets are CC-BY-4.0 while other pretrained assets have different conditions.

## Release gate

Protected scenarios remain denied until all are complete:
1. Exact model and immutable weight SHA-256 reviewed; notices/license bundled.
2. On-device raw PCM capture and feature/embedding extraction verified on target ARM64 handset.
3. Three or more enrollment samples are embedded; template is encrypted and vector dimension/version is recorded.
4. Live challenge sample comparison is implemented and tested; enrollment and verification audio are not fed via recognized text.
5. Threshold selected from independent, consented same-speaker and impostor recordings. Report FAR/FRR and confidence intervals; do not adopt a generic example threshold.
6. Replay/synthetic speech countermeasure is separately validated. Similarity alone is not liveness.
7. Screen locked, screen off, battery/thermal, noisy-room, device reboots, ASR interaction and cooldown are tested on real hardware.
8. Tests and review pass; only then can protected scenarios be explicitly enabled.

Voice verification is not a substitute for Android device credentials for actions that could disclose private data, send messages, make payments, or change security settings.


## Added testable utilities (not authentication features)

- `SpeakerEmbeddingMath.kt`: cosine similarity, normalized mean, empirical threshold analysis from labeled scores.
- `AudioSignalQuality.kt`: preliminary PCM16 waveform metrics.
- JVM tests cover basic vector geometry/calibration input and simple waveform quality cases.
- Workflow now runs `:app:testDebugUnitTest` before building APK.

These utilities do not constitute a deployed speaker embedding model, liveness detector, or calibrated biometric authentication. A passing unit test only validates code behavior on synthetic data.
