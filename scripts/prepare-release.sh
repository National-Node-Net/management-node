#!/usr/bin/env bash

# Automates the release-preparation steps that would otherwise be performed
# manually:
#   1. Switch to develop and pull the latest changes.
#   2. Create feature/prepare-release-<version>.
#   3. Run Maven in Docker to update the project POM version.
#   4. Open CHANGELOG.md for editing unless --no-edit-changelog is supplied.
#   5. Commit and push the preparation branch.
#   6. Open a pull request into develop.
#
# After the PR is reviewed and merged, create release/<version> from develop
# with no further commits, then open its pull request into main. The release
# and publish-github-release workflows publish the container, Git tag, GitHub
# Release, and SBOM.

set -euo pipefail

GITHUB_REPOSITORY="National-Node-Net/management-node"

usage() {
  echo "Usage: $0 <release-version> [--no-edit-changelog]"
  echo
  echo "Example: $0 1.2.3 --no-edit-changelog"
  echo 
  echo "Requirements: Docker, git, and the GitHub CLI (gh)."
  echo "Java and Maven do not need to be installed; Maven runs in Docker."
}

if [[ $# -lt 1 || $# -gt 2 || ! "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  usage >&2
  exit 1
fi

version="$1"
prepare_branch="feature/prepare-release-${version}"
edit_changelog=true

if [[ $# -eq 2 ]]; then
  if [[ "$2" != "--no-edit-changelog" ]]; then
    echo "Unknown option: $2" >&2
    usage >&2
    exit 1
  fi
  edit_changelog=false
fi

for command in git docker gh; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Required command not found: $command" >&2
    exit 1
  fi
done

if [[ -n "$(git status --porcelain)" ]]; then
  echo "The working tree is not clean. Commit or stash your changes first." >&2
  exit 1
fi

git switch develop
git pull --ff-only origin develop

if git show-ref --verify --quiet "refs/heads/${prepare_branch}" || \
   git ls-remote --exit-code --heads origin "${prepare_branch}" >/dev/null 2>&1; then
  echo "Branch already exists: ${prepare_branch}" >&2
  exit 1
fi

git switch -c "${prepare_branch}"

docker run --rm \
  --user "$(id -u):$(id -g)" \
  --env HOME=/tmp \
  --env MAVEN_CONFIG=/tmp/.m2 \
  --volume "${PWD}:/workspace" \
  --workdir /workspace \
  maven:3.9.11-eclipse-temurin-21 \
  mvn --batch-mode --no-transfer-progress \
    versions:set \
    -DnewVersion="${version}" \
    -DgenerateBackupPoms=false

if git diff --quiet -- pom.xml; then
  echo "The project POM is already at version ${version}; nothing to commit." >&2
  git switch develop
  git branch -d "${prepare_branch}"
  exit 1
fi

if [[ "${edit_changelog}" == true ]]; then
  read -r -a editor_command <<< "${VISUAL:-${EDITOR:-vi}}"
  "${editor_command[@]}" CHANGELOG.md
fi

git add pom.xml CHANGELOG.md
git commit -m "chore: prepare release ${version}"
git push --set-upstream origin "${prepare_branch}"

body_text=$(cat <<EOF
Updates the Maven project version to ${version} before creating release/${version}.

This PR was generated from script \`/scripts/prepare-release.sh\`.

## Next steps - to produce a release

1. Review and merge this PR into \`develop\`.
2. After the merge has completed, update your local \`develop\` branch:

       git switch develop
       git pull --ff-only origin develop

3. Create and push the release branch from \`develop\`. Do not make any commits
   on the release branch:

       git switch -c release/${version}
       git push --set-upstream origin release/${version}

4. Open the release PR into \`main\`:

       gh pr create --base main --head release/${version} --title "Release ${version}" --body "Release ${version}." --repo "${GITHUB_REPOSITORY}"

5. Review and merge the \`release/${version}\` PR into \`main\`.

Merging the release PR creates Git tag \`v${version}\`, publishes GitHub
Release \`v${version}\`, and builds the release container tagged \`${version}\`.
EOF
)

gh pr create \
  --base develop \
  --head "${prepare_branch}" \
  --title "chore: prepare release ${version}" \
  --body "$body_text" \
  --repo "${GITHUB_REPOSITORY}"

echo "Preparation PR created. After it is reviewed and merged, create release/${version} from develop."
