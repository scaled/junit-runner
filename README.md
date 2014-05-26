# Scaled JUnit Runner

This handles running [JUnit] tests for [Scaled]. It runs as a daemon which processes requests to
invoke tests and sends the output back to Scaled for parsing and processing. It's not wildly
different from the stock JUnit runner except that it provides more detailed feedback on test
excecution and errors to make it easier for Scaled to parse the results and display them sensibly.

## Distribution

Scaled JUnit Runner is released under the New BSD License. The most recent version of the code is
available at http://github.com/scaled/junit-runner

[JUnit]: http://junit.org
[Scaled]: https://github.com/scaled/scaled
