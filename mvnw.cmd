@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements.  See the NOTICE file
@REM distributed with this work for additional information
@REM regarding copyright ownership.  The ASF licenses this file
@REM to you under the Apache License, Version 2.0 (the
@REM "License"); you may not use this file except in compliance
@REM with the License.  You may obtain a copy of the License at
@REM
@REM    https://www.apache.org/licenses/LICENSE-2.0
@REM
@REM Unless required by applicable law or agreed to in writing,
@REM software distributed under the License is distributed on an
@REM "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
@REM KIND, either express or implied.  See the License for the
@REM specific language governing permissions and limitations
@REM under the License.
@REM SPDX-License-Identifier: Apache-2.0

@if "%DEBUG%"=="" @echo off
setlocal enabledelayedexpansion

if not "%JAVA_HOME%"=="" goto gotJavaHome
for /f "delims=" %%a in ('where java 2^>nul') do (
  set JAVA_HOME=%%a
  goto findJavaVersion
)
echo Error: JAVA_HOME not set and java could not be found in PATH 1>&2
exit /b 1

:findJavaVersion
for /f "delims=" %%a in ('"%JAVA_HOME%\bin\java" -version 2^>&1') do set "JAVA_VERSION=%%a"

:gotJavaHome
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo Error: JAVA_HOME is not defined correctly 1>&2
  echo JAVA_HOME = "%JAVA_HOME%" 1>&2
  exit /b 1
)

setlocal enabledelayedexpansion
set CLASSWORLDS_JAR=%~dp0\.mvn\wrapper\maven-wrapper.jar
if not exist "%CLASSWORLDS_JAR%" (
  echo Error: maven-wrapper.jar not found in %~dp0\.mvn\wrapper 1>&2
  exit /b 1
)

set MAVEN_PROJECTBASEDIR=%~dp0
if not "%MAVEN_CMD_LINE_ARGS%"=="" goto appendArgs

set MAVEN_CMD_LINE_ARGS=%*

:appendArgs
"%JAVA_HOME%\bin\java.exe" ^
  -cp "%CLASSWORLDS_JAR%" ^
  "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" ^
  org.apache.maven.wrapper.MavenWrapperMain ^
  %MAVEN_CMD_LINE_ARGS%

if %ERRORLEVEL% neq 0 (
  exit /b %ERRORLEVEL%
)
goto end

:end
endlocal &amp; goto :EOF
