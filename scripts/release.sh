#!/bin/bash
# Releases and uploads bundle to artifactory
# Required: 
#   - ARTIFACTORY_KEY: Artifactory API Key. To obtain one, go to artifactory-sjc1.uberinternal.com/artifactory/webapp/#/profile
# Optional:
#   - BBF_VERSION: Version of the artifact. Defaults to LOCALSNAPSHOT-${CURR_DATE}
#   - ARTIFACTORY_URL: Defaults to http://artifactory.uber.internal:4587
#   - ARTIFACTORY_REPO: Defaults to artifactory/libs-release-local

set -ex
readonly REPO_ROOT="$(git rev-parse --show-toplevel)"
pushd $REPO_ROOT
trap popd EXIT

readonly CURR_DATE=$(date +'%Y%m%d%H%M%S')
readonly BBF_VERSION=${1:-"LOCALSNAPSHOT-${CURR_DATE}"}
readonly OUT_DIR="${REPO_ROOT}/tmp"
readonly ARTIFACTORY_URL=${ARTIFACTORY_URL:-"http://artifactory.uber.internal:4587"}
readonly ARTIFACTORY_REPO=${ARTIFACTORY_REPO:-"artifactory/libs-release-local"}

upload_to_artifactory() {
  local LOCAL_PATH="${1}"
  local ARTIFACT_PATH="${2}"

  curl -H "X-JFrog-Art-Api:${ARTIFACTORY_KEY}" \
      -T $LOCAL_PATH \
      -X PUT "${ARTIFACTORY_URL}/${ARTIFACTORY_REPO}/${ARTIFACT_PATH}"
}

release() {
  local VERSION_NAME="$1"
  local TARGET_NAME="$2"

  local BAZEL_BIN_PATH="${REPO_ROOT}/bazel-bin/src/main/java/build/buildfarm"
  local ARTIFACT_PATH="build/buildfarm/${TARGET_NAME}/${VERSION_NAME}"

  ./bazelw build --define=pom_version=${VERSION_NAME} \
    //src/main/java/build/buildfarm:${TARGET_NAME} \
    //src/main/java/build/buildfarm:${TARGET_NAME}_pom

  upload_to_artifactory "${BAZEL_BIN_PATH}/lib${TARGET_NAME}.jar" "$ARTIFACT_PATH/${TARGET_NAME}-${VERSION_NAME}.jar"
  upload_to_artifactory "${BAZEL_BIN_PATH}/${TARGET_NAME}_pom.xml" "$ARTIFACT_PATH/${TARGET_NAME}-${VERSION_NAME}.pom"
}

release $BBF_VERSION "bundle"
