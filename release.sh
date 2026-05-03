#!/usr/bin/env bash
#
# Triggers the Build Distributions workflow on GitHub, waits for it to finish,
# downloads all platform artifacts, signs them with GPG, and creates a GitHub
# release with the signed artifacts attached. Release notes are read from the
# matching "## v<version>" section in release.md.
#
# Usage:
#   ./release.sh                  # build, download, sign, release
#   ./release.sh --no-sign        # skip GPG signing
#   ./release.sh --no-release     # skip GitHub release creation
#   ./release.sh --skip-build     # skip CI build (use existing dist/)
#
# Prerequisites: gh (GitHub CLI), authenticated with repo access, and a
# release.md section for the current project version.
#

set -euo pipefail

REPO="Alipsa/accounting"
WORKFLOW="build-distributions.yml"
DIST_DIR="dist"
RELEASE_NOTES_FILE="release.md"
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

if [ "$RELEASE" = true ]; then
  if [ ! -f "$RELEASE_NOTES_FILE" ]; then
    echo "ERROR: Release notes file not found: $RELEASE_NOTES_FILE"
    exit 1
  fi

  release_body=$(awk -v tag="$TAG" '
    BEGIN {
      prefix = "## " tag
    }
    index($0, prefix) == 1 {
      suffix = substr($0, length(prefix) + 1)
      if (suffix == "" || substr(suffix, 1, 1) == "," || substr(suffix, 1, 1) == " ") {
        found = 1
        print
        next
      }
    }
    found && /^## v[0-9]/ {
      exit
    }
    found {
      print
    }
    END {
      if (!found) {
        exit 1
      }
    }
  ' "$RELEASE_NOTES_FILE") || {
    echo "ERROR: No release notes section found for $TAG in $RELEASE_NOTES_FILE"
    echo "Expected a heading like: ## $TAG, YYYY-MM-DD"
    exit 1
  }

  echo "Using release notes from $RELEASE_NOTES_FILE section $TAG."
fi

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
  mkdir -p "$DIST_DIR/extras"
  gh run download "$run_id" --repo "$REPO" --dir "$DIST_DIR/extras"

  # Promote known distribution files to the dist root; leave anything else
  # behind in extras/ so the root stays clean.
  shopt -s nullglob
  for f in "$DIST_DIR"/extras/*/alipsa-accounting-*.zip \
           "$DIST_DIR"/extras/*/app-*.zip; do
    mv "$f" "$DIST_DIR/"
  done
  shopt -u nullglob

  # Flatten any residual platform subdirs inside extras/ and drop empties.
  find "$DIST_DIR/extras" -mindepth 2 -type f -exec mv -n {} "$DIST_DIR/extras/" \;
  find "$DIST_DIR/extras" -mindepth 1 -type d -empty -delete
  rmdir "$DIST_DIR/extras" 2>/dev/null || true

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
      *.asc|*.sha256) continue ;;
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
  echo "Creating GitHub release $TAG..."
  mapfile -t release_assets < <(find "$DIST_DIR" -maxdepth 1 -type f)
  gh release create "$TAG" \
    --repo "$REPO" \
    --title "Alipsa Accounting ${VERSION}" \
    --notes "$release_body" \
    "${release_assets[@]}"

  echo ""
  release_url=$(gh release view "$TAG" --repo "$REPO" --json url --jq '.url')
  echo "Release published: $release_url"
fi

echo ""
echo "Done."
