//
// Scaled JUnit Runner - integrates JUnit with Scaled's project services
// http://github.com/scaled/junit-runner/blob/master/LICENSE

package scaled.junit

import java.io.{File, PrintWriter, StringWriter}
import java.net.{URL, URLClassLoader}
import java.util.{Map => JMap}
import org.junit.runner.notification.{Failure, RunListener}
import org.junit.runner.{Description, JUnitCore, Request, Result}
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

    val ju = new JUnitCore()
    ju.addListener(new RunListener() {
      override def testRunStarted (descrip :Description) {} // nada
      override def testRunFinished (result :Result) {
        sender.send("results", Map("ran" -> result.getRunCount.toString,
                                   "ignored" -> result.getIgnoreCount.toString,
                                   "failed" -> result.getFailureCount.toString,
                                   "duration" -> result.getRunTime.toString,
                                   "success" -> result.wasSuccessful.toString))
      }
      override def testStarted (descrip :Description) {
        sender.startMessage("started")
        sender.sendString("class", descrip.getClassName)
        sender.sendString("method", descrip.getMethodName)
        sender.startText("output")
        // flush the sender so that any stdout written by the test is accumulated into this
        // multiline text block; this is a little hacky, but not heinously worse than capturing
        // System.out and restoring it when the test is done
        sender.textWriter.flush()
      }
      override def testFinished (descrip :Description) {
        sender.endText()
        sender.endMessage()
      }
      override def testFailure (failure :Failure) {
        sender.endText()
        sender.endMessage()
        val trace = new StringWriter()
        failure.getException.printStackTrace(new PrintWriter(trace))
        val descrip = failure.getDescription
        sender.send("failure", Map("class" -> descrip.getClassName,
                                   "method" -> descrip.getMethodName,
                                   "trace" -> trace.toString))
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
      // if(globPatterns.size() > 0) request = new SilentFilterRequest(request, new GlobFilter(settings, globPatterns));
      // if(testFilter.length() > 0) request = new SilentFilterRequest(request, new TestFilter(testFilter, ed));
      ju.run(request)
    } catch {
      case e :Exception => e.printStackTrace(System.err)
    }
  }
}
