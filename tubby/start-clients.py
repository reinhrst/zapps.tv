#!/usr/bin/python
import yaml, os, subprocess, pipes, sys
dirname = os.path.dirname(__file__);
channels = yaml.load(file(dirname+"/../channels.yaml"))
hostname=subprocess.check_output("hostname").strip().split(".")[0]
for channel in channels:
  if channel["active"] == "true" and channel["mpeg_host"] == hostname:
      streaming_command = "echo 'Now streaming'%(name)s' from '%(device)s' to '%(port)s && echo cvlc v4l2://%(device)s --v4l2-tuner-frequency=%(frequency)s  --v4l2-standard PAL --sout='#standard{access=http,mux=ts,dst=*:'%{port)s'}" % {x:pipes.quote(y) for x,y in channel.items()}
      command=("tmux new-session", "-d", "-s", channel["name"], streaming_command)
      print 'Now streaming %(name)s from %(device)s to %(port)s' % channel
      success = subprocess.call(command)
      if success != 0:
          print "subprocess ended with value %d" % success
          sys.exit(1)
print "done"
