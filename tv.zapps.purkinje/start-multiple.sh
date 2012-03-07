#!/bin/bash
echo "starting $1 instances to $2"

BASEDIR="$(dirname "$0")"

for i in $(seq 1 $1); do
	srcport=$((8000 + i))
	dstport=$((9000 + i))
	url="${2}:${srcport}"
	echo "$url -> $dstport"
	tmux new-session -d -s "purkinje ${srcport} -> ${dstport}" "(cd ${BASEDIR}; lein run $dstport $url)" # Yes this will break as soon as there is a space in the directory name, but I'm not sure how to escape twice....
done
