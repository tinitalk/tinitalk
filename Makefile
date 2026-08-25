GOARCH ?= amd64
JAVA17 ?= C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot
GRADLE_ARGS ?=
SHELL := cmd.exe
.SHELLFLAGS := /C

.PHONY: server client test check clean

server:
	@if not exist dist mkdir dist
	@set CGO_ENABLED=0&& set GOOS=linux&& set GOARCH=$(GOARCH)&& go build -trimpath -buildvcs=false -ldflags "-s -w" -o dist/tinitalk-linux-$(GOARCH) ./cmd/tinitalk

client:
	@if not exist dist mkdir dist
	@cd android && set JAVA_HOME=$(JAVA17)&& gradlew.bat testDebugUnitTest assembleDebug $(GRADLE_ARGS)
	@copy /Y android\app\build\outputs\apk\debug\app-debug.apk dist\tinitalk-debug.apk >NUL

test:
	@go test ./...
	@cd android && set JAVA_HOME=$(JAVA17)&& gradlew.bat testDebugUnitTest

check: test server client

clean:
	@if exist dist rmdir /S /Q dist
