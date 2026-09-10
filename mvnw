#!/bin/sh
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#    https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# SPDX-License-Identifier: Apache-2.0

if [ -z "$JAVA_HOME" ] ; then
  JAVA_HOME=$(dirname "$(dirname "$(command -v javac)")")
fi
export JAVA_HOME

if [ ! -x "$JAVA_HOME/bin/java" ] ; then
  echo "The JAVA_HOME environment variable is not defined correctly" >&2
  echo "This environment variable is needed to run this program" >&2
  exit 1
fi

CLASSWORLDS_JAR="$( cd "$( dirname "$0" )/.mvn/wrapper" && pwd )/maven-wrapper.jar"
if [ ! -r "$CLASSWORLDS_JAR" ] ; then
    echo "maven-wrapper.jar not found in .mvn/wrapper, please run the 'mvn wrapper:wrapper' goal" >&2
    exit 1
fi

exec "$JAVA_HOME/bin/java" \
  -cp "$CLASSWORLDS_JAR" \
  "-Dmaven.multiModuleProjectDirectory=$(pwd)" \
  org.apache.maven.wrapper.MavenWrapperMain "$@"
