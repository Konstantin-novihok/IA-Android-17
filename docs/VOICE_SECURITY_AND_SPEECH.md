# Voice security and speech I/O validation

## Components added

- PAD model: SpeechAntiSpoofingBenchmarks/AASIST ONNX, MIT license, pinned SHA-256 in `AntiSpoofModelInstaller`.
- Input contract: mono PCM16, 16 kHz; deterministic 64,600-sample window (~4.04 s), centered crop or zero-padding.
- Output contract: two class logits; index 0 = spoof, index 1 = bona fide, following the model card. The current 0.5 softmax operating point is provisional, not a calibrated security threshold.
- Additional signal heuristics run after AASIST. Any rejection prevents speaker comparison for that attempt.
- Chat speech output uses Android `TextToSpeech`; tap **Озвучить** on an assistant answer.
- Chat speech input launches Android `RecognizerIntent`; the recognized transcript is placed into the composer and is not sent until the user taps Send.

## Important limitations

AASIST is a trained PAD model, but no PAD model proves liveness. The upstream benchmark reports 0.83% EER on ASVspoof2019 LA, 12.35% on ASVspoof2021 LA, 17.04% on ASVspoof2021 DF, 43.01% on InTheWild and 51.05% on CD-ADD for this checkpoint. These are upstream reported results, not results measured by this Android app. Modern TTS and voice cloning may pass. The model must not be used as the only factor for authorizing sensitive actions.

Android's `EXTRA_PREFER_OFFLINE` is a preference and may be ignored by a recognition provider. Android TextToSpeech uses the installed default engine and voice; offline operation and Russian/Ukrainian language support depend on installed voice data. The current chat integration is speech input/output, not hands-free wake-word listening or arbitrary device command execution.

## Device test matrix

Run on the target Samsung phone after installing the PAD model. Use consenting speakers and locally generated/owned test audio.

| Test class | Procedure | Record |
| --- | --- | --- |
| Genuine speech | 20+ fresh live utterances, several phrases and distances | bona-fide score, false rejects, latency |
| Loudspeaker replay | Play a recording through a separate phone/speaker into the microphone | false accepts / score distribution |
| Ordinary TTS | Generate speech from several available voices and languages, replay acoustically | false accepts |
| Voice cloning | Use consented test samples from a clone system; play into microphone | false accepts |
| Voice conversion | Use consented converted samples; replay into microphone | false accepts |
| Background conditions | Quiet room, fan/noise, farther microphone distance | false rejects and score shifts |
| Speech output | Tap **Озвучить** on Russian, Ukrainian and English answers | language availability, pronunciation, clipping, latency |
| Speech input | Tap microphone, speak each supported language, inspect transcript before sending | recognition errors; verify no unintended send |

Do not enable voice-command authorization based on these checks alone. For meaningful evaluation, collect a held-out set, report FAR and FRR at the chosen threshold, and evaluate attack types not used to tune that threshold. Keep raw test recordings only with consent and delete them when no longer needed.
