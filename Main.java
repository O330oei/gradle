import org.apache.commons.math3.stat.inference.MannWhitneyUTest;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Main {
    private static String projectDirPath = "/home/tcagent1/agent/work/668602365d1521fc";
    private static File projectDir = new File(projectDirPath);
    private static Map<String, String> gradleBinary = new HashMap<>();

    static {
        gradleBinary.put("baseline1",
            projectDirPath + "/intTestHomeDir/previousVersion/5.2-20181211000030+0000/gradle-5.2-20181211000030+0000/bin/gradle");
        gradleBinary.put("baseline2",
            projectDirPath + "/intTestHomeDir/previousVersion/5.2-20181211000030+0000/gradle-5.2-20181211000030+0000-2/bin/gradle");
        gradleBinary.put("current1",
            projectDirPath + "/subprojects/performance/build/integ test/bin/gradle");
        gradleBinary.put("current2",
            projectDirPath + "/subprojects/performance/build/integ test-2/bin/gradle");
    }

    private static class Experiment {
        String version;
        List<Long> results;

        public Experiment(String version, List<Long> results) {
            this.version = version;
            this.results = results;
        }

        private double[] toDoubleArray() {
            return results.stream().mapToDouble(Long::doubleValue).toArray();
        }

        private void printResult() {
            System.out.println(version + ": " + results.stream().map(s -> s + " ms").collect(Collectors.joining(", ")));
        }
    }

    private static class TwoExperiments {
        Experiment version1;
        Experiment version2;
        double confidence;

        public TwoExperiments(Experiment version1, Experiment version2) {
            this.version1 = version1;
            this.version2 = version2;
            this.confidence = 1 - new MannWhitneyUTest().mannWhitneyUTest(version1.toDoubleArray(), version2.toDoubleArray());
        }

        public void printResultsAndConfidence() {
            version1.printResult();
            version2.printResult();
            System.out.println(String.format("Confidence of %s and %s is %f", version1.version, version2.version, confidence));
        }
    }

    public static void main(String[] args) {
        List<TwoExperiments> allResults = IntStream.range(0, Integer.parseInt(System.getProperty("retryCount"))).mapToObj(i -> runASetOfExperiments()).collect(Collectors.toList());

        System.out.println("All results:");
        allResults.forEach(TwoExperiments::printResultsAndConfidence);
    }

    private static TwoExperiments runASetOfExperiments() {
        String strategy = System.getProperty("strategy");

        TwoExperiments comparison = "oneByOne".equals(strategy) ? runOneByOne() : runSetBySet();

        comparison.printResultsAndConfidence();
        return comparison;
    }

    private static TwoExperiments runSetBySet() {
        String[] versions = System.getProperty("expVersions").split(",");

        Experiment version1 = runExperiment(versions[0]);
        Experiment version2 = runExperiment(versions[1]);

        return new TwoExperiments(versions[0], versions[1]);
    }

    private static TwoExperiments runOneByOne() {
        String[] versions = System.getProperty("expVersions").split(",");

        String version1 = versions[0];
        String version2 = versions[1];

        assertTrue(!version1.equals(version2));

        prepareForExperiment(version1);
        prepareForExperiment(version2);

        doWarmUp(getExpProject(version1), args);
        doWarmUp(getExpProject(version2), args);

        List<Long> version1Results = new ArrayList<>();
        List<Long> version2Results = new ArrayList<>();

        for (int i = 0; i < Integer.parseInt(System.getProperty("runCount"))) {
            version1Results.add(measureOnce(getExpProject(version1), getExpArgs(version1), "help"));
            version2Results.add(measureOnce(getExpProject(version2), getExpArgs(version2), "help"));
        }
        stopDaemon(version1);
        stopDaemon(version2);

        return new TwoExperiments(new Experiment(version1, version1Results), new Experiment(version2, version2Results));
    }

    private static void prepareForExperiment(String version) {
        initDirectory(getGradleUserHome(version));
        deleteDirectory(getExpProject(version));

        run(projectDir, "cp", "-r",
            projectDirPath + "/subprojects/performance/build/largeJavaMultiProjectKotlinDsl",
            getExpProject(version).getAbsolutePath());
    }

    private static void stopDaemon(String version) {
        run(getExpProject(version), getExpArgs(version, "--stop"));
    }

    private static Experiment runExperiment(String version) {
        prepareForExperiment(version);

        List<String> args = getExpArgs(version, "help");
        doWarmUp(getExpProject(version), args);
        List<Long> results = doRun(getExpProject(version), args);

        stopDaemon(version);
        return new Experiment(version, results);
    }

    private static List<Long> doRun(File workdingDir, List<String> args) {
        int runCount = Integer.parseInt(System.getProperty("runCount"));
        return IntStream.range(0, runCount).mapToObj(i -> measureOnce(workdingDir, args)).collect(Collectors.toList());
    }

    private static long measureOnce(File workingDir, List<String> args) {
        long t0 = System.currentTimeMillis();
        run(workingDir, args);
        return System.currentTimeMillis() - t0;
    }

    private static List<String> getExpArgs(String version, String task) {
        return Arrays.asList(
            gradleBinary.get(version),
            "--gradle-user-home",
            getGradleUserHome(version).getAbsolutePath(),
            "--stacktrace",
            "-Dorg.gradle.jvmargs=-Xms1536m -Xmx1536m",
            task
        );
    }

    private static File getGradleUserHome(String version) {
        return new File(projectDirPath, version + "GradleUserHome");
    }

    private static File getExpProject(String version) {
        return new File(projectDirPath, version + "ExpProject");
    }

    private static void doWarmUp(File workingDir, List<String> args) {
        int warmups = Integer.parseInt(System.getProperty("warmUp"));
        IntStream.range(0, warmups).forEach(i -> run(workingDir, args));
    }

    private static void deleteDirectory(File dir) {
        if (dir.exists()) {
            run(projectDir, "rm", "-rf", dir.getAbsolutePath());
        }
    }

    private static void initDirectory(File dir) {
        deleteDirectory(dir);
        assertTrue(dir.mkdir());
    }

    private static void handleException(Exception e) {
        if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
        } else {
            throw new RuntimeException(e);
        }
    }

    private static void run(File workingDir, List<String> args) {
        try {
            int code = new ProcessBuilder(args).directory(workingDir).inheritIO().start().waitFor();
            assertTrue(code == 0);
        } catch (Exception e) {
            handleException(e);
        }
    }

    private static void run(File workingDir, String... args) {
        try {
            int code = new ProcessBuilder(args).directory(workingDir).inheritIO().start().waitFor();
            assertTrue(code == 0);
        } catch (Exception e) {
            handleException(e);
        }
    }

    private static void assertTrue(boolean value) {
        if (!value) {
            throw new IllegalStateException();
        }
    }
}