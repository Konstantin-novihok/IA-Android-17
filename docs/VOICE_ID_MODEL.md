# On-device speaker embedding model

IA-Android-17 uses the CAM++ speaker-embedding ONNX checkpoint distributed by the sherpa-onnx project:
- Model: `3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx`
- Upstream release: https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models
- Expected SHA-256: `357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b`
- Expected graph contract: float32 input `x` shaped `[1, frames, 80]`; float32 output `embedding` shaped `[1, 512]`.
- Input features: 16 kHz mono PCM; Kaldi-style 80-bin log-mel fbank and per-bin global mean normalization.
- Runtime: ONNX Runtime Android 1.22.0. The model is downloaded only when the user requests installation; all subsequent inference is local.
- Upstream model is identified as CAM++ / 3D-Speaker trained on VoxCeleb. Review upstream model and dataset terms before commercial distribution; model weights and training-data rights are not necessarily identical.

The extracted vector is a biometric feature. This implementation does not claim liveness detection, spoof resistance, a calibrated verification threshold, or authorization readiness. Do not enable sensitive voice commands solely from cosine similarity.
