#!/bin/sh
# Askpass helper for credential profiles using the askpass channel.
#
# OpenSSH and Git pass the prompt as $1. The selected credential profile, not the prompt text,
# determines which secret this helper returns: the worker exports TORVIAN_ASKPASS_FILE for the one
# attach that needs it, and the path is readable only by the credential account.
#
# The script never contains a secret. A missing TORVIAN_ASKPASS_FILE is a hard failure instead of an
# empty answer, so a profile with an incomplete environment fails visibly.
case "$1" in
    *[Uu]sername*) printf '%s\n' "${TORVIAN_ASKPASS_USERNAME:-git}" ;;
    *) exec /bin/cat "${TORVIAN_ASKPASS_FILE:?TORVIAN_ASKPASS_FILE is not set}" ;;
esac
