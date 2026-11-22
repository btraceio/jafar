#!/usr/bin/env python3
# perf_ab_post.py — post or update the perf-ab comment on the PR a perf-ab run was for.
#
# Step 4 of doc/plans/perf-ab.md, run by .github/workflows/perf-ab-comment.yml from the default
# branch, after scripts/perf_ab_comment.py has rendered the comment.
#
#   perf_ab_post.py --comment FILE
#
# Environment (set by the workflow from the workflow_run event, never from the artifact):
#   GITHUB_API_URL, GITHUB_REPOSITORY, GH_TOKEN
#   HEAD_SHA        the perf-ab run's head commit
#   PULL_REQUESTS   the run's pull_requests array, as JSON (empty for a PR from a fork)
#
# Which PR: the one in PULL_REQUESTS, else the open PR whose head is HEAD_SHA. The artifact is
# never asked: it is the PR's own output, and a PR that could name another PR's number could
# write on it. Exactly one PR must match; none or several is an error, and nothing is posted.
#
# Which comment: the newest comment by github-actions[bot] that starts with the marker is
# edited in place; without one, a comment is created. Other comments are never touched.
#
# Exit status: 0 when the comment was created or updated; 1 when it could not be (no or several
# matching PRs, a bad comment file, an API error).

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

MARKER = "<!-- perf-ab-report -->"
BOT = "github-actions[bot]"


def die(message):
    print(f"perf_ab_post: {message}", file=sys.stderr)
    sys.exit(1)


class Api:
    def __init__(self, base, token):
        self.base = base.rstrip("/")
        self.token = token

    def call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("Accept", "application/vnd.github+json")
        request.add_header("X-GitHub-Api-Version", "2022-11-28")
        if self.token:
            request.add_header("Authorization", f"Bearer {self.token}")
        if data is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.loads(response.read() or b"null")
        except urllib.error.HTTPError as exc:
            die(f"{method} {path}: HTTP {exc.code}")
        except (urllib.error.URLError, ValueError) as exc:
            die(f"{method} {path}: {exc}")

    def pages(self, path):
        """Every item of a paginated list endpoint (per_page=100, up to 20 pages)."""
        items = []
        sep = "&" if "?" in path else "?"
        for page in range(1, 21):
            batch = self.call("GET", f"{path}{sep}per_page=100&page={page}")
            if not isinstance(batch, list):
                die(f"GET {path}: expected a list")
            items.extend(batch)
            if len(batch) < 100:
                break
        return items


def find_pr(api, repo, head_sha, pull_requests):
    numbers = []
    if isinstance(pull_requests, list):
        for pr in pull_requests:
            if isinstance(pr, dict) and isinstance(pr.get("number"), int):
                numbers.append(pr["number"])
    if not numbers:
        for pr in api.pages(f"/repos/{repo}/pulls?state=open"):
            head = pr.get("head") if isinstance(pr, dict) else None
            if isinstance(head, dict) and head.get("sha") == head_sha and isinstance(pr.get("number"), int):
                numbers.append(pr["number"])
    numbers = sorted(set(numbers))
    if len(numbers) != 1:
        die(f"expected one PR for {head_sha}, found {numbers or 'none'}: nothing posted")
    return numbers[0]


def upsert(api, repo, number, body):
    mine = [
        c for c in api.pages(f"/repos/{repo}/issues/{number}/comments")
        if isinstance(c, dict)
        and (c.get("user") or {}).get("login") == BOT
        and isinstance(c.get("body"), str)
        and c["body"].startswith(MARKER)
        and isinstance(c.get("id"), int)
    ]
    if mine:
        comment_id = max(c["id"] for c in mine)
        api.call("PATCH", f"/repos/{repo}/issues/comments/{comment_id}", {"body": body})
        return f"updated comment {comment_id} on #{number}"
    api.call("POST", f"/repos/{repo}/issues/{number}/comments", {"body": body})
    return f"created a comment on #{number}"


def main():
    parser = argparse.ArgumentParser(description="Post or update the perf-ab PR comment.")
    parser.add_argument("--comment", required=True, help="the rendered comment (perf_ab_comment.py)")
    args = parser.parse_args()

    env = os.environ
    repo = env.get("GITHUB_REPOSITORY", "")
    head_sha = env.get("HEAD_SHA", "")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        die("GITHUB_REPOSITORY is not owner/name")
    if not re.fullmatch(r"[0-9a-f]{40}", head_sha):
        die("HEAD_SHA is not a full commit id")
    try:
        pull_requests = json.loads(env.get("PULL_REQUESTS") or "[]")
    except ValueError:
        die("PULL_REQUESTS is not JSON")
    try:
        with open(args.comment, encoding="utf-8") as fh:
            body = fh.read()
    except OSError as exc:
        die(f"cannot read {args.comment}: {exc}")
    if not body.startswith(MARKER):
        die(f"{args.comment} does not start with the marker; refusing to post it")

    api = Api(env.get("GITHUB_API_URL", "https://api.github.com"), env.get("GH_TOKEN", ""))
    number = find_pr(api, repo, head_sha, pull_requests)
    print(f"perf_ab_post: {upsert(api, repo, number, body)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
