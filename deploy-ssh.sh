#!/bin/sh -eu

if test "$#" -lt 1; then
  cat << EOF
Usage: $0 username@remoteServer
EOF
  return 2
fi

cd -- "$(dirname -- "$(realpath -- "$0" || printf %s "$0")")"

remote="$1"
remote="${remote#http*://}"
remote="${remote%%/*}"
case "$remote" in *@*) ;; *) remote="ubuntu@$remote" ;; esac

set -x
remote="$remote"
rsync -zv --progress --rsync-path="sudo rsync" -- src/ear/target/eaas-server.ear "$remote:/eaas-home/deployments/"
ssh -- "$remote" sudo systemctl restart eaas
set +x

printf '\nssh %s\nhttps://%s\n' "$remote" "${remote##*@}"
