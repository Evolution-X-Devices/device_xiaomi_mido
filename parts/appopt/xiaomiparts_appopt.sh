#!/system/bin/sh
#
# Copyright (C) 2026 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#
# Runs as shell: ART only accepts compile requests from root or shell.

PROP=sys.xiaomiparts.appopt

pkg=$(getprop $PROP.pkg)
filter=$(getprop $PROP.filter)
force=$(getprop $PROP.force)

case "$pkg" in
    ""|-*|*[!A-Za-z0-9_.]*) setprop $PROP.request failed; exit 1 ;;
esac

case "$filter" in
    speed|speed-profile|verify) ;;
    *) setprop $PROP.request failed; exit 1 ;;
esac

if [ "$force" = "1" ]; then
    cmd package compile -m "$filter" -f "$pkg" > /dev/null 2>&1
else
    cmd package compile -m "$filter" "$pkg" > /dev/null 2>&1
fi

if [ $? -eq 0 ]; then
    setprop $PROP.request done
else
    setprop $PROP.request failed
fi
