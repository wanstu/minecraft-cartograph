#!/usr/bin/env python3
"""Compatibility entrypoint.

The HTML prototype is the single source of truth for Cartograph V3 icons.
Running this legacy command now delegates to sync_icons_from_html.py so the
game can never silently drift back to the old simplified icon set.
"""
from sync_icons_from_html import main

if __name__ == "__main__":
    main()
