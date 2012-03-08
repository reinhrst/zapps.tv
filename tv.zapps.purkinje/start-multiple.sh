#!/bin/bash
if [[ "$3" == "" ]]; then
	echo "use $0 path/to/uberjar #nrinstances# #tv-server#"
	exit 1
fi;

echo "starting $1 instances to $2"

BASEDIR="$(dirname "$0")"

for i in $(seq 1 $2); do
	srcport=$((8000 + i))
	dstport=$((9000 + i))
	url="http://${3}:${srcport}"
	echo "$url -> $dstport"
	tmux new-session -d -s "purkinje ${srcport} -> ${dstport}" "(java -jar $1 $dstport $url)" # Yes this will break as soon as there is a space in the directory name, but I'm not sure how to escape twice....
done
