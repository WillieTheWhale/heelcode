package edu.heelcode.policy;

import java.nio.file.Files;
import java.nio.file.Path;

/** Locates the research fixtures and saved experiment evidence that ship with this checkout. */
public final class Fixtures {
  /** The SYS 301 policy Luna generated on September 18 (50/50 rule settings correct). */
  public static final String SYS301_RUN = "2026-09-18/luna-policy-01";

  /**
   * The policy-engine package directory: {@code HEELCODE_POLICY_PACKAGE}, then the JAR or classes
   * location, then the working directory. Null outside a checkout (e.g. a student build).
   */
  public static Path packageRoot() {
    var configured =
        System.getProperty("heelcode.policy.package", System.getenv("HEELCODE_POLICY_PACKAGE"));
    if (configured != null && Files.isDirectory(Path.of(configured, "fixtures")))
      return Path.of(configured).toAbsolutePath().normalize();
    try {
      var location =
          Path.of(Fixtures.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      // .../policy-engine/target/policy-engine.jar or .../policy-engine/target/classes
      var candidate = location.getParent().getParent();
      if (Files.isDirectory(candidate.resolve("fixtures"))) return candidate.normalize();
    } catch (Exception ignored) {
      // Fall through to the working directory.
    }
    var working = Path.of("").toAbsolutePath();
    return Files.isDirectory(working.resolve("fixtures")) ? working : null;
  }

  public static Path fixtures() {
    var root = packageRoot();
    return root == null ? null : root.resolve("fixtures");
  }

  public static Path experiments() {
    var root = packageRoot();
    if (root == null) return null;
    var path = root.resolve("../../docs/research/experiments").normalize();
    return Files.isDirectory(path) ? path : null;
  }

  public static Path sys301Sources() {
    var fixtures = fixtures();
    return fixtures == null ? null : fixtures.resolve("systems/course");
  }

  public static Path sys301Run() {
    var experiments = experiments();
    if (experiments == null) return null;
    var run = experiments.resolve(SYS301_RUN);
    return Files.exists(run.resolve("policy.json")) ? run : null;
  }

  /** Activates the saved SYS 301 policy against the fixture sources in {@code workspace}. */
  public static Path createDemo(Path workspace) throws Exception {
    var sources = sys301Sources();
    var run = sys301Run();
    if (sources == null || run == null)
      throw new IllegalStateException(
          "The SYS 301 fixtures and saved policy are only available inside a HeelCode checkout");
    Workspace.activate(
        workspace,
        sources,
        Json.read(run.resolve("bundle.json"), Course.class),
        Json.read(run.resolve("policy.json"), Policy.class));
    var readme = workspace.resolve("README.md");
    if (!Files.exists(readme))
      Files.writeString(
          readme,
          """
          # SYS 301 demo workspace

          Fictional course workspace activated with the Luna-generated SYS 301 policy from the
          September 18, 2026 experiment. Policy sessions and gated answers are stored under
          `.heelcode-policy/`. Nothing here is real student data.
          """);
    return workspace.toRealPath();
  }
}
