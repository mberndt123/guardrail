#!/bin/bash

set -e

module_name="$1"

gh_api() {
  path="$1"; shift
  curl \
    -H "Accept: application/vnd.github.v3+json" \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    "$@" \
    "https://api.github.com/$path"
}

fetch_pr() {
  pr_number="$1"
  cache="$2"
  echo "Fetching labels for current PR (${pr_number})" >&2
  gh_api "repos/guardrail-dev/guardrail/pulls/${pr_number}" -o "$cache"
}

labels_since_last_release() {
  module_name="$1"; shift || die 'Missing module_name'
  cache="$1"; shift || die 'Missing cache target'

  latest_tag="$(git tag | grep "^$module_name-v[0-9]" | tail -n 1)"
  if [ -z "$latest_tag" ]; then
    echo '{"labels":[]}' > "$cache"
  else
    module_released_on="$(git show "${latest_tag}" --format=%cI)"
    gh_api "search/issues?q=repo:guardrail-dev/guardrail+type:pr+is:merged+closed:>${module_released_on}" \
      | jq -cM '{labels: (.items | map(.labels) | flatten | map(.name) | unique | map({ name: . })) }' \
      > "$cache"
  fi
}

cachedir=target/github
if [ -n "$GITHUB_EVENT_PATH" ]; then
  mkdir -p "$cachedir"
  pr_number="$(jq --raw-output .pull_request.number "$GITHUB_EVENT_PATH")"
  cache="$cachedir/guardrail-ci-${pr_number}-labels-cache.json"

  if [ -f "$cache" ]; then
    echo "Using PR labels from $cache" >&2
  elif [ -n "$module_name" ]; then
    labels_since_last_release "$module_name" "$cache"
  elif [ "$pr_number" != "null" ]; then
    fetch_pr "$pr_number" "$cache"
  else
    # If $pr_number is null, we're either building a branch or master,
    # so either way just skip labels.
    echo '{"labels": []}' > "$cache"
  fi

  msg="$(jq -r .message < "$cache")"
  if [ "$msg" != "null" ]; then  # If the API returned an error message
    echo "ERROR: ${msg}" >&2
    exit 1
  fi
  cat "$cache" | jq -r '.labels | map(.name)[]'
else
  echo 'Skipping finding labels because GITHUB_EVENT_PATH is not defined' >&2
fi
