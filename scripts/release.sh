#!/bin/bash
# Generates zip files ready to be uploaded to the artifactory
# set -ex

# bazel build farm version
readonly BBF_VERSION="${1:-"LOCAL-SNAPSHOT"}"
readonly REPO_ROOT="$(git rev-parse --show-toplevel)"
readonly OUT_DIR="${REPO_ROOT}/tmp"

release() {
  local VERSION_NAME="$1"
  local TARGET_NAME="$2"
  local BAZEL_BIN_PATH="./bazel-bin/src/main/java/build/buildfarm"

  local TARGET_OUT_DIR="${OUT_DIR}/${TARGET_NAME}-${VERSION_NAME}"
  local ARTIFACT_PATH="${TARGET_OUT_DIR}/build/buildfarm/${TARGET_NAME}/${VERSION_NAME}"

  # Build target and pom file
  ./bazelw build --define=pom_version=${VERSION_NAME} \
    //src/main/java/build/buildfarm:${TARGET_NAME} \
    //src/main/java/build/buildfarm:${TARGET_NAME}_pom

  # Clean output directory
  rm -rf $TARGET_OUT_DIR
  mkdir -p $ARTIFACT_PATH

  # Move pom, jar and sources files to output dir
  cp "${BAZEL_BIN_PATH}/lib${TARGET_NAME}.jar" "$ARTIFACT_PATH/${TARGET_NAME}-${VERSION_NAME}.jar"
  cp "${BAZEL_BIN_PATH}/${TARGET_NAME}_pom.xml" "$ARTIFACT_PATH/${TARGET_NAME}-${VERSION_NAME}.pom"
  cp "${BAZEL_BIN_PATH}/lib${TARGET_NAME}-src.jar" "$ARTIFACT_PATH/${TARGET_NAME}-${VERSION_NAME}-sources.jar"

  # Build zip
  pushd ${TARGET_OUT_DIR}
  zip -r build.zip build
  popd

  echo "----> zip: ${TARGET_OUT_DIR}/build.zip"
}

release $BBF_VERSION "ac"
release $BBF_VERSION "cas"
release $BBF_VERSION "common"
release $BBF_VERSION "http-proxy"
release $BBF_VERSION "instance"
release $BBF_VERSION "memory-instance"
release $BBF_VERSION "operationqueue-worker"
release $BBF_VERSION "server-instance"
release $BBF_VERSION "server"
release $BBF_VERSION "stub-instance"
release $BBF_VERSION "worker"
