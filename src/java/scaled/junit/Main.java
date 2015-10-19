//
// Scaled JUnit Runner - integrates JUnit with Scaled's project services
// http://github.com/scaled/junit-runner/blob/master/LICENSE

package scaled.junit;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.junit.runner.*;
import org.junit.runner.manipulation.*;
import org.junit.runner.notification.*;

import scaled.prococol.*;

public class Main {

  public static void main (String[] args) throws Exception {
    Sender sender = new Sender(System.out, true);
    // read prococol messages from stdin and pass them to server
    new Receiver(System.in, new Receiver.Listener() {

      public void onMessage (String cmd, Map<String,String> args) {
        if ("test".equals(cmd)) test(args);
        else sender.send("error", map("cause", "Unknown command: " + cmd));
      }

      // captures output before, during and after test runs
      private boolean inBetween = false;
      private void startBetween () { if (!inBetween) {
        sender.startMessage("between");
        sender.startText("output");
        sender.textWriter().flush();
        inBetween = true;
      }}
      private void endBetween () { if (inBetween) {
        inBetween = false;
        sender.endText();
        sender.endMessage();
      }}

      private void test (Map<String,String> data) {
        JUnitCore ju = new JUnitCore();
        ju.addListener(new RunListener() {
          @Override public void testRunStarted (Description descrip) {
            startBetween();
          }
          @Override public void testRunFinished (Result result) {
            endBetween();
            sender.send("results", map("ran", result.getRunCount(),
                                       "ignored", result.getIgnoreCount(),
                                       "failed", result.getFailureCount(),
                                       "duration", result.getRunTime(),
                                       "success", result.wasSuccessful()));
          }
          @Override public void testStarted (Description descrip) {
            endBetween();
            sender.send("started", map("class", descrip.getClassName(),
                                       "method", descrip.getMethodName()));
            startBetween();
          }
          @Override public void testFinished (Description descrip) {
            endBetween();
            startBetween();
          }
          @Override public void testFailure (Failure failure) {
            endBetween();
            StringWriter trace = new StringWriter();
            failure.getException().printStackTrace(new PrintWriter(trace));
            Description descrip = failure.getDescription();
            sender.send("failure", map("class", descrip.getClassName(),
                                       // if we fail during a @Before/@After, this is null
                                       "method", descrip.getMethodName(),
                                       "trace", trace.toString()));
            startBetween();
          }
          @Override public void testAssumptionFailure (Failure failure) {
            testFailure(failure); // TODO: need we differentiate?
          }
          @Override public void testIgnored (Description descrip) {} // nada
        });

        try {
          String[] cpurls = get(data, "classpath", new String[0], str -> str.split("\t"));
          URL[] classpath = new URL[cpurls.length];
          int ii = 0; for (String cpurl : cpurls) classpath[ii++] = new File(cpurl).toURI().toURL();
          URLClassLoader loader = new URLClassLoader(classpath, getClass().getClassLoader());

          String[] cnames = get(data, "classes", new String[0], str -> str.split("\t"));
          Class<?>[] classes = new Class<?>[cnames.length];
          ii = 0; for (String cname : cnames) classes[ii++] = loader.loadClass(cname);

          Request request = Request.classes(classes);
          String filter = get(data, "filter", "", str -> str);
          Request frequest = (filter.equals("")) ? request : new Request() {
            @Override public Runner getRunner () {
              Runner runner = request.getRunner();
              try {
                _filt.apply(runner);
                return runner;
              } catch (NoTestsRemainException ntre) {
                return new Runner() {
                  @Override public Description getDescription () {
                    return runner.getDescription();
                  }
                  @Override public void run (RunNotifier notifier) {}
                };
              }
            }
            private Filter _filt = new Filter() {
              public String describe () { return "Selects tests named '" + filter + "'"; }
              public boolean shouldRun (Description d) {
                String dn = d.getDisplayName();
                if (dn.indexOf('(') == -1) return true; // this is a suite name, let it run
                return dn.startsWith(filter + "(");
              }
            };
          };
          // if(globPatterns.size() > 0) request = new SilentFilterRequest(request, new GlobFilter(settings, globPatterns));
          // if(testFilter.length() > 0) request = new SilentFilterRequest(request, new TestFilter(testFilter, ed));
          ju.run(frequest);
          endBetween(); // JUnit might not call testRunFinished in some cases because it's awesome!
          sender.send("done", map());
        } catch (Exception e) {
          e.printStackTrace(System.err);
        }
      }
    }).run();
  }

  private static <T> T get (Map<String,String> data, String key, T defval, Function<String,T> fn) {
    String value = data.get(key);
    return (value == null) ? defval : fn.apply(value);
  }

  private static Map<String,String> map (Object... keysValues) {
    Map<String,String> map = new HashMap<>();
    for (int ii = 0; ii < keysValues.length; ii += 2) map.put(
      String.valueOf(keysValues[ii]), String.valueOf(keysValues[ii+1]));
    return map;
  }
}
