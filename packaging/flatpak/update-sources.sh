#!/bin/sh
# Points lyrenne-sources.json at one release's Linux tarballs (x86_64 and aarch64), with the
# sha256 GitHub reports for each asset. Run after the release is published:
#   sh packaging/flatpak/update-sources.sh 2.21.0
# CI swaps the same file for a local tarball to test a build before it is released (release.yml).
set -eu
V=${1:?usage: update-sources.sh <version>}
DIR=$(dirname "$0")
REPO=Doctordefector/Lyrenne
digest() {
    curl -fsSL "https://api.github.com/repos/$REPO/releases/tags/v$V" |
        python3 -c "import json,sys; a=[x for x in json.load(sys.stdin)['assets'] if x['name']==sys.argv[1]]; print(a[0]['digest'].split(':',1)[1]) if a else sys.exit('missing asset '+sys.argv[1])" "$1"
}
X64=$(digest "lyrenne-$V-linux-x86_64.tar.gz")
ARM=$(digest "lyrenne-$V-linux-aarch64.tar.gz")
cat > "$DIR/lyrenne-sources.json" <<JSON
[
  {
    "type": "archive",
    "only-arches": ["x86_64"],
    "url": "https://github.com/$REPO/releases/download/v$V/lyrenne-$V-linux-x86_64.tar.gz",
    "sha256": "$X64",
    "dest": "lyrenne"
  },
  {
    "type": "archive",
    "only-arches": ["aarch64"],
    "url": "https://github.com/$REPO/releases/download/v$V/lyrenne-$V-linux-aarch64.tar.gz",
    "sha256": "$ARM",
    "dest": "lyrenne"
  }
]
JSON
echo "lyrenne-sources.json now points at v$V. Add a <release version=\"$V\"> to the metainfo too."
