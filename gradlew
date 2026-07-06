#!/bin/sh

# Setup gradle files locally if not present in the project to let Android Studio / CI build it.
# This is a stub gradlew that triggers gradle build.
exec gradle "$@"
