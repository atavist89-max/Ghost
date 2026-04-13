#!/usr/bin/env python3
"""
Convert a raw Piper TTS model to Sherpa-ONNX compatible format.

Run this script ON YOUR DESKTOP (not on Android) before copying model files
to the device. It patches ONNX metadata and generates tokens.txt.

Usage:
    python3 scripts/convert_hal_model.py /path/to/model/directory

Requirements:
    pip install onnx==1.17.0

After running, copy these files to your Android device next to the Gemma model:
    - hal.onnx   (patched with metadata)
    - hal.onnx.json   (unchanged)
    - tokens.txt              (generated)
    - espeak-ng-data/         (download link below)

Download espeak-ng-data:
    wget https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2
    tar xf espeak-ng-data.tar.bz2
"""

import argparse
import json
import os
import sys
from typing import Any, Dict


def load_config(path: str) -> Dict[str, Any]:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def generate_tokens(config: Dict[str, Any], output_path: str):
    id_map = config["phoneme_id_map"]
    with open(output_path, "w", encoding="utf-8") as f:
        for symbol, ids in id_map.items():
            if isinstance(ids, list):
                token_id = ids[0]
            else:
                token_id = ids
            f.write(f"{symbol} {token_id}\n")
    print(f"Generated: {output_path}")


def add_meta_data(filename: str, meta_data: Dict[str, Any]):
    try:
        import onnx
    except ImportError:
        print("Error: The 'onnx' Python package is required.")
        print("Install it with: pip install onnx==1.17.0")
        sys.exit(1)

    model = onnx.load(filename)

    # Clear existing metadata to avoid duplicates
    while len(model.metadata_props):
        model.metadata_props.pop()

    for key, value in meta_data.items():
        meta = model.metadata_props.add()
        meta.key = key
        meta.value = str(value)

    onnx.save(model, filename)
    print(f"Patched ONNX metadata: {filename}")


def main():
    parser = argparse.ArgumentParser(
        description="Convert HAL 9000 Piper model for Sherpa-ONNX"
    )
    parser.add_argument(
        "model_dir",
        type=str,
        help="Directory containing hal.onnx and hal.onnx.json",
    )
    args = parser.parse_args()

    model_dir = os.path.abspath(args.model_dir)
    onnx_path = os.path.join(model_dir, "hal.onnx")
    json_path = os.path.join(model_dir, "hal.onnx.json")
    tokens_path = os.path.join(model_dir, "tokens.txt")
    espeak_dir = os.path.join(model_dir, "espeak-ng-data")

    if not os.path.isfile(onnx_path):
        print(f"Error: ONNX model not found: {onnx_path}")
        sys.exit(1)

    if not os.path.isfile(json_path):
        print(f"Error: JSON config not found: {json_path}")
        sys.exit(1)

    config = load_config(json_path)

    print("Generating tokens.txt from phoneme_id_map...")
    generate_tokens(config, tokens_path)

    sample_rate = config["audio"]["sample_rate"]
    if sample_rate == 22500:
        print("Changing sample rate from 22500 to 22050")
        sample_rate = 22050

    voice = config.get("espeak", {}).get("voice", "en-us")
    num_speakers = config.get("num_speakers", 1)
    language_name = config.get("language", {}).get("code", "en-us").split("-")[0]

    meta_data = {
        "model_type": "vits",
        "comment": "piper",  # REQUIRED: tells Sherpa-ONNX to use Piper inference path
        "language": language_name,
        "voice": voice,
        "has_espeak": 1,
        "n_speakers": num_speakers,
        "sample_rate": sample_rate,
    }

    print("Adding metadata to ONNX model...")
    print(json.dumps(meta_data, indent=2))
    add_meta_data(onnx_path, meta_data)

    if not os.path.isdir(espeak_dir):
        print("\n" + "=" * 60)
        print("WARNING: espeak-ng-data directory is missing!")
        print("Download and extract it with:")
        print(
            "  wget https://github.com/k2-fsa/sherpa-onnx/releases/download/"
            "tts-models/espeak-ng-data.tar.bz2"
        )
        print("  tar xf espeak-ng-data.tar.bz2 -C " + model_dir)
        print("=" * 60)
    else:
        print(f"Found espeak-ng-data: {espeak_dir}")

    print("\nConversion complete! Copy these files to your Android device:")
    print(f"  - {onnx_path}")
    print(f"  - {json_path}")
    print(f"  - {tokens_path}")
    if os.path.isdir(espeak_dir):
        print(f"  - {espeak_dir}/")


if __name__ == "__main__":
    main()
