#!/usr/bin/python
import yaml, os, subprocess, pipes, sys
dirname = os.path.dirname(__file__);
channels = yaml.load(open(dirname+"/../channels.yaml"))
hostname=subprocess.check_output("hostname").decode("ascii").strip().split(".")[0] #the first one introducing non-ascii characters in a hostname will be hung by his feet in the main lobby until all eternety (and will break this code)
for channel in channels:
  if channel["active"] and channel["tubby_hostname"] == hostname:
      streaming_command = "echo 'Now streaming'%(name)s' from '%(device)s' to '%(tubby_port)s && cvlc v4l2://%(device)s --v4l2-tuner-frequency=%(frequency)s  --v4l2-standard PAL --sout='#standard{access=http,mux=ts,dst=*:'%(tubby_port)s'}'" % {x:pipes.quote(str(y)) for x,y in channel.items()}
      command=("tmux", "new-session", "-d", "-s", channel["name"], streaming_command)
      print ('Now streaming %(name)s from %(device)s to %(tubby_port)s' % channel)
      success = subprocess.call(command)
      if success != 0:
          print ("subprocess ended with value %d" % success)
          sys.exit(1)
print ("done")
