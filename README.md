# Sledge - We're lost in music

Available protocols such as DAAP, DLNA etc for publishing music media
files (e.g. ripped CDs) on the local network and/or the internet are
either insanely complex or proprietary or sometimes both.  Which is odd given that HTTP is so eminently suited to schlepping bits across the net.

Sledge is a spare-time/weekend hack to see if there's a simpler way.
It runs [green-tags](https://github.com/DanPallas/green-tags) on your audio collection and creates an index on disk, then starts a web server that knows how to transcode it (requires [libav](https://libav.org/)) and provides a single-page JS (actually ClojureScript) app that lets you search it and play the music.

Status: works on my machine, works on my phone (Firefox for Android), ugly, many rough edges.

## Configuring

First create a configuration file to tell it where your music collection is and where to create its index files

```
$ cat sledge.conf.edn
{:index "/srv/media/.sledge/"
 :folders ["/srv/media/Music/"]
 :port 53281
}
```

## Running in development mode

I have not as yet managed to figure out why the leiningen `dev` profile appears to be loaded even when making uberjars.  Until I do (and all advice on that score is welcome), it is necessary to use an additional profile to include dev-only configuration such as the browser repl

    $ lein with-profile +brepl cljsbuild auto
    # and in another window
    $ lein with-profile +brepl repl
    sledge.core=> (-main "conf.edn")

Now point your web browser at http://localhost:53281

A websocket-based browser repl is available, assuming your browser
supports websockets: run `(user/simple-brepl)` in the Clojure REPL to
start it.


## Running in deployment mode

    $ lein with-profile uberjar do clean, cljsbuild once, uberjar
    $ java -jar target/uberjar+uberjar/sledge-0.1.0-SNAPSHOT-standalone.jar sledge.conf.edn

Now point your web browser at http://localhost:53281


## Securing it

Don't run this on an untrusted network.  It's had no real security
review and it runs external commands.

I started looking at what it would take to add SSL, but it's kind of
involved.  If you're running a Linux-like OS, you can use stud as an
SSL proxy: set up would be something like this -

```
$ openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out
cert.pem -days 365
$ cat *.pem > ssl.combined
$ sudo stud -f 0,443 -b 0,53281 ssl.combined
```


## A note on Android

The builtin browser in Android Jelly Bean, at least on the phone I
own, doesn't work very well with Sledge once the screen turns off.
The browser stops calling JS event handlers, meaning that when the
player reaches the end of the current track Sledge isn't told and
can't start the next one.  I can't see an easy way to fix it either,
but a simple workaround is to download and use Firefox for Android
instead.


## Copyright

Copyright © 2014,2015 Daniel Barlow

Distributed under the GNU Affero General Public License, which means
this is free software but that if you run it for the benefit of people
who are not you, you need to provide them a link to download what
you're running.

Why the restriction?  It depends on libav for transcoding, and libav is GPL, and Affero GPL seems to be a good option for enforcing the spirit of GPL for systems where the software itself lives on a server and is not actually distibuted to its users. 

If that poses a problem for your preferred use case, I am happy to
discuss alternative licencing arrangements.
