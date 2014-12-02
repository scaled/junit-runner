//
// Scaled JUnit Runner - integrates JUnit with Scaled's project services
// http://github.com/scaled/junit-runner/blob/master/LICENSE

package scaled.junit

import java.io.{File, PrintWriter, StringWriter}
import java.net.{URL, URLClassLoader}
import java.util.{Map => JMap}
import org.junit.runner.manipulation.{Filter, NoTestsRemainException}
import org.junit.runner.notification.{Failure, RunListener, RunNotifier}
import org.junit.runner.{Description, JUnitCore, Request, Result, Runner}
import scaled.prococol.{Sender, Receiver}

class Server (sender :Sender) extends Receiver.Listener {
  import scala.collection.convert.WrapAsScala._
  import scala.collection.convert.WrapAsJava._

  def onMessage (cmd :String, args :JMap[String,String]) :Unit = cmd match {
    case "test" => test(args)
    case _      => sender.send("error", Map("cause" -> s"Unknown command: $cmd"))
  }

  private def test (data :JMap[String,String]) {
    def get[T] (key :String, defval :T, fn :String => T) = data.get(key) match {
      case null => defval
      case text => fn(text)
    }

    // captures output before, during and after test runs
    var inBetween = false
    def startBetween () = if (!inBetween) {
      sender.startMessage("between")
      sender.startText("output")
      sender.textWriter.flush()
      inBetween = true
    }
    def endBetween () = if (inBetween) {
      inBetween = false
      sender.endText()
      sender.endMessage()
    }

    val ju = new JUnitCore()
    ju.addListener(new RunListener() {
      override def testRunStarted (descrip :Description) {
        startBetween()
      }
      override def testRunFinished (result :Result) {
        endBetween()
        sender.send("results", Map("ran" -> result.getRunCount.toString,
                                   "ignored" -> result.getIgnoreCount.toString,
                                   "failed" -> result.getFailureCount.toString,
                                   "duration" -> result.getRunTime.toString,
                                   "success" -> result.wasSuccessful.toString))
      }
      override def testStarted (descrip :Description) {
        endBetween()
        sender.send("started", Map("class" -> descrip.getClassName,
                                   "method" -> descrip.getMethodName))
        startBetween()
      }
      override def testFinished (descrip :Description) {
        endBetween()
        startBetween()
      }
      override def testFailure (failure :Failure) {
        endBetween()
        val trace = new StringWriter()
        failure.getException.printStackTrace(new PrintWriter(trace))
        val descrip = failure.getDescription
        sender.send("failure", Map("class" -> descrip.getClassName,
                                   // if we fail during a @Before/@After, this is null
                                   "method" -> String.valueOf(descrip.getMethodName),
                                   "trace" -> trace.toString))
        startBetween()
      }
      override def testAssumptionFailure (failure :Failure) {
        testFailure(failure) // TODO: need we differentiate?
      }
      override def testIgnored (descrip :Description) {} // nada
    })

    try {
      val classpath = get("classpath", Array[URL](), _.split("\t").map(new File(_).toURI.toURL))
      val classes = get("classes", Array[String](), _.split("\t"))
      val loader = new URLClassLoader(classpath, getClass.getClassLoader)
      val request = Request.classes(classes.map(loader.loadClass) :_*)
      val filter = get("filter", "", identity[String])
      val frequest = if (filter == "") request else new Request() {
        val filt = new Filter() {
          override def describe = s"Selects tests named '$filter'"
          override def shouldRun (d :Description) = d.getDisplayName match {
            case dn if (dn.indexOf('(') == -1) => true // this is a suite name, let it run
            case dn => (dn startsWith s"$filter(")
          }
        }
        override def getRunner = {
          val runner = request.getRunner
          try { filt.apply(runner) ; runner }
          catch {
            case e :NoTestsRemainException => new Runner() {
              override def getDescription = runner.getDescription
              override def run (notifier :RunNotifier) {}
            }
          }
        }
      }
      // if(globPatterns.size() > 0) request = new SilentFilterRequest(request, new GlobFilter(settings, globPatterns));
      // if(testFilter.length() > 0) request = new SilentFilterRequest(request, new TestFilter(testFilter, ed));
      ju.run(request)
      endBetween() // JUnit might not call testRunFinished in some cases because it's awesome!
      sender.send("done", Map[String,String]())
    } catch {
      case e :Exception => e.printStackTrace(System.err)
    }
  }
}
