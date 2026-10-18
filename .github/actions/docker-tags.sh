#!/usr/bin/env bash

#
# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
# attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
#

# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -e

function usage() {
    echo "Usage: $0 IMAGE TAGS"
    echo "  IMAGE: The image to tag"
    echo "  TAGS: A comma-separated list of tags to apply"
    echo "Optional env vars:"
    echo "  STAGED_TAG: The tag to use as the source image (default: staged)"
}

if [ "$#" -ne 2 ]; then
  usage
  exit 1
fi

IMAGE="$1"
JOINED_TAGS="$2"
STAGED_TAG="${STAGED_TAG:-staged}"

if [ -z "$IMAGE" ]; then
  echo "Error: IMAGE is required"
  usage
  exit 1
fi

if [[ ! "$IMAGE" =~ ^[a-zA-Z0-9_./-]+$ ]]; then
  echo "Error: Invalid image name '$IMAGE'"
  exit 1
fi

if [ -z "$JOINED_TAGS" ]; then
  echo "Error: TAGS is required"
  usage
  exit 1
fi

if [[ ! "$STAGED_TAG" =~ ^[a-zA-Z0-9_./-]+$ ]]; then
  echo "Error: Invalid staged tag name '$STAGED_TAG'"
  exit 1
fi

# Check that the image exists
if ! docker image inspect "$IMAGE:$STAGED_TAG" &> /dev/null; then
  echo "Error: Image $IMAGE:$STAGED_TAG does not exist"
  exit 1
fi

IFS=',' read -r -a TAGS <<< "$JOINED_TAGS"

echo "Tagging $IMAGE with tags:" "${TAGS[@]}"

for TAG in "${TAGS[@]}"; do
  # Remove all spaces from the tag
  tag="${TAG//[[:space:]]/}"
  if [[ ! "$tag" =~ ^[a-zA-Z0-9_./-]+$ ]]; then
    echo "Error: Invalid tag name '$tag'"
    exit 1
  fi
  echo "Applying tag: '$tag'"
  docker tag "$IMAGE:$STAGED_TAG" "$IMAGE:$tag"
done
