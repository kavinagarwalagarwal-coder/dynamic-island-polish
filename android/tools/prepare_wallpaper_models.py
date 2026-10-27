#!/usr/bin/env python3
"""Build-time only: bundle pinned public models. The Android app never downloads them."""
import hashlib
import pathlib
import urllib.request
import zipfile

output = pathlib.Path(__file__).resolve().parents[1] / 'app/build/generated/wallpaperAssets'
output.mkdir(parents=True, exist_ok=True)
models = [
    ('wallpaper-classifier.tflite', 'image_classifier/efficientnet_lite0/float32/1/efficientnet_lite0.tflite',
     '6c7ab0a6e5dcbf38a8c33b960996a55a3b4300b36a018c4545801de3a3c8bde0', 'labels_without_background.txt', 'wallpaper-labels.txt'),
    ('wallpaper-segmenter.tflite', 'image_segmenter/deeplab_v3/float32/1/deeplab_v3.tflite',
     'ff36e24d40547fe9e645e2f4e8745d1876d6e38b332d39a82f0bf0f5d1d561b3', 'labelmap.txt', 'wallpaper-segments.txt'),
]
for filename, source, digest, labels, destination in models:
    target = output / filename
    data = target.read_bytes() if target.exists() else b''
    if hashlib.sha256(data).hexdigest() != digest:
        with urllib.request.urlopen('https://storage.googleapis.com/mediapipe-models/' + source, timeout=120) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != digest:
            raise RuntimeError('Model checksum mismatch: ' + filename)
        temporary = target.with_suffix('.download')
        temporary.write_bytes(data)
        temporary.replace(target)
    with zipfile.ZipFile(target) as archive:
        (output / destination).write_bytes(archive.read(labels))
print('Verified and bundled two offline wallpaper models.')

# Download a real model test photo only for instrumentation builds, never into the app APK.
import sys
if '--test' in sys.argv:
    tests = output.parent / 'wallpaperTestAssets'
    tests.mkdir(exist_ok=True)
    target = tests / 'cat.jpg'
    digest = '2533197401eebe9410ea4d063f86c43fbd2666f3e8165a38aca155c0d09c21be'
    data = target.read_bytes() if target.exists() else b''
    if hashlib.sha256(data).hexdigest() != digest:
        data = urllib.request.urlopen('https://storage.googleapis.com/mediapipe-assets/cat.jpg', timeout=60).read()
        if hashlib.sha256(data).hexdigest() != digest:
            raise RuntimeError('Test photo checksum mismatch')
        target.write_bytes(data)
