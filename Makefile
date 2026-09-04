GOARCH ?= amd64
GRADLE_ARGS ?=
GRADLE_FLAGS ?= --no-daemon
DEBUG_CLIENT_GRADLE_ARGS = $(GRADLE_ARGS) -PtinitalkAbi=all
MIN_CLIENT_GRADLE_ARGS = $(GRADLE_ARGS) -PtinitalkAbi=arm64

.PHONY: server client client-min client-release test check clean

ifeq ($(OS),Windows_NT)
SHELL := cmd.exe
.SHELLFLAGS := /C
NULL_DEVICE := NUL
CREATE_DIST = if not exist dist mkdir dist
BUILD_SERVER = set CGO_ENABLED=0&& set GOOS=linux&& set GOARCH=$(GOARCH)&& go build -trimpath -buildvcs=false -ldflags "-s -w -X tinitalk/internal/httpapi.serverCommit=$(SERVER_COMMIT)" -o dist/tinitalk-linux-$(GOARCH) ./cmd/tinitalk
BUILD_CLIENT = cd android && gradlew.bat $(GRADLE_FLAGS) testDebugUnitTest assembleDebug $(DEBUG_CLIENT_GRADLE_ARGS)
BUILD_CLIENT_MIN = cd android && gradlew.bat $(GRADLE_FLAGS) testDebugUnitTest verifyWebRtcJniMin $(MIN_CLIENT_GRADLE_ARGS)
BUILD_CLIENT_RELEASE = cd android && gradlew.bat $(GRADLE_FLAGS) testDebugUnitTest exportReleaseApk $(MIN_CLIENT_GRADLE_ARGS)
TEST_CLIENT = cd android && gradlew.bat $(GRADLE_FLAGS) testDebugUnitTest
COPY_CLIENT = copy /Y android\app\build\outputs\apk\debug\app-debug.apk dist\tinitalk-debug.apk >NUL
COPY_CLIENT_MIN = copy /Y android\app\build\outputs\apk\min\app-min.apk dist\tinitalk-min.apk >NUL
CLEAN_DIST = if exist dist rmdir /S /Q dist
else
SHELL := /bin/sh
NULL_DEVICE := /dev/null
CREATE_DIST = mkdir -p dist
BUILD_SERVER = CGO_ENABLED=0 GOOS=linux GOARCH=$(GOARCH) go build -trimpath -buildvcs=false -ldflags "-s -w -X tinitalk/internal/httpapi.serverCommit=$(SERVER_COMMIT)" -o dist/tinitalk-linux-$(GOARCH) ./cmd/tinitalk
COPY_CLIENT = cp android/app/build/outputs/apk/debug/app-debug.apk dist/tinitalk-debug.apk
COPY_CLIENT_MIN = cp android/app/build/outputs/apk/min/app-min.apk dist/tinitalk-min.apk
CLEAN_DIST = rm -rf dist
ifneq ($(WSL_DISTRO_NAME),)
WINDOWS_CMD ?= /mnt/c/Windows/System32/cmd.exe
WINDOWS_ROOT := $(shell wslpath -w "$(CURDIR)")
BUILD_CLIENT = $(WINDOWS_CMD) /D /C "cd /D $(WINDOWS_ROOT)\android && gradlew.bat $(GRADLE_FLAGS) testDebugUnitTest assembleDebug $(DEBUG_CLIENT_GRADLE_ARGS)"
BUILD_CLIENT_MIN = $(WINDOWS_CMD) /D /C "cd /D $(WINDOWS_ROOT)\android && gradlew.bat $(GRADLE_FLAGS) testDebugUnitTest verifyWebRtcJniMin $(MIN_CLIENT_GRADLE_ARGS)"
BUILD_CLIENT_RELEASE = $(WINDOWS_CMD) /D /C "cd /D $(WINDOWS_ROOT)\android && gradlew.bat $(GRADLE_FLAGS) testDebugUnitTest exportReleaseApk $(MIN_CLIENT_GRADLE_ARGS)"
TEST_CLIENT = $(WINDOWS_CMD) /D /C "cd /D $(WINDOWS_ROOT)\android && gradlew.bat $(GRADLE_FLAGS) testDebugUnitTest"
else
BUILD_CLIENT = cd android && ./gradlew $(GRADLE_FLAGS) testDebugUnitTest assembleDebug $(DEBUG_CLIENT_GRADLE_ARGS)
BUILD_CLIENT_MIN = cd android && ./gradlew $(GRADLE_FLAGS) testDebugUnitTest verifyWebRtcJniMin $(MIN_CLIENT_GRADLE_ARGS)
BUILD_CLIENT_RELEASE = cd android && ./gradlew $(GRADLE_FLAGS) testDebugUnitTest exportReleaseApk $(MIN_CLIENT_GRADLE_ARGS)
TEST_CLIENT = cd android && ./gradlew $(GRADLE_FLAGS) testDebugUnitTest
endif
endif

SERVER_COMMIT := $(shell git -c safe.directory="$(CURDIR)" rev-parse --short=8 HEAD 2>$(NULL_DEVICE))
ifeq ($(strip $(SERVER_COMMIT)),)
SERVER_COMMIT := unknown
endif

server:
	@$(CREATE_DIST)
	@$(BUILD_SERVER)

client:
	@$(CREATE_DIST)
	@$(BUILD_CLIENT)
	@$(COPY_CLIENT)

client-min:
	@$(CREATE_DIST)
	@$(BUILD_CLIENT_MIN)
	@$(COPY_CLIENT_MIN)

client-release:
	@$(CREATE_DIST)
	@$(BUILD_CLIENT_RELEASE)

test:
	@go test ./...
	@$(TEST_CLIENT)

check: test server client

clean:
	@$(CLEAN_DIST)
