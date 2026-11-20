Pip’s optional offline Kokoro voice

- Kokoro 82M, English v0.19 INT8 weights, voice embeddings and tokens:
  Apache 2.0. Model source: https://huggingface.co/hexgrad/kLegacy
  Conversion: https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.2/scripts/kokoro/v0.19
  Android downloads the model and English lexicon from the sherpa-onnx tts-models
  release when requested. The eSpeak data in the model archive is not installed.
- sherpa-onnx 2.0.1 (Apache 2.0) and ONNX Runtime (MIT): Android JNI runtime
  and its matching Kotlin TTS bindings are extracted from the pinned
  @siteed/sherpa-onnx.rn 2.0.1 archive. No React Native module is included.
  Source: https://github.com/k2-fsa/sherpa-onnx/tree/v2.0.1
  Prebuild: https://github.com/deeeed/audiolab/tree/main/packages/sherpa-onnx.rn
  ONNX Runtime: https://github.com/microsoft/onnxruntime
  ONNX Runtime notices are from its official 1.28.x Python distribution.
- Apache Ant (Apache 2.0) decodes the upstream tar.bz2 archive:
  https://github.com/apache/ant/tree/rel/1.10.15
