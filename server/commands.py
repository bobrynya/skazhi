"""Voice command config (commands.json) and matching a transcript to an action.

A command is ``{"phrases": [...], <action>: <arg>}`` with exactly one action:

- ``launch``: package name. Phrases are app names; each of ``launch_verbs``
  ("запусти", "открой", ...) is prepended automatically in the grammar.
- ``volume``: ``up``, ``down`` or ``mute``.
- ``key``: media key name, e.g. ``KEYCODE_MEDIA_PAUSE``.
- ``global``: ``home``, ``back`` or ``lock``.

The optional ``search`` block turns "<prefix> <anything>" into a search intent
for ``search.package`` with the rest of the phrase as the query.
"""

import json
import logging
import re
import threading
from pathlib import Path
from typing import Any, NamedTuple

log = logging.getLogger("skazhi")

ACTIONS = ("launch", "volume", "key", "global")
# Allowed arguments per action; None means any non-empty string.
ARGS: dict[str, frozenset[str] | None] = {
    "launch": None,
    "volume": frozenset({"up", "down", "mute"}),
    "key": None,
    "global": frozenset({"home", "back", "lock"}),
}


class Hit(NamedTuple):
    """A matched command."""

    action: str
    """``launch``, ``volume``, ``key``, ``global`` or ``search``."""
    arg: str
    """Package name, volume direction, key name or global action."""
    query: str | None = None
    """Search query for ``search``, otherwise None."""


def norm(s: str | None) -> str:
    """Lowercase, fold ё to е, drop punctuation, collapse spaces; None gives ""."""
    if not s:
        return ""
    s = re.sub(r"[^а-я0-9a-z ]", " ", s.lower().replace("ё", "е"))
    return " ".join(s.split())


class Commands:
    """A parsed and validated commands.json."""

    def __init__(self, text: str) -> None:
        """Parse a config.

        Args:
            text: Contents of commands.json.

        Raises:
            ValueError: The JSON is malformed or a command is invalid.
        """
        root: dict[str, Any] = json.loads(text)
        self.trigger_scan_code: int = int(root.get("trigger_scan_code", 63))
        verbs: list[str] = root.get("launch_verbs", [])
        search = root.get("search")
        self._search_package: str | None = search["package"] if search else None
        self._search_prefixes = [norm(p) for p in search["prefixes"]] if search else []

        self._phrases: list[tuple[str, Hit]] = []  # (normalized phrase, hit), longest first
        grammar: list[str] = []
        for c in root.get("commands", []):
            actions = [a for a in ACTIONS if a in c]
            if len(actions) != 1:
                raise ValueError(f"command needs exactly one action of {ACTIONS}: {c}")
            action = actions[0]
            arg = c[action]
            allowed = ARGS[action]
            if not isinstance(arg, str) or not arg or (allowed is not None and arg not in allowed):
                raise ValueError(f"bad {action} argument {arg!r}, allowed: {sorted(allowed or ['<any>'])}")
            for raw in (p.lower() for p in c["phrases"]):
                # The grammar keeps the raw spelling: the model vocabulary has "вперёд", not "вперед".
                self._phrases.append((norm(raw), Hit(action, arg)))
                grammar.append(raw)
                if action == "launch":
                    grammar.extend(f"{v} {raw}" for v in verbs)
        # Longest first so "выключи звук" wins over a shorter phrase it contains.
        self._phrases.sort(key=lambda p: -len(p[0]))
        # [unk] lets out-of-grammar speech stay unknown instead of snapping to a command.
        self.grammar: str = json.dumps([*grammar, "[unk]"], ensure_ascii=False)
        """Vosk grammar: a JSON array of every phrase."""

    def match(self, text: str, free: str) -> Hit | None:
        """Find the command for an utterance.

        Search wins if the free transcript starts with a search prefix: a movie
        title can't be in the grammar. Otherwise the grammar transcript is
        matched, falling back to the free one: an exact phrase first, then the
        longest phrase contained as whole words.

        Args:
            text: Grammar-restricted transcript.
            free: Unrestricted transcript.

        Returns:
            The matched command, or None.
        """
        f = norm(free)
        if self._search_package:
            for p in self._search_prefixes:
                if f.startswith(p + " "):
                    return Hit("search", self._search_package, f[len(p) + 1 :])
        return self._match_phrase(norm(text)) or self._match_phrase(f)

    def _match_phrase(self, t: str) -> Hit | None:
        if not t:
            return None
        for phrase, hit in self._phrases:
            if t == phrase:
                return hit
        padded = f" {t} "
        for phrase, hit in self._phrases:
            if f" {phrase} " in padded:
                return hit
        return None


class CommandsFile:
    """commands.json, reloaded when its mtime changes.

    A broken edit is logged and the last good config stays in use, so a typo
    never takes voice control down.
    """

    def __init__(self, path: Path) -> None:
        """Load the file.

        Raises:
            ValueError: The file is invalid: fail loudly at startup.
        """
        self._path = path
        self._lock = threading.Lock()
        self._mtime = path.stat().st_mtime
        self._commands = Commands(path.read_text(encoding="utf-8"))

    def get(self) -> Commands:
        """Return the current config, reloading it if the file changed."""
        with self._lock:
            try:
                mtime = self._path.stat().st_mtime
                if mtime != self._mtime:
                    self._commands = Commands(self._path.read_text(encoding="utf-8"))
                    # Only after a successful parse: a half-saved file read in the same
                    # mtime tick as the finished one must not block the reload.
                    self._mtime = mtime
                    log.info("reloaded %s", self._path)
            except (OSError, ValueError) as e:
                log.warning("keeping previous commands, %s is broken: %s", self._path, e)
            return self._commands
