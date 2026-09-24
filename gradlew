#!/bin/sh
APP_BASE_NAME=${0##*/}
APP_HOME=$(cd "${APP_HOME:-./}" && pwd -P) || exit
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
exec java -Xmx64m -Xms64m -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
