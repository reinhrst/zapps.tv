Zapps

The code in this application was written with the idea of using the audio coming from live TV, to let another device know what program was being watched. There were 3 parts to this project. Purkinje is the code that runs on a server, and accesses through video4linux video hardware to rip and fingerprint the audiostream. Zippo is a small server app that connects to different purkinje-processes (one for each channel) and waits for a client connection (from the iPhone app) to try to match. A client (IOS client without any sort of interface in myapp) would connect to zippo, and send its own fingerpints. On the server the fingerprints would be macthed and the server would report back whether the received data matched any of the followed ripped audio streams in the last X seconds.

The matching worked "reasonably well", although some major obstacles still would have needed to be overcome.

The project was abandoned when it was not deemed commercially viable. It's being shared here mainly for people to see some example code of some interesting not very often used techinques (like doing DCTs on the iPhone DSP). If you have questions about any of the code here, I's be happy to assist where I can. The code is being licensed through the [Creative Commons Attribution-ShareAlike 3.0 Unported License][1], which basically means: have fun with it, but share whatever you do, and somewhere mention that you got the original from https://github.com/reinhrst . If you would like to obtain any of the code under a different license, contact me!

[1]: http://creativecommons.org/licenses/by-sa/3.0/deed.en_US
