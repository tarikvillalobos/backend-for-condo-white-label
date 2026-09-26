#!/usr/bin/env python3
"""Exercise the packaged server with an isolated, temporary persistent database."""
import datetime
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


root = Path(__file__).resolve().parents[1]
command = root / "build/install/community-api/bin/community-api"
if not command.is_file():
    raise SystemExit("Run ./gradlew installDist first")
