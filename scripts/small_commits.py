#!/usr/bin/env python3
"""Commit selected working files in <=20 changed lines, one file per commit."""
import argparse
import difflib
import os
from pathlib import Path
import subprocess


def git(*args, data=None, check=True):
    return subprocess.run(["git", *args], input=data, capture_output=True, check=check).stdout


def head_content(path):
    result = subprocess.run(["git", "show", f"HEAD:{path}"], capture_output=True)
    return result.stdout if result.returncode == 0 else b""


def stage_and_commit(path, content, mode, part):
    blob = git("hash-object", "-w", "--stdin", data=content).decode().strip()
    git("update-index", "--add", "--cacheinfo", f"{mode},{blob},{path}")
    changed = git("diff", "--cached", "--numstat", "-z").split(b"\0")
    rows = [row.split(b"\t", 2) for row in changed if row]
    if len(rows) != 1 or rows[0][2].decode() != path:
        raise RuntimeError("Refusing commit: staged changes must contain exactly one selected file")
    added, removed = rows[0][:2]
    # Git reports binary artifacts as '-' because they have no text line count.
    lines = 0 if added == b"-" else int(added) + int(removed)
    if lines > 20:
        raise RuntimeError(f"Refusing commit with {lines} changed lines")
    git("commit", "-m", f"feat: implement {path} (part {part})")
    sha = git("rev-parse", "--short", "HEAD").decode().strip()
    print(f"{sha} {path}: {lines} text lines", flush=True)


def commit_file(path):
    file = Path(path)
    if not file.is_file() or file.is_symlink():
        raise ValueError(f"Expected a regular file: {path}")
    target = file.read_bytes()
    current = head_content(path)
    mode = "100755" if os.access(file, os.X_OK) else "100644"
    if b"\0" in target or b"\0" in current:
        if current != target:
            stage_and_commit(path, target, mode, 1)
        return
    before, after = current.splitlines(keepends=True), target.splitlines(keepends=True)
    part = 0
    while before != after:
        for operation, start, end, new_start, new_end in difflib.SequenceMatcher(
            None, before, after, autojunk=False,
        ).get_opcodes():
            if operation == "equal":
                continue
            if operation == "delete":
                del before[start:min(end, start + 20)]
            elif operation == "insert":
                before[start:start] = after[new_start:min(new_end, new_start + 20)]
            else:
                before[start:min(end, start + 10)] = after[new_start:min(new_end, new_start + 10)]
            part += 1
            stage_and_commit(path, b"".join(before), mode, part)
            break
    staged_mode = git("ls-files", "--stage", "--", path).split(b" ", 1)[0].decode()
    if staged_mode and staged_mode != mode:
        stage_and_commit(path, target, mode, part + 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+")
    parser.add_argument("--push", action="store_true")
    args = parser.parse_args()
    root = git("rev-parse", "--show-toplevel").decode().strip()
    os.chdir(root)
    if git("branch", "--show-current").decode().strip() != "main":
        raise RuntimeError("This workflow requires main")
    if git("diff", "--cached", "--name-only").strip():
        raise RuntimeError("Commit or unstage existing staged changes first")
    for path in args.paths:
