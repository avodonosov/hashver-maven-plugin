/*
    Copyright 2020 Anton Vodonosov (avodonosov@yandex.ru).

    This file is part of hashver-maven-plugin.

    hashver-maven-plugin is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    hashver-maven-plugin is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with hashver-maven-plugin.  If not, see <https://www.gnu.org/licenses/>.
*/

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

import static pro.avodonosov.mvnhashver.Logging.LOG_PREFIX;

// TODO:
//   - in the afterProjectsRead compute hashversions again and make sure
//     corresponding property expressions have the same values.
//     (duplicates user's wait time for hashver computations, but
//     prevents mistakes).
//     How to know what extraHashData was used during the mojo call?
//     Store it in the generate target/hashversions.properties
//     with name hashverMojo.extraHashData.
//     An option to disable that - clean builds on build server won't usually
//     have stale target/ directory, no need to waste their time.
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
        loadSysPropFiles(Config.sysPropFiles(), session);
    }

    private void loadSysPropFiles(String filesSpec, MavenSession session)
        throws MavenExecutionException
    {
        for (SysPropFile fileSpec : parseSysPropFilesSpec(filesSpec)) {
            if (fileSpec.required) {
                propFileToSysRequired(session, fileSpec.file);
            } else {
                propFileToSysOptional(session, fileSpec.file);
            }
        }
    }

    static class SysPropFile {
        boolean required = true;
        String file;
    }

    static SysPropFile[] parseSysPropFilesSpec(String filesSpec)
    {
        if (null == filesSpec) {
            return new SysPropFile[0];
        }
        String[] parts = filesSpec.split(",");
        SysPropFile[] result = new SysPropFile[parts.length];
        int i = 0;
        for (String spec : parts) {
            SysPropFile elem = new SysPropFile();
            if (spec.startsWith("opt:")) {
                elem.required = false;
                elem.file = spec.substring("opt:".length());
            } else {
                elem.required = true;
                elem.file = spec;
            }
            result[i++] = elem;
        }
        return result;
    }

    private void propFileToSys(MavenSession session, File file)
            throws MavenExecutionException
    {
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            throw new MavenExecutionException(
                    "Error loading " + file, e);
        }
        logInfo("Setting system properties from " + file);
        // We need to modify the property collection already
        // assembled by maven, simply setting System.setProperty
        // was not enough - maven failed when property expression
        // was specified in dependencyManagement/dependency/version.
        Properties sessionSysProps = session.getSystemProperties();
        for (Object propName : props.keySet()) {
            logInfo(propName.toString() + "=" + props.get(propName));
            String key = propName.toString();
            String val = props.get(propName).toString();
            System.setProperty(key, val);
            sessionSysProps.put(key, val);
        }
    }

    static File resolveFile(String file, MavenSession session) {
        File f = new File(file);
        if (f.isAbsolute()) {
            return f;
        }

        String baseDir = session.getExecutionRootDirectory();
        return new File(baseDir + File.separator + file);
    }

    private void propFileToSysOptional(MavenSession session, String file)
            throws MavenExecutionException
    {
        File f = resolveFile(file, session);
        if (!f.exists()) {
            logInfo("File is absent - loading nothing: " + file);
        } else {
            propFileToSys(session, f);
        }
    }

    private void propFileToSysRequired(MavenSession session, String file)
            throws MavenExecutionException
    {
        File f = resolveFile(file, session);
        if (!f.exists()) {
            throw new MavenExecutionException(
                    "File is absent: " + file, (Throwable)null);
        }
        propFileToSys(session, f);
    }

    @Override
    public void afterProjectsRead(MavenSession session) {
        if (!Config.skipExistingArtifacts(session)) {
            return;
        }

        logInfo("Preparing to remove from the maven session the modules"
                + " whose artifacts exist.");

        ExistenceCheckMethod[] existenceChecks =
                ExistenceCheckMethod.parse(
                        Config.existenceCheckMethods(session));

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
        logger.info(LOG_PREFIX + s);
    }

    private void logInfo(String s, Throwable t) {
        logger.info(LOG_PREFIX + s, t);
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
            logInfo("Failed to resolve artifact " + req);
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

    static class Config {
        public static String existenceCheckMethods(MavenSession session) {
            return ConfigProps.existenceCheckMethods.get(session);
        }

        public static boolean skipExistingArtifacts(MavenSession session) {
            return isTrue(ConfigProps.hashverMode.getSys())
                    || isTrue(ConfigProps.skipExistingArtifacts.get(session));
        }

        public static String sysPropFiles() {
            return isTrue(ConfigProps.hashverMode.getSys())
                    ? HashVerMojo.HASHVER_FILE
                    : ConfigProps.sysPropFiles.getSys();
        }

        public static boolean isTrue(String propVal) {
            if (propVal == null) {
                return false;
            }
            if (propVal.isEmpty()) {
                return true;
            }
            return Boolean.parseBoolean(propVal);
        }
    }

    enum ConfigProps {
        hashverMode("false"),
        sysPropFiles("versions.properties"),
        skipExistingArtifacts("false"),
        existenceCheckMethods("resolve");

        public final String defaultValue;

        ConfigProps(String defaultValue) {
            this.defaultValue = defaultValue;
        }

        public String get(MavenSession session) {
            return getProp(session, name(), defaultValue);
        }

        /**
         * For properties that are accessed before session.getTopLevelProject()
         * is available.
         */
        public String getSys() {
            return System.getProperty(name(), defaultValue);
        }

        private static String getProp(MavenSession session,
                                      String name,
                                      String defaultValue)
        {
            String val = System.getProperty(name);
            if (val != null) {
                return val;
            }

            Properties prjProps = session.getTopLevelProject().getProperties();
            return prjProps.getProperty(name, defaultValue);
        }
    }
}
