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
