#!/usr/bin/env sh

#
# Copyright 2015 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# #############################################################################
#
#  Gradle startup script.
#
# #############################################################################

# Attempt to set APP_HOME
# Resolve links: $0 may be a link
PRG="$0"
# Need this for relative symlinks.
while [ -h "$PRG" ] ; do
    ls=`ls -ld "$PRG"`
    link=`expr "$ls" : '.*-> \(.*\)$'`
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=`dirname "$PRG"`"/$link"
    fi
done
SAVED="`pwd`"
cd "`dirname \"$PRG\"`" >/dev/null
APP_HOME="`pwd -P`"
cd "$SAVED" >/dev/null

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

# Use the maximum available, or set MAX_FD != -1 to use that value.
MAX_FD="maximum"

# OS specific support.  $var _must_ be set to either true or false.
cygwin=false
msys=false
darwin=false
nonstop=false
case "`uname`" in
  CYGWIN* )
    cygwin=true
    ;;
  Darwin* )
    darwin=true
    ;;
  MINGW* )
    msys=true
    ;;
  NONSTOP* )
    nonstop=true
    ;;
esac

# For Cygwin, ensure paths are in UNIX format before anything is touched
if $cygwin ; then
    [ -n "$APP_HOME" ] &&
        APP_HOME=`cygpath --unix "$APP_HOME"`
    [ -n "$JAVA_HOME" ] &&
        JAVA_HOME=`cygpath --unix "$JAVA_HOME"`
    [ -n "$CLASSPATH" ] &&
        CLASSPATH=`cygpath --path --unix "$CLASSPATH"`
fi

# Attempt to find java
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange locations for the executables
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        die "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
    fi
else
    JAVACMD="java"
    which java >/dev/null 2>&1 || die "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.

Please set the JAVA_HOME variable in your environment to match the
location of your Java installation."
fi

# Set MIBS values, if not set
if [ -z "$DEFAULT_JVM_OPTS" ] ; then
    DEFAULT_JVM_OPTS='"-Xms20m" "-Xmx40m"'
fi

# Set up the commands
if $cygwin ; then
    CMD_LINE_ARGS=""
    for arg in "$@"
    do
        CMD_LINE_ARGS="$CMD_LINE_ARGS \"$arg\""
    done
else
    CMD_LINE_ARGS="$@"
fi

# Split up the JVM options string into an array, following the shell quoting and substitution rules
function splitJvmOpts() {
    JVM_OPTS=()
    for o in "$@"
    do
        # Don't use eval to solve the quoting problem, it's not trustworthy
        # Just do a simple parsing on a few chosen quotes
        if [[ $o == *"\""* ]]; then
            o="${o//\"/}"
        fi
        if [[ $o == *"'"* ]]; then
            o="${o//\'/}"
        fi
        JVM_OPTS+=("$o")
    done
}

# Collect all arguments for the java command, following the shell quoting and substitution rules
function collectCommandLineArguments() {
    splitJvmOpts $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS
    # Add the jar to the classpath
    CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
    # The directory of the script
    # Build the command line
    COMMAND_LINE=("$JAVACMD")
    COMMAND_LINE+=("${JVM_OPTS[@]}")
    COMMAND_LINE+=("-Dorg.gradle.appname=$APP_BASE_NAME")
    COMMAND_LINE+=("-classpath" "$CLASSPATH")
    COMMAND_LINE+=("org.gradle.wrapper.GradleWrapperMain")
    COMMAND_LINE+=($CMD_LINE_ARGS)
}

# Execute the command line
function launch() {
    # Turn on echo if in debug mode
    if [ ! -z "$DEBUG" ]; then
        echo
        echo "launching with:"
        for arg in "${COMMAND_LINE[@]}"
        do
            echo "$arg"
        done
        echo
    fi
    # Launch the command line
    exec "${COMMAND_LINE[@]}"
}

# Parse the command line
collectCommandLineArguments
# Launch the command line
launch

die() {
    echo "$*"
    exit 1
}
exit 0 