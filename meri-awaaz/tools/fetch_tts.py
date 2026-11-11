#!/usr/bin/env python3
"""Download the Hindi text-to-speech model the app bundles and check its interface.

Model: Meta MMS-TTS Hindi (VITS, reads Devanagari characters directly), in the ONNX export
published for sherpa-onnx. Licence of the weights: CC-BY-NC 4.0 (non-commercial).

Usage: fetch_tts.py --out DIR      -> DIR/tts_hi.onnx, DIR/tts_hi_tokens.txt
"""
import argparse
import os
import urllib.request

import numpy as np
import onnxruntime as ort

BASE = "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/hin"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    model = os.path.join(a.out, "tts_hi.onnx")
    tokens = os.path.join(a.out, "tts_hi_tokens.txt")
    for url, path in ((BASE + "/model.onnx", model), (BASE + "/tokens.txt", tokens)):
        print("downloading", url)
        urllib.request.urlretrieve(url, path)
        print("  ->", path, os.path.getsize(path), "bytes")

    sess = ort.InferenceSession(model, providers=["CPUExecutionProvider"])
    print("inputs :", [(i.name, i.type, i.shape) for i in sess.get_inputs()])
    print("outputs:", [(o.name, o.type, o.shape) for o in sess.get_outputs()])
    meta = sess.get_modelmeta().custom_metadata_map
    print("metadata:", meta)

    table = {}
    for line in open(tokens, encoding="utf-8").read().split("\n"):
        if not line.strip("\n"):
            continue
        sym, idx = line.rsplit(" ", 1)
        table[sym] = int(idx)
    print("tokens:", len(table), repr("".join(sorted(k for k in table if len(k) == 1))))
    print("multi-char tokens:", [k for k in table if len(k) != 1])

    # Interface the app relies on.
    names = [i.name for i in sess.get_inputs()]
    assert names == ["x", "x_length", "noise_scale", "length_scale", "noise_scale_w"], names
    assert int(meta.get("sample_rate", 0)) == 16000, meta
    assert int(meta.get("add_blank", 0)) == 1, meta
    for ch in "नमस्ते आज":
        assert ch in table, f"token table lacks {ch!r}"

    text = "नमस्ते आज हम बात करेंगे"
    ids = [0]
    for ch in text:
        ids += [table[ch], 0]
    x = np.array([ids], dtype=np.int64)
    y = sess.run(None, {
        "x": x, "x_length": np.array([x.shape[1]], dtype=np.int64),
        "noise_scale": np.array([0.667], dtype=np.float32),
        "length_scale": np.array([1.0], dtype=np.float32),
        "noise_scale_w": np.array([0.8], dtype=np.float32),
    })[0]
    print("test sentence ->", y.shape, "peak", float(np.abs(y).max()), "seconds", y.size / 16000)
    assert y.size > 16000 and np.isfinite(y).all() and np.abs(y).max() > 0.05
    print("TTS MODEL OK")


if __name__ == "__main__":
    main()
