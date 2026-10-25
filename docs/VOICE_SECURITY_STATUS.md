# Voice security implementation status

This branch keeps voice-triggered actions disabled unless identity verification is actually established. No command may rely on transcript recognition, encrypted enrollment clips, cosine utilities, signal quality, or caller-provided metadata as owner proof.

## Current implemented groundwork

- Owner enrollment captures raw PCM16, mono, 16 kHz through AudioRecord (not an AAC/MP4 container).
- PCM inspection checks sample rate, duration, RMS level, peak, clipping and zero-crossing statistics. These are acquisition-quality signals only.
- Three example recordings are encrypted at rest using an AES-GCM key in Android Keystore.
- Voice attempt throttling and bounded local security event logging exist.
- Vector cosine/centroid/calibration math is isolated as experimental evaluation code and is not wired into command authorization.
- VoiceScenarioService fails closed: starts foreground, reports verifier unavailable and exits. No protected scenario runs.

## Acceptance status by requested phase

### A — Owner enrollment
- [x] Collect several consented owner samples.
- [x] Capture as controlled raw PCM16 mono at 16 kHz.
- [x] Basic signal quality checks.
- [x] Encrypt recordings with Android Keystore AES-GCM.
- [ ] A trained, bundled speaker-embedding model and reproducible model/version metadata.
- [ ] Aggregate learned embeddings and encrypt the template separately.
- [ ] Authenticated reset/re-enrollment and actual enrollment workflow tests on device.

### B — Verification
- [x] Experimental vector operations exist for evaluation only.
- [ ] Live PCM capture in the command authorization path.
- [ ] Same trained embedding extractor for enrollment and verification.
- [ ] Validated threshold on representative independent owner/impostor cohorts.
- [ ] Calibrated score persistence, signed decision provenance, and operational integration.
- [ ] Do not allow speaker similarity alone to authorize sensitive actions.

### C — Spoof/replay defenses
- [x] Unknown/unauthenticated commands fail closed; simple cooldown and attempt budget.
- [ ] Robust PAD/anti-spoof model tested on replay, TTS, voice conversion and adversarial cases.
- [ ] Challenge-response based on unpredictable fresh prompts, expiry, and one-time consumption.
- [ ] Distinguish audio source capture from transcript recognition; the Android system SpeechRecognizer API does not expose raw microphone frames to this service.
- [ ] Defense in depth: device credential/system BiometricPrompt confirmation for sensitive operations, where Android permits it.
- A spoken challenge alone is not reliable liveness proof; a replay can include the challenge response.

### D — Test plan
- [x] JVM tests cover PCM level/clipping and vector arithmetic (subject to green CI).
- [ ] Diverse speaker set, language/accent, phone models, mic distances, background noise, echo, playback and synthesized/converted speech.
- [ ] Locked screen, screen off, Doze, process death, reboot and permission-revocation tests.
- [ ] Measure FAR/FRR/EER with independent train/calibration/test splits and report confidence intervals.
- [ ] Red-team command repeats and failure recovery.
- Never claim a security level from a handful of owner samples or synthetic tests.

## Important product decision

The app may expose a non-security voice convenience feature for harmless read-only tasks. Sensitive actions (messages, contact creation, device controls, purchases, unlocking or data disclosure) require a separate trusted confirmation flow. Voice matching is probabilistic and must not replace Android's lock-screen authentication.
