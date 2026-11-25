from datetime import datetime, timezone
import importlib.util
from io import BytesIO
from pathlib import Path
import unittest
from unittest.mock import Mock, call, patch
from urllib.error import HTTPError
from urllib.request import Request


spec = importlib.util.spec_from_file_location(
    "mirror", Path(__file__).resolve().parents[1] / "actions/setup-maven-mirror/proxy.py")
mirror = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mirror)


class MirrorTests(unittest.TestCase):
    def setUp(self):
        self.path = "/org/robolectric/android-all-instrumented/new-sdk/runtime.jar"
        self.url = mirror.MIRROR + self.path
        self.get = self.enterContext(patch.object(mirror.opener, "open"))
        self.sleep = self.enterContext(patch.object(mirror.time, "sleep"))
        self.enterContext(patch.object(mirror.random, "uniform", return_value=1))

    def error(self, status, headers=None):
        return HTTPError(self.url, status, "fixture", headers or {}, None)

    def test_retries_the_requested_artifact_without_a_version_list(self):
        self.get.side_effect = [self.error(429) for _ in range(4)] + [BytesIO(b"JAR")]
        with mirror.open_artifact(self.url, "GET") as response:
            self.assertEqual(response.read(), b"JAR")
        self.assertEqual([item.args[0].full_url for item in self.get.call_args_list], [self.url] * 5)
        self.assertTrue(all(item.args[0].get_method() == "GET" for item in self.get.call_args_list))
        self.assertEqual(self.sleep.call_args_list, [call(6), call(11), call(21), call(41)])

    def test_retry_after_and_exhaustion_across_test_classes(self):
        self.get.side_effect = [self.error(429, {"Retry-After": "60"}) for _ in range(5)]
        handler = Mock(path="/central" + self.path, command="GET")
        handler.server.retry_at = {}
        mirror.MirrorHandler.serve_artifact(handler)
        mirror.MirrorHandler.serve_artifact(handler)
        self.assertEqual(handler.send_error.call_args_list, [call(429), call(429)])
        self.assertEqual(self.get.call_count, 5)
        self.assertEqual(self.sleep.call_args_list, [call(60)] * 4)

    def test_retry_after_http_date(self):
        with patch.object(mirror, "datetime") as clock:
            clock.now.return_value = datetime(2026, 11, 25, tzinfo=timezone.utc)
            self.assertEqual(mirror.retry_delay("Wed, 25 Nov 2026 00:01:30 GMT", 5), 90)
        self.assertEqual(mirror.retry_delay("invalid", 5), 6)

    def test_long_retry_after_returns_429_without_sleeping(self):
        self.get.side_effect = self.error(429, {"Retry-After": "7200"})
        with self.assertRaises(HTTPError) as caught:
            mirror.open_artifact(self.url, "GET")
        self.assertEqual(caught.exception.code, 429)
        self.get.assert_called_once()
        self.sleep.assert_not_called()

    def test_retries_share_one_deadline(self):
        clock = self.enterContext(patch.object(mirror.time, "monotonic", return_value=1000))

        def advance_clock(seconds):
            clock.return_value += seconds

        self.sleep.side_effect = advance_clock
        self.get.side_effect = self.error(429, {"Retry-After": "180"})
        with self.assertRaises(HTTPError) as caught:
            mirror.open_artifact(self.url, "GET")
        self.assertEqual(caught.exception.code, 429)
        self.assertEqual(self.get.call_count, 2)
        self.sleep.assert_called_once_with(180)

    def test_mirror_miss_serves_origin_response_for_get_and_head(self):
        path = self.path + "?variant=1"
        for method in ("GET", "HEAD"):
            with self.subTest(method=method):
                self.get.reset_mock()
                response = BytesIO(b"JAR")
                response.status = 200
                response.headers = {"Content-Length": "3"}
                self.get.side_effect = [HTTPError(mirror.MIRROR + path, 404, "missing", {}, None), response]
                handler = Mock(path="/central" + path, command=method, wfile=BytesIO())
                handler.server.retry_at = {}
                mirror.MirrorHandler.serve_artifact(handler)
                requests = [item.args[0] for item in self.get.call_args_list]
                self.assertEqual([(r.full_url, r.get_method()) for r in requests],
                                 [(mirror.MIRROR + path, method), (mirror.CENTRAL + path, method)])
                handler.send_response.assert_called_once_with(200)
                handler.send_header.assert_called_once_with("Content-Length", "3")
                self.assertEqual(handler.wfile.getvalue(), b"JAR" if method == "GET" else b"")

    def test_origin_miss_returns_404_without_retry(self):
        self.get.side_effect = [self.error(404), HTTPError(mirror.CENTRAL + self.path, 404, "missing", {}, None)]
        handler = Mock(path="/central" + self.path, command="GET")
        handler.server.retry_at = {}
        mirror.MirrorHandler.serve_artifact(handler)
        handler.send_error.assert_called_once_with(404)
        self.assertEqual(self.get.call_count, 2)
        self.sleep.assert_not_called()

    def test_plugin_portal_404_is_not_sent_to_central(self):
        url = mirror.PLUGIN_PORTAL + self.path
        self.get.side_effect = HTTPError(url, 404, "missing", {}, None)
        with self.assertRaises(HTTPError):
            mirror.open_artifact(url, "GET")
        self.get.assert_called_once()
        self.sleep.assert_not_called()

    def test_head_retries_and_preserves_metadata_without_sending_a_body(self):
        response = BytesIO(b"unused")
        response.status = 200
        response.headers = {"Content-Length": "1234", "Last-Modified": "Wed, 25 Nov 2026 00:00:00 GMT"}
        self.get.side_effect = [self.error(429), response]
        handler = Mock(path="/plugins/plugin.jar", command="HEAD")
        handler.server.retry_at = {}
        mirror.MirrorHandler.serve_artifact(handler)
        for attempt in self.get.call_args_list:
            self.assertEqual(attempt.args[0].get_method(), "HEAD")
            self.assertEqual(attempt.args[0].full_url, mirror.PLUGIN_PORTAL + "/plugin.jar")
        handler.send_header.assert_any_call("Content-Length", "1234")
        handler.wfile.write.assert_not_called()

    def test_plugin_portal_redirects_to_central_use_the_mirror(self):
        redirect = mirror.MirrorRedirects()
        req = Request(mirror.PLUGIN_PORTAL + self.path, method="HEAD")
        for host in ("repo.maven.apache.org", "repo1.maven.org"):
            target = f"https://{host}/maven2{self.path}?v=1"
            redirected = redirect.redirect_request(req, None, 303, "See Other", {}, target)
            self.assertEqual(redirected.full_url, self.url + "?v=1")
            self.assertEqual(redirected.get_method(), "HEAD")
        asset = "https://plugins-artifacts.gradle.org/example.jar"
        self.assertEqual(redirect.redirect_request(req, None, 303, "", {}, asset).full_url, asset)
