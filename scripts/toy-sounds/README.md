# Toy sound effects — AudioLDM pipeline and handoff

Replaces the on-device synthesizer (`ToySounds.kt`) with real `.mp3` files in
`app/src/main/res/raw/`, generated with AudioLDM
(https://github.com/haoheliu/AudioLDM, model `audioldm-s-full`).

## Why this note exists

The only missing piece is the **2.6 GB checkpoint**. It lives on zenodo.org
(md5 `5451d3628b3a2b535f87044685fe102c`, record 7600541), which old sandboxes
block at the TLS layer (strict network allowlist). Once a sandbox has
zenodo.org allowed, everything runs locally.

## What is already staged (in this folder)

- `generate.py` — 20 sound prompts (13 existing families + clink, drip, swipe,
  ding, bounce, spray, whoosh), per-sound trim/length profiles, resumable
  (`--only`, `--force`), post-processing (event-trim → normalize → 44.1 kHz →
  192 kbps mono .mp3 into `res/raw/`).
- `vendor/audioldm/` — the model code, patched for this sandbox:
  - `audio/stft.py`: `librosa.filters.mel` call made keyword-compatible
  - `clap/encoders.py` + `clap/training/data.py`: tokenizer loads from
    `ROBERTA_TOKENIZER_DIR` (or `~/.cache/audioldm/roberta-base`) instead of
    huggingface.co
  - `pipeline.py`: checkpoint loads with `mmap` + `assign` so 2.6 GB fits in
    3.8 GB RAM without doubling
- `vendor/roberta-base/` — CLAP's roberta tokenizer files (fetched from the
  `Artrajz/vits-simple-api` GitHub repo), load-tested.

## To run (in a sandbox that can reach zenodo.org)

1. `mkdir -p ~/.cache/audioldm && cd ~/.cache/audioldm`
   `curl -L -o audioldm-s-full.ckpt 'https://zenodo.org/record/7600541/files/audioldm-s-full?download=1'`
   verify `md5 == 5451d3628b3a2b535f87044685fe102c`
2. Python stack (py3.11): `pip3 install --break-system-packages numpy scipy
   lameenc torch torchaudio torchvision transformers==4.40.2 librosa soundfile
   einops tqdm ftfy regex pyyaml progressbar2 torchlibrosa==0.0.9 pandas chardet`
   (no GPU; torch CPU is fine — ~2 min at sandbox speeds)
3. `python3 scripts/toy-sounds/generate.py` — resumable, ~5–15 min per sound on
   2 CPU cores; poll it. Takes land in `.takes/`, mp3s in `app/src/main/res/raw/`.
4. **STOP HERE.** `git add app/src/main/res/raw/*.mp3 && git commit -m "toy
   sounds: AudioLDM-generated effects" && git push origin arena/568149f5-phone-assistant`.

## After the mp3s are committed — done in the follow-up session

The generating session should stop at step 4 above. The follow-up session
(the original conversation) fetches the mp3s and finishes: the Android
integration below, then **deletes this entire `scripts/toy-sounds/` folder**
so nothing in the repo references zenodo or the model — the mp3s alone are
the deliverable. Keep one line of provenance in the header comment of the
new `ToyAudio.kt` ("generated with AudioLDM").

1. Rewrite `ToyAudio.kt` on `SoundPool` loading `R.raw.toy_*` (keep its API:
   `play/playPair/enabled/volume/duck/audibleNow/ringerMuted/summary`, the
   0.2 volume floor, per-play rate jitter 0.94–1.06 via `SoundPool.play` rate).
2. Delete `ToySounds.kt` (the whole synthesizer) — this also lifts the old
   "no audio files in this project" rule, reversed by the user 2026-11-23.
3. Move the `Sound` enum into `ToyAudio`; update call sites `ToySounds.Sound.X`
   → `ToyAudio.Sound.X` in: BubbleWrap, Lemonade, Painting, Plant, Prop,
   Soccer, OverlayService (PropHost signature), PipSettingsActivity.
4. Add sounds where toys were silent: doomscroll swipe/tap (FeedRunner),
   lemonade clink, plant spray, etc. (see SOUNDS table in generate.py).
5. Tests: remove the ToySounds synthesis pins in `tests/JobProgressTest.kt`
   (~lines 1100–1135); drop `ToySounds.kt` from `tests/run-job-progress.sh`;
   pin res/raw coverage in ScreensRenderTest if cheap.
6. `tests/run-job-progress.sh` + `tests/run-settings-layout.sh` green →
   commit → push `arena/568149f5-phone-assistant` (no PR unless asked).

## Notes

- Checkpoint license is CC-BY-NC 4.0 (personal toy box, not for resale).
- The checkpoint and pip packages cannot live in the workspace (2.6 GB breaks
  the artifact cap); everything else is here on purpose so a sandbox restart
  loses nothing.
- This whole folder is scaffolding: it gets deleted once the mp3s are in
  place (see above). The sounds themselves are the lasting artifact.
