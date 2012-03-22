#!/usr/bin/python
import yaml, os, subprocess, pipes, sys

if len(sys.argv) != 2:
  print ("Start as %s path/to/uberjar" %sys.argv[0])
  sys.exit(1)

uberjar=sys.argv[1]
channels = yaml.load(open("/etc/zapps/channels.yaml"))
hostname=subprocess.check_output("hostname").decode("ascii").strip().split(".")[0] #the first one introducing non-ascii characters in a hostname will be hung by his feet in the main lobby until all eternety (and will break this code)
for channel in channels:
  channel["uberjar"] = uberjar
  if channel["active"] and channel["purkinje_hostname"] == hostname:
      purkinje_command = "java -jar %(uberjar)s %(purkinje_port)s %(device)s %(frequency)s" % {x:pipes.quote(str(y)) for x,y in channel.items()}
      command=("tmux", "new-session", "-d", "-s", "purkinje %s" % channel["name"], purkinje_command)
      print ('Now starting purkingje for %(name)s, source %(device)s @ %(frequency)s MHz to %(purkinje_port)s' % channel)
      success = subprocess.call(command)
      if success != 0:
          print ("subprocess ended with value %d" % success)
          sys.exit(1)
print ("done")
