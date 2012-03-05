#!/bin/bash
NR=$1
DEV="/dev/video$((NR-1))"
PORT=$((8000+NR))
BASEDIR="$(dirname "$0")"
FREQKHZ=$(cat "${BASEDIR}"/frequencies | awk 'NR=='${NR}' {print $1*1000}')
echo $BASEDIR
echo "Now streaming $(cat "${BASEDIR}"/frequencies | awk 'BEGIN {FS="\t"} NR=='${NR}' {print $2}') from ${DEV} on port $PORT"
cvlc v4l2://${DEV} --v4l2-tuner-frequency=${FREQKHZ}  --v4l2-standard PAL --sout='#standard{access=http,mux=ts,dst=*:'${PORT}'}'
