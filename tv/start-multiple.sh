#!/bin/bash
echo "starting $1 instances"

BASEDIR="$(dirname "$0")"

for i in $(seq 1 $1); do
	tmux new-session -d -s "$(cat "${BASEDIR}"/frequencies | awk 'BEGIN {FS="\t"} NR=='${i}' {print $2}')" "${BASEDIR}/start-streaming.sh $i" # Yes this will break as soon as there is a space in the directory name, but I'm not sure how to escape twice....
done
