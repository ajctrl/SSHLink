"""Release a verified Dependabot Gradle PR using only GitHub's REST API.

Run from the default branch, never from the privileged workflow's PR checkout.
GITHUB_TOKEN cannot trigger push workflows, so explicitly dispatch Build APK.
"""

import base64
import json
import os
import re
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


ALLOWED_FILES = {"app/build.gradle.kts", "build.gradle.kts"}
REQUIRED_JOBS = {"Build, test and lint", "Independent reproducibility build"}
VERSION = re.compile(r"v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)")


class GitHub:
    def __init__(self, repository, token):
        self.base = f"https://api.github.com/repos/{repository}"
        self.token = token

    def request(self, method, path, data=None):
        request = Request(
            self.base + path,
            data=None if data is None else json.dumps(data).encode(),
            method=method,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
                "Content-Type": "application/json",
            },
        )
        with urlopen(request, timeout=60) as response:
            body = response.read()
        return json.loads(body) if body else None

    def items(self, path, key=None):
        page = 1
        while True:
            separator = "&" if "?" in path else "?"
            result = self.request("GET", f"{path}{separator}per_page=100&page={page}")
            batch = result[key] if key else result
            yield from batch
            if len(batch) < 100:
                return
            page += 1


def version_tuple(tag):
    match = VERSION.fullmatch(tag)
    return tuple(map(int, match.groups())) if match else None


def next_tag(tags, default_version):
    versions = [version_tuple("v" + default_version)]
    if versions[0] is None:
        raise ValueError("Invalid defaultVersionName")
    versions.extend(v for tag in tags if (v := version_tuple(tag["name"])) is not None)
    major, minor, patch = max(versions)
    if minor >= 1000 or patch >= 1000:
        raise ValueError("Release versions must have minor and patch below 1000")
    patch += 1
    if patch == 1000:
        minor, patch = minor + 1, 0
    if minor == 1000:
        major, minor = major + 1, 0
    if major * 1_000_000 + minor * 1000 + patch > 2_147_483_647:
        raise ValueError("Android versionCode would overflow")
    return f"v{major}.{minor}.{patch}"


def release(api, repository, run_id, signing_ready):
    if not str(run_id).isdigit():
        raise ValueError("BUILD_RUN_ID must be a numeric Build APK run ID")
    run = api.request("GET", f"/actions/runs/{run_id}")
    workflow = api.request("GET", "/actions/workflows/build.yml")
    if not (
        run["workflow_id"] == workflow["id"]
        and run["event"] == "pull_request"
        and run["status"] == "completed"
        and run["conclusion"] == "success"
        and run["head_repository"]["full_name"] == repository
    ):
        raise ValueError("Expected a successful Build APK pull request run in this repository")

    repo = api.request("GET", "")
    # workflow_run.pull_requests can be empty; resolve through the tested head SHA.
    candidates = list(api.items(f"/commits/{run['head_sha']}/pulls"))
    eligible = []
    for candidate in candidates:
        pr = api.request("GET", f"/pulls/{candidate['number']}")
        if (
            pr["user"]["login"] == "dependabot[bot]"
            and pr["user"]["type"] == "Bot"
            and pr["head"]["repo"] is not None
            and pr["head"]["repo"]["full_name"] == repository
            and pr["head"]["sha"] == run["head_sha"]
            and pr["base"]["repo"]["full_name"] == repository
            and pr["base"]["ref"] == repo["default_branch"]
            and not pr["draft"]
            and (pr["state"] == "open" or pr["merged"])
        ):
            eligible.append(pr)
    if not eligible:
        print("No current Dependabot PR for this run; nothing to release.")
        return
    if len(eligible) != 1:
        raise ValueError("Expected exactly one Dependabot PR for the tested commit")
    pr = eligible[0]
    number = pr["number"]
    files = list(api.items(f"/pulls/{number}/files"))
    if not files or any(
        f["filename"] not in ALLOWED_FILES or f["status"] != "modified" for f in files
    ):
        raise ValueError("Automatic releases only accept existing Gradle dependency files")
    jobs = list(api.items(f"/actions/runs/{run_id}/jobs?filter=latest", key="jobs"))
    passed = {job["name"] for job in jobs if job["conclusion"] == "success"}
    if not REQUIRED_JOBS <= passed:
        raise ValueError("Tests, lint and both reproducibility builds must succeed")
    if not signing_ready:
        raise ValueError("Configure all four SIGNING_* secrets before automatically merging")

    if pr["merged"]:
        merged_sha = pr["merge_commit_sha"]
    else:
        methods = {
            "squash": "allow_squash_merge",
            "merge": "allow_merge_commit",
            "rebase": "allow_rebase_merge",
        }
        method = next((m for m, setting in methods.items() if repo.get(setting)), None)
        if method is None:
            raise ValueError("Repository has no enabled merge method")
        merge_request = {
            "sha": run["head_sha"],
            "merge_method": method,
        }
        if method != "rebase":
            merge_request["commit_title"] = f"build(deps): update Gradle dependencies (#{number})"
        merged = api.request("PUT", f"/pulls/{number}/merge", merge_request)
        if not merged["merged"]:
            raise RuntimeError("GitHub refused to merge the dependency PR")
        merged_sha = merged["sha"]

    content = api.request(
        "GET", "/contents/app/build.gradle.kts?" + urlencode({"ref": merged_sha})
    )
    gradle = base64.b64decode(content["content"]).decode("utf-8")
    default = re.search(r'^val defaultVersionName = "([^"]+)"$', gradle, re.MULTILINE)
    if default is None:
        raise ValueError("Cannot read defaultVersionName from the merged commit")

    # Reuse an existing tag on retries, including a retry after dispatch failure.
    # Atomic ref creation also protects against a concurrent manually created tag.
    for attempt in range(5):
        tags = list(api.items("/tags"))
        existing = [
            t["name"] for t in tags
            if t["commit"]["sha"] == merged_sha and version_tuple(t["name"]) is not None
        ]
        if existing:
            tag = max(existing, key=version_tuple)
            break
        tag = next_tag(tags, default[1])
        try:
            api.request("POST", "/git/refs", {"ref": f"refs/tags/{tag}", "sha": merged_sha})
            break
        except HTTPError as error:
            if error.code != 422 or attempt == 4:
                raise
            error.close()
    # A GITHUB_TOKEN tag push does not start the release workflow by itself.
    api.request("POST", "/actions/workflows/build.yml/dispatches", {"ref": tag})
    print(f"Dependabot PR #{number}: dispatched Build APK for {tag} ({merged_sha}).")


if __name__ == "__main__":
    release(
        GitHub(os.environ["GITHUB_REPOSITORY"], os.environ["GH_TOKEN"]),
        os.environ["GITHUB_REPOSITORY"],
        os.environ["BUILD_RUN_ID"],
        os.environ.get("SIGNING_READY") == "true",
    )
