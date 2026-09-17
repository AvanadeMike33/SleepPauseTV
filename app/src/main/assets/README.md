# YAMNet asset

The build automatically creates `yamnet.tflite` by running the Gradle task
`downloadYamnetModel` before `preBuild`.

Default source:
https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/audio_classification/android/lite-model_yamnet_classification_tflite_1.tflite

To prepare an offline build:

```bash
python3 scripts/download_yamnet.py
```

After download, the model is bundled in the APK and inference does not require
Internet access. Override the URL with `-PyamnetModelUrl=<URL>` if necessary.
