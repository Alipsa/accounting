#!/usr/bin/env bash
#
# Triggers the Build Distributions workflow on GitHub, waits for it to finish,
# downloads all platform artifacts, signs them with GPG, and creates a GitHub
# release with the signed artifacts attached.
#
# Usage:
#   ./release.sh                  # build, download, sign, release
#   ./release.sh --no-sign        # skip GPG signing
#   ./release.sh --no-release     # skip GitHub release creation
#   ./release.sh --skip-build     # skip CI build (use existing dist/)
#
# Prerequisites: gh (GitHub CLI), authenticated with repo access.
#

set -euo pipefail

REPO="Alipsa/accounting"
WORKFLOW="build-distributions.yml"
DIST_DIR="dist"
SIGN=true
RELEASE=true
BUILD=true

for arg in "$@"; do
  case "$arg" in
    --no-sign) SIGN=false ;;
    --no-release) RELEASE=false ;;
    --skip-build) BUILD=false ;;
    *) echo "Unknown argument: $arg"; exit 1 ;;
  esac
done

VERSION=$(./gradlew :app:properties -q 2>/dev/null | grep "^version:" | awk '{print $2}')
TAG="v${VERSION}"

if [ -z "$VERSION" ]; then
  echo "ERROR: Could not determine project version."
  exit 1
fi

echo "Project version: $VERSION (tag: $TAG)"

if [ "$BUILD" = true ]; then
  branch=$(git rev-parse --abbrev-ref HEAD)
  echo "Triggering workflow on branch: $branch"
  gh workflow run "$WORKFLOW" --repo "$REPO" --ref "$branch"

  echo "Waiting for workflow run to appear..."
  run_id=""
  for attempt in $(seq 1 12); do
    sleep 5
    run_id=$(gh run list --repo "$REPO" --workflow "$WORKFLOW" --branch "$branch" \
      --limit 1 --json databaseId,status --jq '.[0].databaseId')
    if [ -n "$run_id" ]; then
      break
    fi
    echo "  Attempt $attempt/12: run not yet visible..."
  done

  if [ -z "$run_id" ]; then
    echo "ERROR: Could not find workflow run after 60 seconds."
    exit 1
  fi

  echo "Waiting for run $run_id to complete (this takes a while)..."
  gh run watch "$run_id" --repo "$REPO" --exit-status

  echo "Downloading artifacts to $DIST_DIR/"
  rm -rf "$DIST_DIR"
  mkdir -p "$DIST_DIR"
  gh run download "$run_id" --repo "$REPO" --dir "$DIST_DIR"

  # Flatten: move files out of per-platform subdirectories (no-clobber to avoid overwrites)
  find "$DIST_DIR" -mindepth 2 -type f -exec mv -n {} "$DIST_DIR/" \;
  find "$DIST_DIR" -mindepth 1 -type d -empty -delete

  echo ""
  echo "Generating SHA-256 checksums..."
  for file in "$DIST_DIR"/*; do
    [ -f "$file" ] || continue
    shasum -a 256 "$file" | awk '{print $1}' > "${file}.sha256"
    echo "  Checksum: $(basename "$file").sha256"
  done

  echo ""
  echo "Downloaded artifacts:"
  ls -lh "$DIST_DIR/"
fi

if [ "$SIGN" = true ]; then
  echo ""
  echo "Signing artifacts with GPG..."
  for file in "$DIST_DIR"/*; do
    [ -f "$file" ] || continue
    case "$file" in
      *.asc) continue ;;
    esac
    gpg --armor --detach-sign "$file"
    echo "  Signed: $(basename "$file")"
  done
  echo ""
  echo "Signed artifacts:"
  ls -lh "$DIST_DIR/"
fi

if [ "$RELEASE" = true ]; then
  echo ""

  # Generate release notes from commits since the last tag (or all if no tags exist)
  last_tag=$(git describe --tags --abbrev=0 2>/dev/null || true)
  if [ -n "$last_tag" ]; then
    range="${last_tag}..HEAD"
  else
    range="HEAD"
  fi
  notes=$(git log "$range" --pretty=format:"- %s" --no-merges)

  release_body="## Alipsa Accounting ${VERSION}

### Changes
${notes}

### Downloads
| Platform | File |
|----------|------|"

  for file in "$DIST_DIR"/*; do
    [ -f "$file" ] || continue
    case "$file" in *.asc) continue ;; esac
    name=$(basename "$file")
    release_body="${release_body}
| $(echo "$name" | sed 's/.*linux.*/Linux/;s/.*windows.*/Windows/;s/.*macos.*/macOS/;s/.*AlipsaAccounting.*/macOS/') | \`${name}\` |"
  done

  if [ "$SIGN" = true ]; then
    release_body="${release_body}

All artifacts are signed with GPG. Verify with:
\`\`\`
gpg --verify <file>.asc <file>
\`\`\`"
  fi

  echo "Creating GitHub release $TAG..."
  gh release create "$TAG" \
    --repo "$REPO" \
    --title "Alipsa Accounting ${VERSION}" \
    --notes "$release_body" \
    "$DIST_DIR"/*

  echo ""
  release_url=$(gh release view "$TAG" --repo "$REPO" --json url --jq '.url')
  echo "Release published: $release_url"
fi

echo ""
echo "Done."
