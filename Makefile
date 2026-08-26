GOARCH ?= amd64
GRADLE_ARGS ?=
SERVER_URL ?= https://tinitalk.example.com
CLIENT_GRADLE_ARGS = -PtinitalkServerUrl=$(SERVER_URL) $(GRADLE_ARGS)

.PHONY: server client test check clean

ifeq ($(OS),Windows_NT)
SHELL := cmd.exe
.SHELLFLAGS := /C
JAVA17 ?= C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
CREATE_DIST = if not exist dist mkdir dist
BUILD_SERVER = set CGO_ENABLED=0&& set GOOS=linux&& set GOARCH=$(GOARCH)&& go build -trimpath -buildvcs=false -ldflags "-s -w" -o dist/tinitalk-linux-$(GOARCH) ./cmd/tinitalk
BUILD_CLIENT = cd android && set JAVA_HOME=$(JAVA17)&& gradlew.bat testDebugUnitTest assembleDebug $(CLIENT_GRADLE_ARGS)
TEST_CLIENT = cd android && set JAVA_HOME=$(JAVA17)&& gradlew.bat testDebugUnitTest
COPY_CLIENT = copy /Y android\app\build\outputs\apk\debug\app-debug.apk dist\tinitalk-debug.apk >NUL
CLEAN_DIST = if exist dist rmdir /S /Q dist
else
SHELL := /bin/sh
CREATE_DIST = mkdir -p dist
BUILD_SERVER = CGO_ENABLED=0 GOOS=linux GOARCH=$(GOARCH) go build -trimpath -buildvcs=false -ldflags "-s -w" -o dist/tinitalk-linux-$(GOARCH) ./cmd/tinitalk
COPY_CLIENT = cp android/app/build/outputs/apk/debug/app-debug.apk dist/tinitalk-debug.apk
CLEAN_DIST = rm -rf dist
ifneq ($(WSL_DISTRO_NAME),)
WINDOWS_CMD ?= /mnt/c/Windows/System32/cmd.exe
WINDOWS_ROOT := $(shell wslpath -w "$(CURDIR)")
JAVA17 ?= C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
BUILD_CLIENT = $(WINDOWS_CMD) /D /C "cd /D $(WINDOWS_ROOT)\android && set JAVA_HOME=$(JAVA17)&& gradlew.bat testDebugUnitTest assembleDebug $(CLIENT_GRADLE_ARGS)"
TEST_CLIENT = $(WINDOWS_CMD) /D /C "cd /D $(WINDOWS_ROOT)\android && set JAVA_HOME=$(JAVA17)&& gradlew.bat testDebugUnitTest"
else
BUILD_CLIENT = cd android && ./gradlew testDebugUnitTest assembleDebug $(CLIENT_GRADLE_ARGS)
TEST_CLIENT = cd android && ./gradlew testDebugUnitTest
endif
endif

server:
	@$(CREATE_DIST)
	@$(BUILD_SERVER)

client:
	@$(CREATE_DIST)
	@$(BUILD_CLIENT)
	@$(COPY_CLIENT)

test:
	@go test ./...
	@$(TEST_CLIENT)

check: test server client

clean:
	@$(CLEAN_DIST)
