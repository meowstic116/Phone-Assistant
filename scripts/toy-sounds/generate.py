#!/usr/bin/env python3
"""Generates the toy box sound effects as .mp3 files with AudioLDM.

Source model: haoheliu/AudioLDM (https://github.com/haoheliu/AudioLDM),
checkpoint "audioldm-s-full" (2.6 GB, md5 5451d3628b3a2b535f87044685fe102c,
from zenodo.org/record/7600541). The model code is MIT; the checkpoint is
CC-BY-NC 4.0 — these sounds are for Pip's personal toy box, not for resale.

Expected layout (outside the repository):
  ~/.cache/audioldm/audioldm-s-full.ckpt      the checkpoint (2.6 GB)

Everything else ships in this folder: vendor/audioldm is the patched model
code (modern-librosa compatible, offline tokenizer, mmap checkpoint load)
and vendor/roberta-base is CLAP's tokenizer. Raw takes land in .takes/.

Output goes straight into the app: app/src/main/res/raw/toy_*.mp3.

The run is resumable: a sound whose .mp3 already exists is skipped. Use
--only pop,kick to (re)generate a few, --force to redo everything.

python3 scripts/toy-sounds/generate.py [--only a,b] [--force] [--steps N] [--dry]
"""

import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
VENDOR = os.path.join(HERE, "vendor")
CACHE = os.path.expanduser("~/.cache/audioldm")
OUT_WAV = os.path.join(HERE, ".takes")
CKPT = os.path.join(CACHE, "audioldm-s-full.ckpt")
RES_RAW = os.path.join(os.path.dirname(os.path.dirname(HERE)),
                       "app", "src", "main", "res", "raw")

# (file name, prompt, max seconds, tail ms, ddim steps)
# The prompt phrasing follows AudioLDM's AudioSet-caption training: what the
# event is, the material, the room ("close-up", "dry"), and "isolated sound
# effect" to keep speech and music out.
SOUNDS = [
    # The thirteen the toy box already knows.
    ("toy_pop", "A single small water bubble popping, close-up, dry, isolated sound effect", 0.45, 80, 150),
    ("toy_squeak", "A short rubber squeak, a wet cork twisting in a glass bottle, isolated sound effect", 0.65, 120, 120),
    ("toy_pour", "Water pouring into a drinking glass, close-up, isolated sound effect", 1.1, 200, 120),
    ("toy_glug", "Gulping down a glass of water, swallowing liquid, close-up, isolated sound effect", 1.1, 200, 150),
    ("toy_grow", "A soft gentle chime, three magical bell notes, quiet, isolated sound effect", 1.6, 450, 120),
    ("toy_chalk", "Pastel chalk scratching on rough paper, one short stroke, close-up, dry", 0.45, 80, 120),
    ("toy_splat", "A wet blob of thick paint splattering onto canvas, close-up, isolated sound effect", 0.65, 120, 120),
    ("toy_kick", "Kicking a soccer ball hard with a boot, close-up, dry, isolated sound effect", 0.55, 100, 150),
    ("toy_goal", "A short triumphant trumpet fanfare, bright and celebratory", 1.8, 500, 150),
    ("toy_whistle", "A referee whistle blowing one short blast, isolated sound effect", 0.9, 150, 120),
    ("toy_boing", "A cartoon spring boing, a comical bouncing sound effect", 0.75, 150, 120),
    ("toy_tap", "A single light wooden tap, knocking on a small wooden block, close-up, dry", 0.3, 60, 120),
    ("toy_sparkle", "Magical sparkly glissando, tiny twinkling chimes, isolated sound effect", 1.7, 450, 120),
    # New: toys that were quiet before.
    ("toy_clink", "Ice cubes clinking in a glass of lemonade, close-up, isolated sound effect", 0.9, 200, 120),
    ("toy_drip", "A single water drop falling into a puddle, close-up, isolated sound effect", 0.55, 120, 150),
    ("toy_swipe", "A quick soft swish of air, a fast swipe, isolated sound effect", 0.5, 100, 120),
    ("toy_ding", "A small service bell dinging once, bright and clean, isolated sound effect", 1.1, 350, 120),
    ("toy_bounce", "A rubber ball bouncing twice on a wooden floor, close-up, isolated sound effect", 0.9, 150, 120),
    ("toy_spray", "A spray bottle spraying twice, quick misting sounds, close-up", 0.9, 150, 120),
    ("toy_whoosh", "A soft airy whoosh, an object swinging past quickly, isolated sound effect", 0.7, 120, 120),
]


def postprocess(name, wav_path, mp3_path, max_seconds, tail_ms):
    """Trim to the event, normalise, and encode one take to .mp3."""
    import numpy as np
    import soundfile as sf
    from scipy.signal import resample_poly
    import lameenc

    audio, rate = sf.read(wav_path, dtype="float32")
    if audio.ndim > 1:
        audio = audio.mean(axis=1)
    peak = float(np.max(np.abs(audio))) or 1.0

    # Event trim: everything the sound actually occupies, with a small lead.
    hop = max(1, int(rate * 0.025))
    loud = np.abs(audio) > (0.012 * peak)
    idx = np.flatnonzero(loud)
    if idx.size:
        start = max(0, idx[0] - int(rate * 0.015))
        end = min(len(audio), idx[-1] + int(rate * tail_ms / 1000.0))
    else:
        start, end = 0, len(audio)
    end = min(end, start + int(rate * max_seconds))
    clip = audio[start:end].copy()

    # Normalise to -1.5 dBFS with a soft knee, then fade the edges.
    peak = float(np.max(np.abs(clip))) or 1.0
    clip = np.tanh(clip / peak * 1.15) * 0.84
    fade = min(int(rate * 0.005), len(clip) // 2)
    if fade > 0:
        ramp = np.linspace(0.0, 1.0, fade, dtype=np.float32)
        clip[:fade] *= ramp
        clip[-fade:] *= ramp[::-1]

    # The model speaks 16 kHz; the app expects ordinary CD-rate files.
    clip = resample_poly(clip, 441, 160).astype(np.float32)

    pcm = (np.clip(clip, -1.0, 1.0) * 32767.0).astype(np.int16)
    enc = lameenc.Encoder()
    enc.set_bit_rate(192)
    enc.set_in_sample_rate(44100)
    enc.set_channels(1)
    enc.set_quality(2)
    data = enc.encode(pcm.tobytes()) + enc.flush()
    with open(mp3_path, "wb") as fh:
        fh.write(data)
    print(f"  wrote {mp3_path} ({len(data) // 1024} KB, {len(clip) / 44100:.2f}s)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", help="comma-separated file names, e.g. toy_pop,toy_kick")
    ap.add_argument("--force", action="store_true", help="regenerate even if the mp3 exists")
    ap.add_argument("--steps", type=int, help="override ddim steps for every sound")
    ap.add_argument("--dry", action="store_true", help="print the plan and exit")
    args = ap.parse_args()

    want = {s.strip() for s in args.only.split(",")} if args.only else None
    todo = [s for s in SOUNDS
            if (want is None or s[0] in want)
            and (args.force or not os.path.exists(os.path.join(RES_RAW, s[0] + ".mp3")))]
    print(f"{len(todo)} of {len(SOUNDS)} sounds to generate")
    if args.dry or not todo:
        return 0
    if not os.path.exists(CKPT):
        print(f"missing checkpoint: {CKPT}", file=sys.stderr)
        return 1

    os.environ.setdefault("AUDIOLDM_CACHE_DIR", CACHE)
    # The workspace vendor copy is the source of truth: it survives sandbox
    # restarts, carries the sandbox patches, and never touches the network.
    os.environ.setdefault("ROBERTA_TOKENIZER_DIR", os.path.join(VENDOR, "roberta-base"))
    sys.path.insert(0, os.path.join(CACHE, "AudioLDM"))
    sys.path.insert(0, VENDOR)

    import torch
    torch.set_num_threads(2)
    from audioldm import build_model, text_to_audio

    os.makedirs(OUT_WAV, exist_ok=True)
    os.makedirs(RES_RAW, exist_ok=True)

    print("loading AudioLDM (mmap, this takes a minute)...")
    model = build_model(ckpt_path=CKPT, model_name="audioldm-s-full")
    for i, (name, prompt, max_seconds, tail_ms, steps) in enumerate(todo):
        print(f"[{i + 1}/{len(todo)}] {name}: {prompt}")
        wav_path = os.path.join(OUT_WAV, name + ".wav")
        if args.force or not os.path.exists(wav_path):
            waveform = text_to_audio(
                model, prompt,
                seed=42 + SOUNDS.index((name, prompt, max_seconds, tail_ms, steps)),
                ddim_steps=args.steps or steps,
                duration=2.5,
                batchsize=1,
                guidance_scale=3.5,
                n_candidate_gen_per_text=1,
            )
            import soundfile as sf
            import numpy as np
            arr = waveform[0] if getattr(waveform, "ndim", 1) > 1 else waveform
            arr = np.asarray(arr, dtype=np.float32).flatten()
            sf.write(wav_path, arr, 16000)
        postprocess(name, wav_path, os.path.join(RES_RAW, name + ".mp3"),
                    max_seconds, tail_ms)
    print("done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
