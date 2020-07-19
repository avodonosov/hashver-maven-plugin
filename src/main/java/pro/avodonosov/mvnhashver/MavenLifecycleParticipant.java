package pro.avodonosov.mvnhashver;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.impl.ArtifactResolver;
import org.eclipse.aether.repository.LocalArtifactRequest;
import org.eclipse.aether.repository.LocalArtifactResult;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Collectors;

// TODO:
//   - in the afterProjectsRead compute hashversions again and make sure
//     corresponding property expressions have the same values.
//     (duplicates user's wait time for hashver computations, but
//     prevents mistakes).
//     Option to disable that - clean CI builds won't usually have stale
//     target/ directory, no need to waste their time.
//   - help
//   - by default don't skip modules if the user has specified an explicit
//     list of projects (-pl)? (gitflow-incremental-builder adopted
//     this mode).
@Singleton
@Named
@Component(
        role = AbstractMavenLifecycleParticipant.class,
        hint = "hashver-extension")
public class MavenLifecycleParticipant
        extends AbstractMavenLifecycleParticipant
{

    @Requirement
    @Inject
    private Logger logger;

    @Inject
    ArtifactResolver artifactResolver;

    @Override
    public void afterSessionStart(MavenSession session) throws MavenExecutionException {
        super.afterSessionStart(session);
        String baseDir = session.getExecutionRootDirectory();
        propFileToSysIfExists(baseDir + "/versions.properties");
        propFileToSysIfExists(baseDir + "/target/hashversions.properties");
    }

    private void propFileToSysIfExists(String file)
            throws MavenExecutionException
    {
        File f = new File(file);
        if (!f.exists()) {
            logger.info("File is absent - loading nothing: " + file);
        } else {
            Properties props = new Properties();
            try (InputStream in = new FileInputStream(f)) {
                props.load(in);
            } catch (IOException e) {
                throw new MavenExecutionException(
                        "Error loading " + file, e);
            }
            logInfo("Setting system properties from " + file);
            for (Object propName : props.keySet()) {
                logInfo(propName.toString() + "=" + props.get(propName));
                System.setProperty(
                        propName.toString(),
                        props.get(propName).toString());
            }
        }
    }

    @Override
    public void afterProjectsRead(MavenSession session) {
         if (!ConfigProps.skipExistingArtifacts.isTrue(session)) {
            return;
        }

        logInfo("Preparing to remove from the maven session the modules"
                + " whose artifacts exist.");

        ExistenceCheckMethod[] existenceChecks =
                ExistenceCheckMethod.parse(
                        ConfigProps.existenceCheckMethod.get(session));

//        logInfo("Project Repositories:");
//        for (MavenProject prj : session.getProjects()) {
//            logInfo("     " + prj.getArtifactId()
//                    + ",\n          getDistributionManagementArtifactRepository(): "
//                    + prj.getDistributionManagementArtifactRepository()
//                    + "/n          getRemoteArtifactRepositories(): "
//                    + prj.getRemoteArtifactRepositories()
//                    + "/n          getRemoteProjectRepositories(): "
//                    + prj.getRemoteProjectRepositories());
//        }

        // TODO: detailed logging about artifact search and skipping
        session.setProjects(
            session.getProjects().stream()
                    .filter(prj -> 
                            "pom".equals(prj.getPackaging()) 
                                || ! artifactExists(existenceChecks,
                                                    session,
                                                    prj))
                    .collect(Collectors.toList()));
    }

    private void logInfo(String s) {
        logger.info("[HASHVER] " + s);
    }

    private void logInfo(String s, Throwable t) {
        logger.info("[HASHVER] " + s, t);
    }

    private static String getProp(MavenSession session,
                                  String name,
                                  String defaultValue)
    {
        String val = System.getProperty(name);
        if (val != null) {
            return val;
        }
        Properties projectProps = session.getTopLevelProject().getProperties();
        return projectProps.getProperty(name, defaultValue);
    }

    interface ExistenceCheck {
        boolean artifactExists(MavenSession session, MavenProject project);
    }
    
    ExistenceCheck implementation(ExistenceCheckMethod method) {
        switch (method) {
            case resolve:
                return this::canResolveArtifact;
            case local:
                return this::localArtifactExists;
            case httpHead:
                return this::canHttpHeadArtifact;
            default:
                throw new RuntimeException(
                        "Unexpected artifact existence check method: " + method);
        }
    }

    boolean artifactExists(ExistenceCheckMethod[] methods,
                           MavenSession session,
                           MavenProject prj)
    {
        for (ExistenceCheckMethod method : methods) {
            if (implementation(method).artifactExists(session, prj)) {
                return true;
            }
        }
        return false;
    }

    private static DefaultArtifact aetherArtifact
            (org.apache.maven.artifact.Artifact  a)
    {
        return new DefaultArtifact(
                a.getGroupId(),
                a.getArtifactId(),
                a.getClassifier(),
                // Is mavenArtifact.getType() == aetherArtifact.getExtension?
                // (It works, but is that correct or just a coincidence?)
                a.getType(), 
                a.getVersion());
    }
    
    boolean canResolveArtifact(MavenSession session, MavenProject prj) {
        logInfo("resolve-checking existence of " + prj.getArtifact());
        ArtifactRequest req = new ArtifactRequest(
                aetherArtifact(prj.getArtifact()),
                prj.getRemoteProjectRepositories(),
                null);
        
        try {
            ArtifactResult result = artifactResolver.resolveArtifact(
                    session.getRepositorySession(),
                    req);
            logInfo(
                    "Resolution result '" + result.isResolved()
                    + "' for " + prj.getArtifact());
            return result.isResolved();
        } catch (ArtifactResolutionException e) {
            logInfo("Failed to resolve artifact " + req, e);
            return false;
        }
    }

    boolean localArtifactExists(MavenSession session, MavenProject prj) {
        RepositorySystemSession repoSession = session.getRepositorySession();
        LocalRepositoryManager lrm = repoSession.getLocalRepositoryManager();
        LocalArtifactResult result = lrm.find(
                repoSession,
                new LocalArtifactRequest(
                        aetherArtifact(prj.getArtifact()),
                        null,
                        null));
        logInfo("localArtifactExists: " + result.isAvailable()
                + " for " + prj.getArtifact());
        return result.isAvailable();
    }

    /** Joins baseUrl and subPath, making sure exactly one '/' separates them.
     */
    static String subUrl(String baseUrl, String subPath) {
        if (baseUrl.endsWith("/")) {
            if (subPath.startsWith("/")) {
                return baseUrl + subPath.substring(1);
            } else {
                return baseUrl + subPath;    
            }
        } else {
            if (subPath.startsWith("/")) {
                return baseUrl + subPath;
            } else {
                return baseUrl + "/" + subPath;
            }
        }
    }
    
    boolean canHttpHeadArtifact(MavenSession session, MavenProject prj) {
        logInfo("http-head-checking existence of " + prj.getArtifact());
        for (RemoteRepository repo : prj.getRemoteProjectRepositories()) {
            //repo.

            DefaultRepositoryLayout layout = new DefaultRepositoryLayout();
            URI artifactUri = URI.create(subUrl(
                    repo.getUrl(), 
                    layout.pathOf(prj.getArtifact())));
            try {
                HttpURLConnection connection =
                        (HttpURLConnection)artifactUri.toURL().openConnection();
                connection.setRequestMethod("HEAD");
                connection.connect();
                boolean exists = connection.getResponseCode() == 200;
                logInfo("HTTP HEAD " + (exists ? "successful" : "failed")
                            + " (" + connection.getResponseCode()
                            + ") for " + artifactUri);
                if (exists) {
                    return true;
                }
            } catch (IOException e) {
                logInfo("Failed to perform HTTP HEAD for "
                    + artifactUri + " : " + e.getMessage());
            }
        }
        return false;
    }

    enum ExistenceCheckMethod {
        resolve,
        local,
        httpHead;
        
        public static ExistenceCheckMethod[] parse(String methods) {
            String[] names = methods.split(",");
            return Arrays.stream(names)
                    .map(ExistenceCheckMethod::valueOf)
                    .toArray(ExistenceCheckMethod[]::new);
        }
    }

    enum ConfigProps {
        skipExistingArtifacts("false"),
        existenceCheckMethod("resolve");

        public final String defaultValue;

        ConfigProps(String defaultValue) {
            this.defaultValue = defaultValue;
        }

        public boolean isTrue(MavenSession session) {
            String val = get(session);
            if (val == null) {
                return false;
            }
            if (val.isEmpty()) {
                return true;
            }
            return Boolean.parseBoolean(val);
        }
        
        public String get(MavenSession session) {
            return getProp(session, name(), defaultValue);
        }

    }
}
