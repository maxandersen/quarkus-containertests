///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.6.3
//DEPS org.zeroturnaround:zt-exec:1.12
//DEPS org.slf4j:slf4j-simple:1.7.32
//JAVA 17

import static java.lang.System.err;
import static java.lang.System.out;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;
import org.zeroturnaround.exec.StartedProcess;
import org.zeroturnaround.exec.stream.LogOutputStream;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "test", mixinStandardHelpOptions = true, version = "test 0.1", description = "test made with jbang")
class test implements Callable<Integer> {

    final int PROCESS_KILLED = 143;

    @Parameters(arity="1..N", description = "The directories to use as test project", defaultValue = "postgres",split = ",")
    private List<String> testDirs;

    @Option(names = { "-v", "--verbose" }, description = "Verbose output")
    static boolean verbose;

    @Option(names = { "-t", "--timeout" }, description = "Timout in seconds", defaultValue = "60")
    int timeout;

    @Option(names = { "--dev" }, description = "Run dev tests", defaultValue="true", fallbackValue="true", negatable = true)
    boolean devTests;

    @Option(names = { "-n", "--native" }, description = "Run native build tests", defaultValue="true", fallbackValue="true", negatable = true)
    boolean nativeTests;

    @Option(names = { "-c", "--container-runtime"}, description = "Container runtime to use. Default: let Quarkus decide")
    String containerRuntime;
    public static void main(String... args) {
        int exitCode = new CommandLine(new test()).execute(args);
        System.exit(exitCode);
    }

    Map<String, String[]> testSetups = Map.of(
        "postgres", "quarkus create app -x jdbc-postgres,hibernate-orm postgres".split(" "),
        "kafka", "quarkus create app -x quarkus-smallrye-reactive-messaging-kafka kafka".split(" "),
        "kubernetes", "quarkus create app -x kubernetes kubernetes".split(" ")
    );

    @Override
    public Integer call() throws Exception {


       // cleanup();
        // docker stop `docker ps -q -f label=org.testcontainers`

        for(String dir : testDirs) {
            
            String[] cmd = testSetups.get(dir);

            Path p = Path.of(dir);
            if (!Files.exists(p)) {
                if(cmd==null) {
                    out.println("No dir nor setup command found for " + dir);
                    System.exit(200);
                }
                var process = new ProcessExecutor()
                .directory(new File("."))
                .command(cmd)
                .redirectOutput(System.out)
                .execute();
            } else if (Files.isDirectory(p)) {
                out.println("Skipping " + dir + " as it already exists");
            } else {
                out.println(dir + " is not a directory. Abort.");
                System.exit(100);
            }
        }

        Map<String, Boolean> testResults = new LinkedHashMap<>();

        for (String dir : testDirs) {

            out.println("Testing " + dir);

            if(nativeTests) testResults.put(dir + "-nativebuild", testNativeBuild(Path.of(dir)));
            if (devTests) testResults.put(dir + "-quarkusdev", testQuarkusDev(Path.of(dir)));

        }

        out.println("Test results: " + testResults);
        return 0;
    }

    private boolean testNativeBuild(Path dir) {

        String cruntime = "-Dnoopy";
        if(containerRuntime!=null) {
            cruntime = "-Dquarkus.native.container-runtime=" + containerRuntime;
        }

        return testCommand(dir, 
        List.of(
                new PatternAndMessage(
                        Pattern.compile(".*-runner: Permission denied.*"),
                        "Permission denied on runner creation",
                        false,
                        false)
                ), 
        "quarkus", "build", "--native", "-Dquarkus.native.container-build=true", cruntime, "-DskipTests"//, "-Dquarkus.native.container-runtime-options=--userns=keep-id,-u=501:20"
        );

    }

    private boolean testQuarkusDev(Path dir) throws IOException, InterruptedException, ExecutionException {
        return testCommand(dir, 
        List.of(
                new PatternAndMessage(
                        Pattern.compile(".*Creating container for image: testcontainers/ryuk:.*"),
                        "Ryuk container creation",
                        false,
                        false),
                new PatternAndMessage(
                        Pattern.compile(".*Container testcontainers/ryuk:.* started.*"),
                        "Ryuk container started",
                        false,
                        false),
                new PatternAndMessage(
                        Pattern.compile("^Caused by"),
                        "Stacktraces in output",
                        true,
                        false),
                new PatternAndMessage(
                        Pattern.compile("^.*Installed features.*"),
                        "Quarkus reached installed features'",
                        true,
                        true)), 
"quarkus","dev");
    }

    private boolean testCommand(Path dir, List<PatternAndMessage> patterns, String... command) {
        out.println("Testing " + dir + " with " + String.join(" ", command));

        

        try(var outputStream = new CustomLogOutputStream(dir.toString(), patterns)) {

            var process = new ProcessExecutor()
                    .directory(dir.toFile())
                    .command(command)
                    // .environment("TESTCONTAINERS_RYUK_DISABLED", "true")
                    .environment("QUARKUS_CONSOLE_BASIC", "true") // to avoid hiding lines
                    .redirectOutput(outputStream)
                    .start();

            outputStream.setStartedProcess(process);

            Future<ProcessResult> qdevFuture = process.getFuture();

            out.println("Waiting for command to finish within " + timeout + " seconds");
            var result = qdevFuture.get(timeout, TimeUnit.SECONDS);

            if (result.getExitValue() == PROCESS_KILLED) {
                err.println("quarkus dev killed abruptly");
            }
            err.println(outputStream.toString());
            return outputStream.passed();

        } catch (TimeoutException e) {
            err.println("quarkus dev timed out");
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
        return false;
    }
    

    static record PatternAndMessage(Pattern pattern, String message, List<String> matches, AtomicBoolean found,
            boolean breakOnMatch, boolean failOnFind) {
        PatternAndMessage(Pattern pattern, String message, boolean breakOnMatch, boolean failOnFind) {
            this(pattern, message, new ArrayList<String>(), new AtomicBoolean(false), breakOnMatch, failOnFind);
        }

        boolean handleKill(String line) {
            if (pattern.matcher(line).find()) {
                found.set(true);
                matches.add(line);
                if (breakOnMatch) {
                    err.println("Kill on " + line);
                    return true;
                }
            }
            return false;
        }
    }

    static class CustomLogOutputStream extends LogOutputStream {

        private volatile StartedProcess startedProcess;
        private String prefix;

        private List<test.PatternAndMessage> patterns;
        private Writer logwriter;

        CustomLogOutputStream(String prefix, List<PatternAndMessage> patterns) {

            this.patterns = patterns;
            this.prefix = prefix;
        }

        void setStartedProcess(StartedProcess startedProcess) {
            this.startedProcess = startedProcess;
        }

        @Override
        protected void processLine(String line) {

            if (verbose)
                out.println(String.format("%s: %s", prefix, line));
            try {
                if(this.logwriter==null) this.logwriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(prefix + ".log", true), "UTF-8"));
                logwriter.write(line);
                logwriter.write("\n");
                logwriter.flush();
            } catch (IOException e) {
                System.err.println("Could not write to log file " + prefix + ".log");
                e.printStackTrace();
            }
            
            
            boolean kill = false;
            for (var pattern : patterns) {
                if (pattern.handleKill(line)) {
                    kill = true; // destroy process when everyone has had a chance to handle the line
                }
            }
            if (kill) {
                startedProcess.getProcess().destroy();
            }
        }

        @Override
        public void close() throws IOException {
            super.close();
            if(logwriter!=null) logwriter.close();
        }

        boolean passed(PatternAndMessage pattern) {
            boolean passed = true;
            boolean found = pattern.found.get();
            if(pattern.failOnFind && found) {
                passed = false;
            } 
            return passed;
        }

        boolean passed() {
            for (PatternAndMessage pattern : patterns) {
                if (!passed(pattern)) {
                    return false;
                }
            }
            return true;
        }

        public String toString() {
            StringBuffer buf = new StringBuffer();

            for (PatternAndMessage pattern : patterns) {
                String status = passed(pattern) ? "PASSED" : "FAILED";
                buf.append(prefix + ": " + pattern.message + ": "
                        + status + "  (" + pattern.matches + ")");
                buf.append("\n");
            }

            return buf.toString();
        }
    }

}
