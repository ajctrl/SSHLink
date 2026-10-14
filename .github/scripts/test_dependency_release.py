import base64
from copy import deepcopy
from io import BytesIO
import unittest
from urllib.error import HTTPError

from dependency_release import GitHub, next_tag, release


REPOSITORY = "ajctrl/SSHLink"


class FakeGitHub:
    def __init__(self):
        self.run = {
            "workflow_id": 1, "event": "pull_request", "status": "completed",
            "conclusion": "success", "head_repository": {"full_name": REPOSITORY},
            "head_sha": "tested",
        }
        self.repo = {"default_branch": "main", "allow_squash_merge": True}
        self.pr = {
            "number": 42, "user": {"login": "dependabot[bot]", "type": "Bot"},
            "head": {"repo": {"full_name": REPOSITORY}, "sha": "tested"},
            "base": {"repo": {"full_name": REPOSITORY}, "ref": "main"},
            "draft": False, "state": "open", "merged": False,
            "merge_commit_sha": None,
        }
        self.files = [{"filename": "app/build.gradle.kts", "status": "modified"}]
        self.jobs = [
            {"name": "Build, test and lint", "conclusion": "success"},
            {"name": "Independent reproducibility build", "conclusion": "success"},
        ]
        self.tags = [{"name": "v0.3.10", "commit": {"sha": "old"}}]
        self.writes = []
        self.merge_allowed = True
        self.tag_collision = False
        self.dispatch_failure = False

    def request(self, method, path, data=None):
        if method == "GET":
            if path == "/actions/runs/7":
                return deepcopy(self.run)
            if path == "/actions/workflows/build.yml":
                return {"id": 1}
            if path == "":
                return deepcopy(self.repo)
            if path == "/pulls/42":
                return deepcopy(self.pr)
            if path == "/contents/app/build.gradle.kts?ref=merged":
                return {"content": base64.b64encode(
                    b'val defaultVersionName = "0.3.11"\n'
                ).decode()}
        self.writes.append((method, path, data))
        if method == "PUT" and path == "/pulls/42/merge":
            if not self.merge_allowed:
                raise HTTPError(path, 405, "Branch protection", {}, BytesIO())
            self.pr.update(merged=True, state="closed", merge_commit_sha="merged")
            return {"merged": True, "sha": "merged"}
        if method == "POST" and path == "/git/refs":
            tag = data["ref"].removeprefix("refs/tags/")
            if self.tag_collision:
                self.tag_collision = False
                self.tags.append({"name": tag, "commit": {"sha": "another"}})
                raise HTTPError(path, 422, "Already exists", {}, BytesIO())
            self.tags.append({"name": tag, "commit": {"sha": data["sha"]}})
            return {}
        if method == "POST" and path == "/actions/workflows/build.yml/dispatches":
            if self.dispatch_failure:
                raise HTTPError(path, 503, "Unavailable", {}, BytesIO())
            return None
        raise AssertionError((method, path, data))

    def items(self, path, key=None):
        return deepcopy({
            "/commits/tested/pulls": [{"number": 42}],
            "/pulls/42/files": self.files,
            "/actions/runs/7/jobs?filter=latest": self.jobs,
            "/tags": self.tags,
        }[path])


class DependencyReleaseTest(unittest.TestCase):
    def test_success_tags_exact_merge_and_dispatches(self):
        api = FakeGitHub()
        release(api, REPOSITORY, "7", True)
        self.assertEqual(api.writes, [
            ("PUT", "/pulls/42/merge", {
                "sha": "tested", "merge_method": "squash",
                "commit_title": "build(deps): update Gradle dependencies (#42)",
            }),
            ("POST", "/git/refs", {"ref": "refs/tags/v0.3.12", "sha": "merged"}),
            ("POST", "/actions/workflows/build.yml/dispatches", {"ref": "v0.3.12"}),
        ])

    def test_untrusted_or_unsuccessful_runs_cannot_write(self):
        cases = [
            {"workflow_id": 2}, {"event": "push"}, {"status": "in_progress"},
            {"conclusion": "failure"}, {"conclusion": "cancelled"},
            {"head_repository": {"full_name": "fork/SSHLink"}},
        ]
        for fields in cases:
            with self.subTest(fields=fields):
                api = FakeGitHub()
                api.run.update(fields)
                with self.assertRaises(ValueError):
                    release(api, REPOSITORY, 7, True)
                self.assertEqual(api.writes, [])

    def test_stale_human_fork_or_closed_prs_cannot_write(self):
        cases = [
            {"user": {"login": "human", "type": "User"}},
            {"head": {"repo": {"full_name": REPOSITORY}, "sha": "newer"}},
            {"head": {"repo": {"full_name": "fork/SSHLink"}, "sha": "tested"}},
            {"head": {"repo": None, "sha": "tested"}},
            {"base": {"repo": {"full_name": REPOSITORY}, "ref": "other"}},
            {"draft": True}, {"state": "closed"},
        ]
        for fields in cases:
            with self.subTest(fields=fields):
                api = FakeGitHub()
                api.pr.update(fields)
                release(api, REPOSITORY, 7, True)
                self.assertEqual(api.writes, [])

    def test_non_dependency_or_renamed_files_cannot_write(self):
        for filename, status in [
            (".github/workflows/build.yml", "modified"),
            ("app/src/main/Injected.kt", "modified"),
            ("app/build.gradle.kts", "renamed"),
        ]:
            with self.subTest(filename=filename, status=status):
                api = FakeGitHub()
                api.files.append({"filename": filename, "status": status})
                with self.assertRaises(ValueError):
                    release(api, REPOSITORY, 7, True)
                self.assertEqual(api.writes, [])

    def test_skipped_build_or_missing_signing_secrets_prevents_merge(self):
        for missing in ("job", "secrets"):
            with self.subTest(missing=missing):
                api = FakeGitHub()
                if missing == "job":
                    api.jobs[-1]["conclusion"] = "skipped"
                with self.assertRaises(ValueError):
                    release(api, REPOSITORY, 7, missing != "secrets")
                self.assertEqual(api.writes, [])

    def test_merge_refusal_does_not_tag_or_dispatch(self):
        api = FakeGitHub()
        api.merge_allowed = False
        with self.assertRaises(HTTPError) as raised:
            release(api, REPOSITORY, 7, True)
        raised.exception.close()
        self.assertEqual(len(api.writes), 1)

    def test_retry_after_dispatch_failure_reuses_merge_and_tag(self):
        api = FakeGitHub()
        api.dispatch_failure = True
        with self.assertRaises(HTTPError) as raised:
            release(api, REPOSITORY, 7, True)
        raised.exception.close()
        api.writes.clear()
        api.dispatch_failure = False
        release(api, REPOSITORY, 7, True)
        self.assertEqual(api.writes, [
            ("POST", "/actions/workflows/build.yml/dispatches", {"ref": "v0.3.12"}),
        ])

    def test_retry_after_merge_before_tag(self):
        api = FakeGitHub()
        api.pr.update(merged=True, state="closed", merge_commit_sha="merged")
        release(api, REPOSITORY, 7, True)
        self.assertEqual([w[1] for w in api.writes], [
            "/git/refs", "/actions/workflows/build.yml/dispatches",
        ])

    def test_tag_collision_allocates_another_version(self):
        api = FakeGitHub()
        api.tag_collision = True
        release(api, REPOSITORY, 7, True)
        self.assertEqual(api.writes[-1][2], {"ref": "v0.3.13"})

    def test_disabled_squash_uses_enabled_merge_method(self):
        for method, setting in (("merge", "allow_merge_commit"), ("rebase", "allow_rebase_merge")):
            with self.subTest(method=method):
                api = FakeGitHub()
                api.repo.update(allow_squash_merge=False)
                api.repo[setting] = True
                release(api, REPOSITORY, 7, True)
                self.assertEqual(api.writes[0][2]["merge_method"], method)


class VersionTest(unittest.TestCase):
    def test_uses_highest_stable_tag_or_default(self):
        tags = [{"name": name} for name in ("v0.3.9", "v0.3.20", "v2.0.0-rc1", "other")]
        self.assertEqual(next_tag(tags, "0.3.11"), "v0.3.21")
        self.assertEqual(next_tag(tags, "1.0.0"), "v1.0.1")

    def test_version_code_carry_and_overflow(self):
        self.assertEqual(next_tag([], "0.3.999"), "v0.4.0")
        self.assertEqual(next_tag([], "0.999.999"), "v1.0.0")
        for version in ("invalid", "0.3.1000", "2147.483.647"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                next_tag([], version)

    def test_paginates_all_tags(self):
        api = GitHub(REPOSITORY, "unused")
        paths = []

        def request(method, path):
            paths.append(path)
            return list(range(100)) if path.endswith("&page=1") else [100]

        api.request = request
        self.assertEqual(list(api.items("/tags")), list(range(101)))
        self.assertEqual(paths, ["/tags?per_page=100&page=1", "/tags?per_page=100&page=2"])


if __name__ == "__main__":
    unittest.main()
