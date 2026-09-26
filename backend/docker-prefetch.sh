#!/bin/sh
# Prefetch Maven dependencies so the dependency layer is cached independently of source changes.
#
# This lives in a script rather than inline in the Dockerfile because the Windows Docker client
# re-parses RUN arguments: a "plugin:goal" argument containing a colon gets split on the colon and
# corrupts the build cache mount. A script file is copied verbatim and executed inside the image.
set -e
mvn -q -B org.apache.maven.plugins:maven-dependency-plugin:3.6.1:go-offline
