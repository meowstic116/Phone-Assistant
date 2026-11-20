"""Small authenticated VoxCPM2 bridge. Run behind an HTTPS reverse proxy."""
import hmac
import io
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODEL = "openbmb/VoxCPM2"
PATH = "/v1/audio/speech"
MAX_TEXT = 420
MAX_BODY = 4096
MAX_WAV = 2_000_000
DEFAULT_STYLE = "A bright, warm, lightly playful voice with dry comic timing and clear diction"


def make_handler(token, synthesize):
    key = f"Bearer {token}".encode("utf-8")
    inference = threading.Lock()

    class SpeechHandler(BaseHTTPRequestHandler):
        def setup(self):
            self.request.settimeout(5.0)
            super().setup()

        def log_message(self, fmt, *args):
            # Never log credentials, reply text, or client-supplied paths.
            pass

        def send_payload(self, status, content_type, body):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def error(self, status, message):
            self.send_payload(status, "application/json", json.dumps({"error": message}).encode())

        def do_POST(self):
            if self.path != PATH:
                return self.error(404, "Unknown endpoint")
            if not hmac.compare_digest(self.headers.get("Authorization", "").encode("utf-8"), key):
                return self.error(401, "Invalid voice token")
            try:
                size = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                return self.error(400, "Invalid request length")
            if self.headers.get("Transfer-Encoding") or not 0 < size <= MAX_BODY:
                return self.error(413, "Invalid request size")
            if self.headers.get_content_type() != "application/json":
                return self.error(415, "Expected application/json")
            try:
                data = json.loads(self.rfile.read(size))
                if not isinstance(data, dict):
                    return self.error(400, "Expected a JSON object")
                text = data.get("input")
                if not isinstance(text, str) or not 0 < len(text.strip()) <= MAX_TEXT:
                    return self.error(400, "Expected a short reply")
                if (data.get("model", MODEL) != MODEL or data.get("voice", "default") != "default"
                        or data.get("response_format", "wav") != "wav"):
                    return self.error(400, "Only VoxCPM2 with the default voice and WAV output is supported")
            except (ValueError, TimeoutError):
                return self.error(400, "Invalid request body")
            if not inference.acquire(blocking=False):
                return self.error(503, "Voice is busy")
            try:
                wav = synthesize(text.strip())
                if len(wav) > MAX_WAV:
                    return self.error(500, "Audio too long")
            except Exception:
                return self.error(500, "Synthesis failed")
            finally:
                inference.release()
            self.send_payload(200, "audio/wav", wav)

    return SpeechHandler


def main():
    token = os.environ.get("PIP_VOICE_TOKEN", "")
    if len(token.strip()) < 24 or not token.isascii() or any(c.isspace() for c in token):
        raise SystemExit("Set PIP_VOICE_TOKEN to a random ASCII token of at least 24 characters without spaces.")
    style = os.environ.get("PIP_VOICE_STYLE", DEFAULT_STYLE).strip()
    if not style or len(style) > 200 or "(" in style or ")" in style:
        raise SystemExit("PIP_VOICE_STYLE must be a short voice description without parentheses.")

    import soundfile as sf
    from voxcpm import VoxCPM

    model = VoxCPM.from_pretrained(os.environ.get("PIP_VOICE_MODEL", MODEL), load_denoiser=False)

    def synthesize(text):
        audio = model.generate(text=f"({style}){text}", cfg_value=2.0, inference_timesteps=10,
                               retry_badcase=False)
        out = io.BytesIO()
        sf.write(out, audio, model.tts_model.sample_rate, format="WAV", subtype="PCM_16")
        return out.getvalue()

    port = int(os.environ.get("PIP_VOICE_PORT", "8000"))
    with ThreadingHTTPServer(("127.0.0.1", port), make_handler(token, synthesize)) as httpd:
        print(f"Pip voice ready on loopback port {port}. Place an HTTPS proxy in front of it.", flush=True)
        httpd.serve_forever()


if __name__ == "__main__":
    main()
