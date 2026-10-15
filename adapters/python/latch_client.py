# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
"""A small Python client for a Latch gateway, for scripted QA and tests (roadmap R15).

It speaks MCP over Streamable HTTP with the standard library only, so it runs
anywhere Python 3.9+ does. It calls the same tools any AI client calls, under
the same rules: the phone still asks its owner before consequential steps,
refuses password fields, and stops when the owner taps Stop. It is not a way
around any of that.

    from latch_client import Latch
    latch = Latch("http://127.0.0.1:8787", token)
    screen = latch.call("observe", screenshot=False)
    settings = latch.call("launch_app", package="com.android.settings")
    latch.call("tap", observation_id=settings.observation_id,
               element_id=settings.element("Network & internet"))

Several phones: pass device_id to each call (see list_devices), or make one
`Latch(..., device_id=...)` per phone; each instance has its own MCP session
and is safe to use from its own thread.
"""

from __future__ import annotations

import itertools
import json
import re
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Optional

PROTOCOL_VERSION = "2025-06-18"
__version__ = "0.1.0"


class LatchError(Exception):
    """The gateway refused the request itself (bad token, bad JSON-RPC, unknown tool)."""


@dataclass
class Result:
    """A tool's answer: its text, whether it is an error, and any screenshots (base64)."""

    text: str
    is_error: bool
    images: list = field(default_factory=list)

    @property
    def observation_id(self) -> Optional[str]:
        """The newest observation id in the answer, to cite in the next action."""
        found = re.findall(r"observation_id: (\S+)", self.text)
        return found[-1] if found else None

    def element(self, label: str) -> str:
        """The id of the last element whose text or description is exactly `label`."""
        for line in reversed(self.text.splitlines()):
            if f'"{label}"' in line or f'desc="{label}"' in line:
                match = re.search(r"\[([^\]\s]+)\]", line)
                if match:
                    return match.group(1)
        raise KeyError(f"{label!r} is not on the screen")

    def __str__(self) -> str:
        return self.text


class Latch:
    """One MCP session with a Latch gateway."""

    def __init__(self, url: str, token: str, device_id: Optional[str] = None, timeout: float = 120.0):
        self.endpoint = url.rstrip("/") + ("" if url.rstrip("/").endswith("/mcp") else "/mcp")
        self.token = token
        self.device_id = device_id
        self.timeout = timeout
        self.session_id: Optional[str] = None
        self.server: dict = {}
        self._ids = itertools.count(1)
        self._initialize()

    # ---- MCP ----

    def _post(self, message: dict) -> Optional[dict]:
        headers = {
            "Authorization": f"Bearer {self.token}",
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "MCP-Protocol-Version": PROTOCOL_VERSION,
        }
        if self.session_id:
            headers["Mcp-Session-Id"] = self.session_id
        request = urllib.request.Request(self.endpoint, json.dumps(message).encode(), headers, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                self.session_id = response.headers.get("Mcp-Session-Id") or self.session_id
                body = response.read().decode()
                kind = response.headers.get("Content-Type", "")
        except urllib.error.HTTPError as e:
            raise LatchError(f"HTTP {e.code}: {e.read().decode(errors='replace')[:300]}") from None
        if "id" not in message or not body.strip():
            return None
        if kind.startswith("text/event-stream"):
            # The answer is the `data:` event that carries our id.
            for chunk in body.split("\n\n"):
                data = "\n".join(line[5:].lstrip() for line in chunk.splitlines() if line.startswith("data:"))
                if data:
                    parsed = json.loads(data)
                    if parsed.get("id") == message["id"]:
                        return parsed
            raise LatchError("the gateway's event stream held no answer")
        return json.loads(body)

    def _rpc(self, method: str, params: Optional[dict] = None) -> Any:
        answer = self._post({"jsonrpc": "2.0", "id": next(self._ids), "method": method, "params": params or {}})
        if answer is None:
            raise LatchError("no answer")
        if "error" in answer:
            raise LatchError(f"{answer['error'].get('code')}: {answer['error'].get('message')}")
        return answer.get("result")

    def _initialize(self) -> None:
        self.server = self._rpc("initialize", {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {},
            "clientInfo": {"name": "latch-python", "version": __version__},
        })
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})

    # ---- Tools ----

    def tools(self) -> list:
        """The tool names the gateway offers."""
        return [t["name"] for t in self._rpc("tools/list")["tools"]]

    def call(self, name: str, **arguments: Any) -> Result:
        """Calls one tool. A refusal or a phone error comes back as `Result.is_error`, not an exception."""
        if self.device_id and "device_id" not in arguments and name not in ("list_devices", "diagnose", "list_skills"):
            arguments["device_id"] = self.device_id
        result = self._rpc("tools/call", {"name": name, "arguments": arguments})
        content = result.get("content", [])
        return Result(
            text="\n".join(c.get("text", "") for c in content if c.get("type") == "text"),
            is_error=bool(result.get("isError")),
            images=[c.get("data") for c in content if c.get("type") == "image"],
        )

    def device_ids(self, connected_only: bool = True) -> list:
        """Ids of paired phones, from list_devices; only connected ones by default."""
        ids = []
        for line in self.call("list_devices").text.splitlines():
            match = re.match(r"- (d_\S+)", line.strip())
            if match and (not connected_only or "connected" in line and "not connected" not in line):
                ids.append(match.group(1))
        return ids
