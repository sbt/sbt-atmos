sbt-atmos
=========

[sbt] plugin for running the Typesafe Console in development.


Add plugin
----------

This plugin requires sbt 0.12.

Add plugin to `project/plugins.sbt`. For example:

    addSbtPlugin("com.typesafe.sbt" % "sbt-atmos" % "0.1.0-SNAPSHOT")

Add the sbt-atmos settings to the project. For a `build.sbt` and a line with:

    atmosSettings

To run your application with the Typesafe Console there are extra versions of
the `run` and `run-main` tasks. These use the same underlying settings for the
regular `run` tasks, and also add the configuration needed to instrument your
application, and start and stop Typesafe Console.

To run the default or discovered main class use:

    atmos:run

To run a specific main class:

    atmos:run-main org.something.MainClass


Mailing list
------------

Please use the [Typesafe Console mailing list][email].


Contribution policy
-------------------

Contributions via GitHub pull requests are gladly accepted from their original
author. Before we can accept pull requests, you will need to agree to the
[Typesafe Contributor License Agreement][cla] online, using your GitHub account.


License
-------

This code is open source software licensed under the [Apache 2.0 License][apache].
Feel free to use it accordingly.


[sbt]: https://github.com/sbt/sbt
[console]: http://typesafe.com/platform/runtime/console
[email]: http://groups.google.com/group/typesafe-console
[cla]: http://www.typesafe.com/contribute/cla
[apache]: http://www.apache.org/licenses/LICENSE-2.0.html
