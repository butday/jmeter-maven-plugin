package com.lazerycode.jmeter.testrunner;

import com.lazerycode.jmeter.IncludesComparator;
import com.lazerycode.jmeter.JMeterMojo;
import com.lazerycode.jmeter.UtilityFunctions;
import com.lazerycode.jmeter.configuration.JMeterArgumentsArray;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.jmeter.JMeter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.tools.ant.DirectoryScanner;

import java.io.*;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TestManager extends JMeterMojo {

    private JMeterArgumentsArray testArgs;
    private File jmeterLog;
    private File logsDir;
    private File srcDir;
    private List<String> jMeterTestFiles;
    private List<String> excludeJMeterTestFiles;
    private boolean suppressJMeterOutput;
    private boolean remoteStop = false;
    private boolean remoteStartAll = false;
    private boolean remoteStartAndStopOnce = true;
    private String remoteStart = null;
    private int exitCheckPause = 2000;

    public TestManager(JMeterArgumentsArray testArgs, File logsDir, File srcDir, List<String> testFiles, List<String> excludeTestFiles, boolean suppressJMeterOutput) {
        this.testArgs = testArgs;
        this.logsDir = logsDir;
        this.srcDir = srcDir;
        this.jMeterTestFiles = testFiles;
        this.excludeJMeterTestFiles = excludeTestFiles;
        this.suppressJMeterOutput = suppressJMeterOutput;
    }

    public void setRemoteStartOptions(boolean remoteStop, boolean remoteStartAll, boolean remoteStartAndStopOnce, String remoteStart) {
        this.remoteStop = remoteStop;
        this.remoteStartAll = remoteStartAll;
        this.remoteStartAndStopOnce = remoteStartAndStopOnce;
        if (UtilityFunctions.isNotSet(remoteStart)) return;
        this.remoteStart = remoteStart;
    }

    public void setExitCheckPause(int value) {
        if (value < 2000) {
            getLog().warn("Minimum value for jmeter.exit.check.pause is 2000 (2 Seconds), setting minimum value.");
            return;
        }
        this.exitCheckPause = value;
    }

    public List<String> executeTests() throws MojoExecutionException {
        List<String> tests = generateTestList();
        List<String> results = new ArrayList<String>();
        for (String file : tests) {
            if (this.remoteStartAndStopOnce == false || tests.get(tests.size() - 1).equals(file)) {
                testArgs.setRemoteStop(this.remoteStop);
            }
            if (this.remoteStartAndStopOnce == false || tests.get(0).equals(file)) {
                testArgs.setRemoteStartAll(this.remoteStartAll);
                testArgs.setRemoteStart(this.remoteStart);
            }
            results.add(executeSingleTest(new File(srcDir, file)));
        }
        return results;
    }

    /**
     * Executes a single JMeter test by building up a list of command line
     * parameters to pass to JMeter.start().
     *
     * @param test JMeter test XML
     * @return the report file names.
     * @throws org.apache.maven.plugin.MojoExecutionException
     *          Exception
     */
    private String executeSingleTest(File test) throws MojoExecutionException {

        try {
            getLog().info(" ");
            testArgs.setTestFile(test);
            //Delete results file if it already exists
            new File(testArgs.getResultsFileName()).delete();
            if (getLog().isDebugEnabled()) {
                getLog().debug("JMeter is called with the following command line arguments: " + UtilityFunctions.humanReadableCommandLineOutput(testArgs.buildArgumentsArray()));
            }

            // This mess is necessary because JMeter likes to use System.exit.
            // We need to trap the exit call.

            //TODO Investigate the use of a listener here (Looks like JMeter reports startup and shutdown to a listener when it finishes a test...
            SecurityManager oldManager = System.getSecurityManager();
            System.setSecurityManager(new SecurityManager() {

                @Override
                public void checkExit(int status) {
                    throw new ExitException(status);
                }

                @Override
                public void checkPermission(Permission perm, Object context) {
                }

                @Override
                public void checkPermission(Permission perm) {
                }
            });
            Thread.UncaughtExceptionHandler oldHandler = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {

                public void uncaughtException(Thread t, Throwable e) {
                    if (e instanceof ExitException && ((ExitException) e).getCode() == 0) {
                        return; // Ignore
                    }
                    getLog().error("Error in thread " + t.getName());
                }
            });
            PrintStream originalOut = System.out;
            try {
                // This mess is necessary because the only way to know when
                // JMeter is done is to wait for its test end message!                
                setJMeterLogFile(test.getName() + ".log");
                getLog().info("Executing test: " + test.getName());
                //Suppress JMeter's annoying System.out messages
                if (suppressJMeterOutput) System.setOut(new PrintStream(new NullOutputStream()));
                new JMeter().start(testArgs.buildArgumentsArray());
                BufferedReader in = new BufferedReader(new FileReader(jmeterLog));
                while (!checkForEndOfTest(in)) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            } catch (ExitException e) {
                if (e.getCode() != 0) {
                    throw new MojoExecutionException("Test failed", e);
                }
            } finally {
                try {
                    //Wait for JMeter to clean up threads.
                    Thread.sleep(this.exitCheckPause);
                } catch (InterruptedException e) {
                }
                System.setSecurityManager(oldManager);
                Thread.setDefaultUncaughtExceptionHandler(oldHandler);
                System.setOut(originalOut);
                getLog().info("Completed Test: " + test.getName());
            }
            return testArgs.getResultsFileName();
        } catch (IOException e) {
            throw new MojoExecutionException("Can't execute test", e);
        }
    }

    /**
     * Check JMeter logfile (provided as a BufferedReader) for End message.
     *
     * @param in JMeter logfile
     * @return true if test ended
     * @throws MojoExecutionException exception
     */
    private boolean checkForEndOfTest(BufferedReader in) throws MojoExecutionException {
        boolean testEnded = false;
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.contains("Test has ended")) {
                    testEnded = true;
                    break;
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Can't read log file", e);
        }
        return testEnded;
    }

    /**
     * Create the jmeter.log file and set the log_file system property for JMeter to pick up
     *
     * @param value
     */
    private void setJMeterLogFile(String value) {
        this.jmeterLog = new File(this.logsDir + File.separator + value);
        System.setProperty("log_file", this.jmeterLog.getAbsolutePath());
    }

    private List<String> generateTestList() {
        List<String> jmeterTestFiles = new ArrayList<String>();
        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir(this.srcDir);
        scanner.setIncludes(this.jMeterTestFiles == null ? new String[]{"**/*.jmx"} : this.jMeterTestFiles.toArray(new String[jmeterTestFiles.size()]));
        if (this.excludeJMeterTestFiles != null) {
            scanner.setExcludes(this.excludeJMeterTestFiles.toArray(new String[excludeJMeterTestFiles.size()]));
        }
        scanner.scan();
        final List<String> includedFiles = Arrays.asList(scanner.getIncludedFiles());
        Collections.sort(includedFiles, new IncludesComparator(this.jMeterTestFiles));
        jmeterTestFiles.addAll(includedFiles);
        return jmeterTestFiles;
    }
}