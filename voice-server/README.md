# VoxCPM2 voice for Pip

This optional bridge uses the [OpenBMB VoxCPM Python API](https://github.com/OpenBMB/VoxCPM/tree/2.0.3)
and a fictional voice described in text. It loads VoxCPM2 once and serves WAV audio over a
small OpenAI-compatible speech endpoint. No reference recording is required.

1. Use a separate Python 3.10–3.12 machine with an NVIDIA GPU, CUDA 12+ and working
   PyTorch 2.5+ as described by upstream. The first launch needs model-download access,
   disk space and time, or you can point `PIP_VOICE_MODEL` at a downloaded VoxCPM2 directory.
2. From `voice-server/`, install and start the bridge:

   ```sh
   python -m venv .venv
   . .venv/bin/activate
   pip install -r requirements.txt
   export PIP_VOICE_TOKEN="$(openssl rand -hex 32)"
   python server.py
   ```

   Store the generated token privately if you want it to survive restarts. The bridge listens
   on `127.0.0.1:8000` by default. Put it behind an HTTPS reverse proxy with a certificate
   trusted by your phone and make it reachable via a private network or VPN when possible.
   Don't expose the raw port. For example, on the same host, a Caddy site can forward only
   the speech path:

   ```caddyfile
   voice.example.com {
       reverse_proxy /v1/audio/speech 127.0.0.1:8000
   }
   ```

3. In **Pip settings → Voice → Configure VoxCPM server**, use the full public-facing
   URL (such as `https://voice.example.com/v1/audio/speech`) and your voice token. Try
   **Preview Pip’s voice**, then turn on **Read Pip’s replies aloud**. An existing
   OpenAI-compatible VoxCPM2 server is also supported if it returns WAV for model
   `openbmb/VoxCPM2`, voice `default`.

Optional: set `PIP_VOICE_STYLE` to a short description of the voice you want and restart
this bridge. That sets a voice style, not a guarantee of an identical timbre every time.
The server doesn't store replies or WAV files. It handles one synthesis at a time; Android
uses its offline voice when the server is busy, unreachable, or too slow. Configure any
reverse proxy not to log headers or request bodies and restrict access to trusted clients.

Run protocol tests without a model or GPU: `python -m unittest discover -s voice-server -v`
from the repository root. These tests use a fake synthesizer.
