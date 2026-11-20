import http.client
import json
import threading
import unittest
from http.server import ThreadingHTTPServer

from server import MODEL, PATH, make_handler


class ProtocolTest(unittest.TestCase):
    def setUp(self):
        self.received = []
        self.token = "only-a-test-token-not-a-secret"
        self.wav = b"RIFF\x00\x00\x00\x00WAVE"

        def synth(text, style):
            self.received.append((text, style))
            return self.wav

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.token, synth))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def request(self, payload, token=None):
        client = http.client.HTTPConnection(*self.server.server_address, timeout=5)
        client.request("POST", PATH, json.dumps(payload), headers={
            "Authorization": f"Bearer {token or self.token}",
            "Content-Type": "application/json",
        })
        try:
            resp = client.getresponse()
            return resp.status, resp.getheader("Content-Type"), resp.read()
        finally:
            client.close()

    def test_request(self):
        self.assertEqual(self.request({"model": MODEL, "input": "Hello!"}), (200, "audio/wav", self.wav))
        self.assertEqual(self.received, [("Hello!", None)])
        self.assertEqual(self.request({"input": "Hi", "instructions": "Dry male voice"})[0], 200)
        self.assertEqual(self.received[-1], ("Hi", "Dry male voice"))

    def test_rejects_invalid_inputs_before_synthesis(self):
        self.assertEqual(self.request({"input": "Hi"}, token="wrong")[0], 401)
        self.assertEqual(self.request({"input": "a" * 421})[0], 400)
        self.assertEqual(self.request({"input": "a" * 5000})[0], 413)
        self.assertEqual(self.request({"input": "Hi", "response_format": "mp3"})[0], 400)
        self.assertEqual(self.request({"input": "Hi", "instructions": "(nested style)"})[0], 400)
        self.assertEqual(self.received, [])


if __name__ == "__main__":
    unittest.main()
