# Voice ID security status

This document distinguishes enrollment storage checks from authentication.

## Status at this revision

| # | Capability | Status | Evidence / constraint |
|---|---|---|---|
| 1 | Speaker embedding extraction | NOT IMPLEMENTED | Enrollment currently stores encrypted AAC clips. No approved embedding model/runtime is shipped. |
| 2 | Compare live voice with enrolled owner | NOT IMPLEMENTED | Android SpeechRecognizer returns text results; the app does not receive a trustworthy raw microphone stream for speaker scoring. |
| 3 | Decision threshold | NOT CALIBRATED | No model-specific score distributions, owner/non-owner validation corpus, or operating-point analysis. |
| 4 | Recording quality | PARTIAL | Enrollment checks container duration (1.8–12 s), file size and rough data rate. No calibrated clipping, SNR, reverberation or speech-activity analysis. |
| 5 | Replay/synthetic speech detection | NOT IMPLEMENTED | No anti-spoof model or trusted capture path. Name/code checks are not liveness. |
| 6 | Owner-confirmed re-enrollment | IMPLEMENTED WITH LIMITS | Deletion uses Android system biometric/device credential confirmation; re-enrollment begins after old profile is erased. Confirm this flow on target Android devices. |
| 7 | Audit and repeated-attempt protection | PARTIAL | Bounded local audit excludes audio/text. A persistent cooldown is enforced for blocked starts and denied matches; no tamper-resistant audit, and local app data reset can clear it. |

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
