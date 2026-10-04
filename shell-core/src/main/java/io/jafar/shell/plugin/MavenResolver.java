package io.jafar.shell.plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionContext;
import org.eclipse.aether.collection.DependencySelector;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;
import org.eclipse.aether.util.graph.transformer.JavaScopeDeriver;
import org.eclipse.aether.util.graph.transformer.JavaScopeSelector;
import org.eclipse.aether.util.graph.transformer.NearestVersionSelector;
import org.eclipse.aether.util.graph.transformer.SimpleOptionalitySelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wrapper for Apache Maven Resolver to download plugin artifacts from Maven repositories.
 *
 * <p>Supports Maven Central, Sonatype snapshots, and local Maven repository (~/.m2/repository).
 */
final class MavenResolver {
  private static final Logger log = LoggerFactory.getLogger(MavenResolver.class);
  private static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2/";
  private static final String SONATYPE_SNAPSHOTS =
      "https://oss.sonatype.org/content/repositories/snapshots/";

  private final RepositorySystem system;
  private final List<RemoteRepository> repositories;
  private final Path localRepoPath;

  MavenResolver() {
    this.system = newRepositorySystem();
    this.localRepoPath = getLocalRepositoryPath();
    this.repositories = createRepositories();
  }

  /**
   * Resolve an artifact and download it to the local Maven repository.
   *
   * @param groupId Maven groupId
   * @param artifactId Maven artifactId
   * @param version Version string
   * @return Path to the downloaded JAR file
   * @throws PluginInstallException if resolution fails
   */
  Path resolveArtifact(String groupId, String artifactId, String version)
      throws PluginInstallException {
    // First, check if artifact exists in local repository to avoid remote checks
    Path localArtifactPath = buildLocalArtifactPath(groupId, artifactId, version);
    if (Files.exists(localArtifactPath)) {
      log.debug("Artifact found in local Maven repository: {}", localArtifactPath);
      return localArtifactPath;
    }

    log.debug(
        "Artifact not in local repository, resolving from remote: {}:{}:{}",
        groupId,
        artifactId,
        version);
    try {
      DefaultRepositorySystemSession session = newSession(system);

      Artifact artifact = new DefaultArtifact(groupId + ":" + artifactId + ":jar:" + version);

      ArtifactRequest request = new ArtifactRequest();
      request.setArtifact(artifact);
      request.setRepositories(repositories);

      ArtifactResult result = system.resolveArtifact(session, request);
      return result.getArtifact().getFile().toPath();
    } catch (ArtifactResolutionException e) {
      throw new PluginInstallException(
          "Failed to resolve artifact: " + groupId + ":" + artifactId + ":" + version, e);
    }
  }

  /**
   * Build the path to an artifact in the local Maven repository.
   *
   * @param groupId Maven groupId
   * @param artifactId Maven artifactId
   * @param version Version string
   * @return Path to the artifact JAR in local repository
   */
  private Path buildLocalArtifactPath(String groupId, String artifactId, String version) {
    // Maven local repository structure: groupId/artifactId/version/artifactId-version.jar
    String groupPath = groupId.replace('.', '/');
    String jarName = artifactId + "-" + version + ".jar";
    return localRepoPath.resolve(groupPath).resolve(artifactId).resolve(version).resolve(jarName);
  }

  /**
   * Resolve an artifact's checksum file (.sha256).
   *
   * @param groupId Maven groupId
   * @param artifactId Maven artifactId
   * @param version Version string
   * @return Path to the downloaded checksum file, or null if not available
   */
  Path resolveChecksum(String groupId, String artifactId, String version) {
    try {
      DefaultRepositorySystemSession session = newSession(system);

      // Maven uses .sha1 by default, but we want .sha256
      Artifact checksumArtifact =
          new DefaultArtifact(groupId + ":" + artifactId + ":jar.sha256:" + version);

      ArtifactRequest request = new ArtifactRequest();
      request.setArtifact(checksumArtifact);
      request.setRepositories(repositories);

      ArtifactResult result = system.resolveArtifact(session, request);
      return result.getArtifact().getFile().toPath();
    } catch (ArtifactResolutionException e) {
      // Checksum files may not exist - return null
      return null;
    }
  }

  /** The root jar of a resolution, plus every dependency artifact it needs on the classpath. */
  record ResolvedPlugin(Path rootJar, List<Artifact> dependencies) {}

  /**
   * Resolve an artifact together with the jars its POM declares as compile or runtime dependencies,
   * Maven-style: scopes are filtered (test/provided/system dependencies are not copied into plugin
   * storage) and version conflicts resolve nearest-wins.
   *
   * <p>Resolution always runs, even when the root jar is already in the local repository: its
   * dependencies may well not be.
   *
   * @param groupId Maven groupId
   * @param artifactId Maven artifactId
   * @param version Version string
   * @return the root jar path and the dependency artifacts, each carrying its own file path
   * @throws PluginInstallException if resolution fails
   */
  ResolvedPlugin resolveWithDependencies(String groupId, String artifactId, String version)
      throws PluginInstallException {
    try {
      DefaultRepositorySystemSession session = newSession(system);
      // The bare MavenRepositorySystemUtils session applies no scope filtering and no conflict
      // resolution: collecting every scope verbatim would copy test jars and duplicate versions
      // into plugin storage, so both are wired explicitly, mirroring what Maven configures.
      session.setDependencySelector(new CompileRuntimeDependencySelector());
      session.setDependencyGraphTransformer(
          new ConflictResolver(
              new NearestVersionSelector(),
              new JavaScopeSelector(),
              new SimpleOptionalitySelector(),
              new JavaScopeDeriver()));

      Artifact artifact = new DefaultArtifact(groupId + ":" + artifactId + ":jar:" + version);
      CollectRequest collect = new CollectRequest();
      collect.setRoot(new Dependency(artifact, "compile"));
      collect.setRepositories(repositories);
      DependencyRequest request = new DependencyRequest(collect, null);

      DependencyNode root = system.resolveDependencies(session, request).getRoot();
      if (root == null || root.getArtifact() == null) {
        throw new PluginInstallException(
            "Failed to resolve artifact: " + groupId + ":" + artifactId + ":" + version);
      }
      List<Artifact> deps = new ArrayList<>();
      collectJarDependencies(root, deps, new HashSet<>());
      return new ResolvedPlugin(root.getArtifact().getFile().toPath(), List.copyOf(deps));
    } catch (DependencyResolutionException e) {
      throw new PluginInstallException(
          "Failed to resolve artifact with dependencies: "
              + groupId
              + ":"
              + artifactId
              + ":"
              + version,
          e);
    }
  }

  /** Selects compile-runtime dependencies — what a published POM means when Maven builds it. */
  private static final class CompileRuntimeDependencySelector implements DependencySelector {

    @Override
    public boolean selectDependency(Dependency dependency) {
      String scope = dependency.getScope();
      // Scopes that were never mediable would have no mediable transitive consequences: a
      // missing scope defaults to compile, and null means the dependency has no declared scope.
      return scope == null
          || switch (scope) {
            case "", "compile", "runtime" -> true;
            default -> false;
          };
    }

    @Override
    public DependencySelector deriveChildSelector(DependencyCollectionContext context) {
      return this;
    }
  }

  /** Collect the resolved jar artifacts below {@code node}; the root's own jar is excluded. */
  private void collectJarDependencies(DependencyNode node, List<Artifact> out, Set<String> seen) {
    for (DependencyNode child : node.getChildren()) {
      Dependency dependency = child.getDependency();
      if (dependency == null || dependency.isOptional()) {
        continue;
      }
      Artifact dep = child.getArtifact();
      if (dep == null || !"jar".equals(dep.getExtension())) {
        // Type poms / BOM imports are scope and version carriers, not classpath entries
        continue;
      }
      if (seen.add(dep.getGroupId() + ":" + dep.getArtifactId() + ":" + dep.getBaseVersion())) {
        out.add(dep);
      }
      collectJarDependencies(child, out, seen);
    }
  }

  /** Create the repository system. */
  private RepositorySystem newRepositorySystem() {
    DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
    locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
    locator.addService(TransporterFactory.class, HttpTransporterFactory.class);

    return locator.getService(RepositorySystem.class);
  }

  /** Create a new session for artifact resolution. */
  private DefaultRepositorySystemSession newSession(RepositorySystem system) {
    DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();

    LocalRepository localRepo = new LocalRepository(localRepoPath.toFile());
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));

    // Disable checksums from remote - we'll verify manually
    session.setChecksumPolicy(null);

    // Set aggressive timeouts to prevent blocking (5 seconds)
    session.setConfigProperty("aether.connector.connectTimeout", 5000);
    session.setConfigProperty("aether.connector.requestTimeout", 5000);
    session.setConfigProperty("aether.connector.http.connectionMaxTtl", 5);

    // Prefer local repository - don't check for updates from remote
    session.setOffline(false); // Allow remote if artifact not found locally
    session.setUpdatePolicy(org.eclipse.aether.repository.RepositoryPolicy.UPDATE_POLICY_NEVER);

    return session;
  }

  /** Get the local Maven repository path (~/.m2/repository). */
  private Path getLocalRepositoryPath() {
    String userHome = System.getProperty("user.home");
    return Paths.get(userHome, ".m2", "repository");
  }

  /** Create list of remote repositories to search. */
  private List<RemoteRepository> createRepositories() {
    List<RemoteRepository> repos = new ArrayList<>();

    repos.add(
        new RemoteRepository.Builder("central", "default", MAVEN_CENTRAL)
            .setSnapshotPolicy(
                new org.eclipse.aether.repository.RepositoryPolicy(
                    false,
                    org.eclipse.aether.repository.RepositoryPolicy.UPDATE_POLICY_DAILY,
                    org.eclipse.aether.repository.RepositoryPolicy.CHECKSUM_POLICY_WARN))
            .build());

    repos.add(
        new RemoteRepository.Builder("sonatype-snapshots", "default", SONATYPE_SNAPSHOTS)
            .setSnapshotPolicy(
                new org.eclipse.aether.repository.RepositoryPolicy(
                    true,
                    org.eclipse.aether.repository.RepositoryPolicy.UPDATE_POLICY_ALWAYS,
                    org.eclipse.aether.repository.RepositoryPolicy.CHECKSUM_POLICY_WARN))
            .build());

    return repos;
  }
}
