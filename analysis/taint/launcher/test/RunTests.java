import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Runs the launcher's JUnit 5 tests through the platform launcher API; exits non-zero on any
 * failure or when discovery finds no tests at all. */
public class RunTests {
    public static void main(String[] argv) {
        Launcher launcher = LauncherFactory.create();
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectPackage("vkr.plugin"))
                .build();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(request, listener);
        var summary = listener.getSummary();
        System.out.printf("tests: found=%d, succeeded=%d, failed=%d, skipped=%d, aborted=%d%n",
                summary.getTestsFoundCount(), summary.getTestsSucceededCount(),
                summary.getTestsFailedCount(), summary.getTestsSkippedCount(),
                summary.getTestsAbortedCount());
        for (var failure : summary.getFailures()) {
            System.out.println("FAILURE: " + failure.getTestIdentifier().getDisplayName());
            System.out.println("    " + String.valueOf(failure.getException()).replace("\n", "\n    "));
        }
        if (summary.getTestsFoundCount() == 0) {
            System.out.println("discovery found no tests");
            System.exit(2);
        }
        if (summary.getTotalFailureCount() > 0) {
            System.exit(1);
        }
    }
}
