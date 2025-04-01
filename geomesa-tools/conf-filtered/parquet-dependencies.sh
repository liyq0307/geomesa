#! /usr/bin/env bash
#
# Copyright (c) 2013-%%copyright.year%% Commonwealth Computer Research, Inc.
# All rights reserved. This program and the accompanying materials
# are made available under the terms of the Apache License, Version 2.0 which
# accompanies this distribution and is available at
# http://www.opensource.org/licenses/apache2.0.php.
#

# This file lists the dependencies required for using geomesa-convert-parquet.
# Update the versions as required to match the target environment.

hadoop_install_version="%%hadoop.version.recommended%%"

# gets the dependencies for this module
# args:
#   $1 - current classpath
function dependencies() {
  local classpath="$1"

  local hadoop_version="$hadoop_install_version"

  if [[ -n "$classpath" ]]; then
    hadoop_version="$(get_classpath_version hadoop-common "$classpath" "$hadoop_version")"
    hadoop_version="$(get_classpath_version hadoop-client-api "$classpath" "$hadoop_version")"
  fi

  if [[ "$hadoop_version" == "3.2.3" ]]; then
    echo >&2 "WARNING Updating Hadoop version from 3.2.3 to 3.2.4 due to invalid client-api Maven artifacts"
    hadoop_version="3.2.4"
  fi

  declare -a gavs=(
    "org.apache.hadoop:hadoop-client-api:${hadoop_version}:jar"
    "org.apache.hadoop:hadoop-client-runtime:${hadoop_version}:jar"
  )

  echo "${gavs[@]}" | tr ' ' '\n' | sort | tr '\n' ' '
}

function exclude_dependencies() {
  # local classpath="$1"
  echo ""
}
