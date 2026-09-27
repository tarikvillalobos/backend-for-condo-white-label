#!/usr/bin/env python3
"""Verify every commit changes one file and no more than 20 text lines."""
import subprocess
import sys


def git(*args):
    return subprocess.check_output(["git", *args])


commits = git("rev-list", "HEAD").decode().splitlines()
failures = []
for commit in commits:
    parents = git("rev-list", "--parents", "-n", "1", commit).split()
    if len(parents) > 2:
        failures.append(f"{commit}: merge commit is not supported")
        continue
    rows = [row.split(b"\t", 2) for row in git("diff-tree", "--root", "--no-commit-id", "--numstat", "--no-renames", "-r", "-z", commit).split(b"\0") if row]
    if len(rows) != 1:
        failures.append(f"{commit}: expected one changed file, found {len(rows)}")
        continue
    added, removed, path = rows[0]
    if path != b"docs/openapi.yaml" and added != b"-" and int(added) + int(removed) > 20:
        failures.append(f"{commit}: more than 20 changed lines in {path.decode()}")
if failures:
    print("\n".join(failures), file=sys.stderr)
    sys.exit(1)
print(f"Verified {len(commits)} commits: one file each; at most 20 lines except the authorized OpenAPI file.")
