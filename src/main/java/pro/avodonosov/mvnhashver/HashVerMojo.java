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

import org.apache.commons.text.StringEscapeUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.SerializingDependencyNodeVisitor;

import javax.inject.Inject;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static java.nio.charset.StandardCharsets.UTF_8;
import static pro.avodonosov.mvnhashver.HashVerMojo.ExtraProperties.hashVerSnapshotDependencyMode;
import static pro.avodonosov.mvnhashver.HashVerMojo.ExtraProperties.hashverAncestorPomsForRelaxedHashing;
import static pro.avodonosov.mvnhashver.HashVerMojo.ExtraProperties.hashverAncestorPomsIgnoreErrors;
import static pro.avodonosov.mvnhashver.HashVerMojo.ExtraProperties.hashverDigestSkip;
import static pro.avodonosov.mvnhashver.Logging.LOG_PREFIX;
import static pro.avodonosov.mvnhashver.Utils.saveToFile;

// TODO: Investigate the "Downloading " message for reactor modules
//       of maven-wagon project during the "hashver" mojo execution
//       (seems happening only for the dependency-managed modules):
//       >
//       >    Downloading: https://repo.maven.apache.org/maven2/org/apache/maven/wagon/wagon-http-shared/4y2Na53WvxoBMoDdnku6ZIN61UM.P87Jh9SiFF__5rM-TAGdYaFzv6U/wagon-http-shared-4y2Na53WvxoBMoDdnku6ZIN61UM.P87Jh9SiFF__5rM-TAGdYaFzv6U.jar
//       >
@Mojo(name = "hashver", aggregator = true)
public class HashVerMojo extends AbstractMojo {

    public static final String HASHVER_PROP_FILE = "target/hashversions.properties";
    public static final String HASHVER_JSON_FILE = "target/hashversions.json";

    public static final String DIGEST_ALGO = "SHA-1";

    @Parameter(defaultValue = "false", property = "includeGroupId")
    boolean includeGroupId;

    // TODO: Support property expressions in the extraHashData?
    // TODO: Inject some values into the extraHashData automatically?
    @Parameter(property = "extraHashData")
    String extraHashData;

    /**
     * The dependency tree builder to use.
     */
    @Component(hint = "default")
    private DependencyGraphBuilder dependencyGraphBuilder;

    @Inject
    MavenSession mavenSession;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        executeImpl(mavenSession, includeGroupId, extraHashData);
    }

    protected Map<String, String> executeImpl(MavenSession mavenSession,
                                              boolean includeGroupId,
                                              String extraHashData)
            throws MojoExecutionException, MojoFailureException
    {
        Map<String, String> hashVers = computeHashVers(mavenSession,
                                                       includeGroupId,
                                                       extraHashData);

        logInfo("HashVers computed: " + hashVers.size());
        ArrayList<String> keys = new ArrayList<>(hashVers.keySet());
        Collections.sort(keys);
        for (String prjKey : keys) {
            logInfo(prjKey + "=" + hashVers.get(prjKey));
        }

        try {
            storeHashVers(hashVers);
        } catch (IOException e) {
            throw new MojoExecutionException("Error saving hashVers", e);
        }

        return hashVers;
    }

    protected Map<String, String> computeHashVers(MavenSession mavenSession,
                                                  boolean includeGroupId,
                                                  String extraHashData)
            throws MojoExecutionException
    {
        Map<String, String> ownHashByArtifact = new HashMap<>();
        for (MavenProject prj : mavenSession.getProjects()) {
            try {
                ownHashByArtifact.put(
                        ArtifactUtils.key(prj.getArtifact()),
                        ownHash(prj, extraHashData));
            } catch (IOException e) {
                throw new MojoExecutionException(
                        "Error calculating module own hash: " + prj.getName(),
                        e);
            }
        }

        Map<String, String> hashVers = new HashMap<>();
        for (MavenProject prj : mavenSession.getProjects()) {
            try {
                hashVers.put(hashVerKey(prj, includeGroupId),
                             fullHash(prj,
                                      mavenSession,
                                      dependencyGraphBuilder,
                                      ownHashByArtifact,
                                      extraHashData));
            } catch (DependencyGraphBuilderException | IOException e) {
                throw new MojoExecutionException(
                        "prjVersion() failed for " + prj.getName(),
                        e);
            }
        }

        return hashVers;
    }

    protected void logInfo(String msg) {
        getLog().info(LOG_PREFIX + msg);
    }

    protected void logDebug(String msg) {
        getLog().debug(LOG_PREFIX + msg);
    }

    protected void logWarn(String msg) {
        getLog().warn(LOG_PREFIX + msg);
    }

    protected String hashVerKey(MavenProject prj, boolean includeGroupId) {
        return includeGroupId
                ? prj.getGroupId() + "." + prj.getArtifactId() + ".version"
                : prj.getArtifactId() + ".version";
    }

    private void storeHashVers(Map<String, String> hashVers)
            throws IOException
    {
        storeHashVerProps(hashVers, HASHVER_PROP_FILE);
        storeHashVerJson(hashVers, HASHVER_JSON_FILE);
        //storeMavenConfig(hashVers, ".mvn/maven.config");
        //storeMvnEx(hashVers, "mvnex.sh");
    }
    
    private static List<String> sortedKeys(Map<String, ?> hashVers) {
        ArrayList<String> keys = new ArrayList<>(hashVers.keySet());
        keys.sort(String::compareTo);
        return keys;
    }
    
    private static void ensureParentDirExists(String file) {
        File parentDir = new File(file).getParentFile();
        if (parentDir != null) {
            // parentDir can be null when the file
            // doesn't specify parent (meaning a file in the current directory)

            parentDir.mkdirs();
        }
    }

    private static BufferedWriter openWriter(String file) throws IOException {
        ensureParentDirExists(file);
        return new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), UTF_8));
    }

    // When searching for a way to pass hashversions to system properties.
    // This was and idea of a wrapper scrip for `mvn`.
    private void storeMvnEx(Map<String, String> hashVers, String file)
            throws IOException
    {
        try (Writer out = openWriter(file)) {
            out.write("#!/bin/sh\n");
            out.write("mvn");
            for (String key : sortedKeys(hashVers)) {
                out.write(" ");
                out.write("-D");
                out.write(key);
                out.write("=");
                out.write(hashVers.get(key));
            }
            out.write(" \"$@\"");
        }
        logInfo("Saved hasVers to " + file);
    }

    // When searching for a way to pass hashversions to system properties.
    // Experimenting with .mvn/maven.config. In addition it was planned
    // to use .mvn/maven.config.head where people who already have
    // something in their maven.config can move that content.
    private void storeMavenConfig(Map<String, String> hashVers,
                                  String file)
            throws IOException
    {
        ArrayList<String> keys = new ArrayList<>(hashVers.keySet());
        keys.sort(String::compareTo);
        try (BufferedWriter out = openWriter(file)) {
            for (String key : sortedKeys(hashVers)) {
                out.write("-D");
                out.write(key);
                out.write("=");
                out.write(hashVers.get(key));
                out.newLine();
            }
        }
        logInfo("Saved hasVers to " + file);
    }

    private void storeHashVerProps(Map<String, String> hashVers,
                                   String file)
            throws IOException
    {
        Properties props = new Properties() {
            // Store properties in alphabetical order.
            // Here we rely on the internal details of the Properties::store
            // method, which saves properties in the order as
            // they are enumerated by the Properties:keys().
            @Override
            public synchronized Enumeration<Object> keys() {
                ArrayList<Object> keys = new ArrayList<>(super.keySet());
                keys.sort(Comparator.comparing(Object::toString));
                return Collections.enumeration(keys);
            }
        };
        props.putAll(hashVers);

        ensureParentDirExists(file);
        try (OutputStream out = new FileOutputStream(file)) {
            props.store(out, null);
        }

        logInfo("Saved hasVers to " + file);
    }

    static String hashVerJson(Map<String, String> hashVers) {
        StringBuilder result = new StringBuilder();

        ArrayList<String> keys = new ArrayList<>(hashVers.keySet());
        keys.sort(Comparator.naturalOrder());
        String maybeSeparatror = "";
        result.append("{");
        for (String key : keys) {
            result.append(maybeSeparatror)
                    .append('"')
                    .append(StringEscapeUtils.escapeJson(key))
                    .append("\": \"")
                    .append(StringEscapeUtils.escapeJson(hashVers.get(key)))
                    .append("\"");
            maybeSeparatror = ",\n ";
        }
        result.append("}\n");

        return result.toString();
    }

    private void storeHashVerJson(Map<String, String> hashVers,
                                  String file)
            throws IOException
    {
        ensureParentDirExists(file);
        saveToFile(new File(file), hashVerJson(hashVers));
        logInfo("Saved hasVers to " + file);
    }

    private String ownHash(MavenProject module,
                           // nullable
                           String extraHashData)
            throws IOException
    {
        File basedir = module.getBasedir();

        MessageDigest digest = newDigest(extraHashData);
        fileHash(new File(basedir, "pom.xml"), "", digest);
        File srcDir = new File(basedir, "src");
        if (srcDir.exists()) {
            // TODO: do we need a hash if the src dir doesn't exist
            // TODO: non-standard directory layout?
            directoryHash(srcDir, "", digest);
        }
        return str(digest);
    }

    // Directory hash calculation includes not only content, but also
    // paths of all files and sub directories in it.
    // We use not just names, but paths relative to module root
    // in order to ensure the hash changes when files or directories
    // are moved around, without changing their names and the order
    // of traversal by the hashver mojo. For example:
    //
    //           src/
    //             dir/
    //               file
    //
    //           src/
    //             dir/
    //             file
    //
    // The paths are built in a platform independent way, using a constant
    // separator and case sensitive file names,
    // so that the hash is stable across operating systems.
    private static final String PATH_SEPARATOR = "/";

    private void directoryHash(File dir,
                               String parentPath,
                               MessageDigest digest)
            throws IOException
    {
        logDebug("hashing directory: " + dir.getPath());
        String myPath = parentPath + PATH_SEPARATOR + dir.getName();
        digest.update(myPath.getBytes(UTF_8));

        File[] children = dir.listFiles();
        if (children == null) {
            throw new IOException(dir.getPath() + " is not a directory");
        }

        // case sensitive sorting (in contrast to the platform-dependent
        // File.compareTo), to make the hash OS-independent.
        Arrays.sort(children, Comparator.comparing(File::getName));

        for (File child : children) {
            if (child.isDirectory()) {
                directoryHash(child, myPath, digest);
            } else  {
                assert child.isFile();
                fileHash(child, myPath, digest);
            }
        }
    }

    private static void fileHash(File f,
                                 String parentPath,
                                 MessageDigest digest)
            throws IOException
    {
        String myPath = parentPath + PATH_SEPARATOR + f.getName();
        digest.update(myPath.getBytes(UTF_8));
        fileContentHash(f, digest);
    }

    private static void fileContentHash(File f, MessageDigest digest)
            throws IOException
    {
        try (InputStream in = new FileInputStream(f)) {
            // TODO: use a single shared buf to avoid constant allocation and gc
            byte[] buf = new byte[10240];
            int len;
            while ((len = in.read(buf)) != -1) {
                // To check hashing CPU cost run the mojo one time normally
                // and one time with this property set. The time difference
                // is the CPU cost. In my experiment with maven-wagon
                // there were no noticeable difference.
                if (System.getProperty(hashverDigestSkip.name()) == null) {
                    digest.update(buf, 0, len);
                }
            }
        }
    }

    /**
     * Properties we don't document for public use,
     * but providing customizations for special cases.
     */
    enum ExtraProperties {
        hashverDigestSkip,
        hashverAncestorPomsForRelaxedHashing,
        hashverAncestorPomsIgnoreErrors,
        hashVerSnapshotDependencyMode,
    }

    String fullHash(MavenProject prj,
                    MavenSession session,
                    DependencyGraphBuilder dependencyGraphBuilder,
                    Map<String, String> ownHashByArtifact,
                    // nullable
                    String extraHashData)
            throws DependencyGraphBuilderException,
                    IOException,
                    MojoExecutionException
    {
        ProjectBuildingRequest buildingRequest =
                new DefaultProjectBuildingRequest(
                        session.getProjectBuildingRequest());

        buildingRequest.setProject(prj);

        DependencyNode rootNode = dependencyGraphBuilder.buildDependencyGraph(
                buildingRequest,
                null,
                session.getProjects());

        String ownHash = ownHashByArtifact.get(
                ArtifactUtils.key(prj.getArtifact()));
        if (ownHash == null) {
            throw new RuntimeException(
                    "Can find own hash for module " + prj.getName());
        }

        MessageDigest depTreeDigest = newDigest(extraHashData);
        ancestorPomsHash(prj, depTreeDigest);
        dependencyTreeHash(rootNode, ownHashByArtifact, depTreeDigest);

        return ownHash + "." + str(depTreeDigest);
    }

    private static final Base64.Encoder BASE_64
            = Base64.getEncoder().withoutPadding();

    // Includes hashversion into the representation of the dependency tree
    // elements that are part of the reactor.
    static class MySerializingDependencyNodeVisitor 
            extends SerializingDependencyNodeVisitor 
    {
        Map<String, String> ownHashByArtifact;

        public MySerializingDependencyNodeVisitor(
                Writer writer,
                Map<String, String> ownHashByArtifact)
        {
            super(writer, SerializingDependencyNodeVisitor.STANDARD_TOKENS);
            this.ownHashByArtifact = ownHashByArtifact;
        }

        @Override
        public boolean visit(DependencyNode node) {
            return super.visit(new DependencyNode() {

                @Override
                public Artifact getArtifact() {
                    return node.getArtifact();
                }

                @Override
                public List<DependencyNode> getChildren() {
                    return node.getChildren();
                }

                @Override
                public boolean accept(DependencyNodeVisitor visitor) {
                    return node.accept(visitor);
                }

                @Override
                public DependencyNode getParent() {
                    return node.getParent();
                }

                @Override
                public String getPremanagedVersion() {
                    return node.getPremanagedVersion();
                }

                @Override
                public String getPremanagedScope() {
                    return node.getPremanagedScope();
                }

                @Override
                public String getVersionConstraint() {
                    return node.getVersionConstraint();
                }

                @Override
                public String toNodeString() {
                    String ownHash = ownHashByArtifact.get(
                            ArtifactUtils.key(node.getArtifact()));

                    if (ownHash != null) {
                        return hashVerNodeString(node, ownHash);
                    } else {
                        if (node.getArtifact().isSnapshot()) {

                            final String ignore = "ignore";
                            if (!ignore.equals(
                                    System.getProperty(hashVerSnapshotDependencyMode.name())))
                            {
                                String errMsg = "You have a -SNAPSHOT "
                                    + "dependency in the dependency tree, "
                                    + "which is not very consistent with "
                                    + "the idea of immutable hash versions: "
                                    + node.getArtifact()
                                    + ". Specify -D"
                                    + hashVerSnapshotDependencyMode.name()
                                    + "=" + ignore
                                    + " if you are sure. See also "
                                    + "https://github.com/avodonosov/hashver-maven-plugin/issues/7";

                                throw new RuntimeException(errMsg);
                            }
                        }
                        return node.toNodeString();
                    }
                }

                @Override
                public Boolean getOptional() {
                    return node.getOptional();
                }
            });
        }
    }

    /**
     * Similar to DependencyNode.toNodeString(), only use hash version
     * instead of the artifact version. (This function is only for
     * artifacts in the reactor).
     * 
     * The goal is to prevent  hashversion to depend on itself - 
     * prevent *.version property expression
     * value to sneak into the dependency tree we hash - otherwise new
     * hashversion will affect the dependency tree and thus lead
     * to different hashversion of the artifact.
     * 
     * Unlike DependencyNode.toNodeString() we don't include
     * various "premanaged" properties - they are not important to
     * the hashversion goal (only the final versions are important), 
     * and the "premanaged" properties can also include the
     * *.version property expression.
     */
    private static String hashVerNodeString(DependencyNode node, String ownHash) {
        // assert ownHash != null;
        
        StringBuilder result = new StringBuilder();

        Artifact artifact = node.getArtifact();

        if (artifact.getGroupId() != null) {
            result.append(artifact.getGroupId());
            result.append(":");
        }
        result.append(artifact.getArtifactId());
        result.append(":");
        result.append(artifact.getType());
        if (artifact.hasClassifier()) {
            result.append(":");
            result.append(artifact.getClassifier());
        }
        result.append(":");
        result.append(ownHash);
        if (artifact.getScope() != null ) {
            result.append( ":" );
            result.append(artifact.getScope());
        }
        
        if (node.getOptional() != null && node.getOptional()) {
            result.append(" (optional) ");
        }

        return result.toString();
    }

    private void ancestorPomsHash(MavenProject prj,
                                  MessageDigest digest)
            throws IOException, MojoExecutionException
    {
        // Implementation note. In debugger I observed that parents
        // located in the same project have prj.getParent().getFile(),
        // and external parent poms are available via
        // prj.getParentArtifact().getFile(). But I don't know the design
        // behind the related Maven APIs, maybe other cases are possible
        // for access to the parent pom file.
        // Thus the extensive error reporting below and the special system
        // properties allowing to not fail in case of parent pom
        // hashing problems.

        // TODO: prevent repeated hashing of the same pom.xml
        //      (when it's in the ancestor chains of several projects).
        //      Not super important, as hashing the same pom files even
        //      couple hundred times (for projects with hundreds of modules)
        //      is quick and constitutes usually a tiny fraction of the source
        //      code files we hash.

        MavenProject parent = prj.getParent();
        Artifact parentArtifact = prj.getParentArtifact();
        while (parent != null) {
            File pomFile = parent.getFile();
            if (pomFile == null && parentArtifact != null) {
                pomFile = parentArtifact.getFile();
            }

            if (pomFile == null) {
                String msg = "Unexpected situation when hashing ancestor"
                        + " poms of " + prj + ": both parent.getFile()"
                        + " and parentArtifact.getFile() are absent. "
                        + "The parent: " + parent + ".";

                String parentKey =
                        parent.getGroupId() + ":" + parent.getArtifactId();

                if (null != System.getProperty(hashverAncestorPomsIgnoreErrors.name())
                        || csvListMember(parentKey,
                                System.getProperty(hashverAncestorPomsForRelaxedHashing.name())))
                {
                    String parentDataToHash = parent.getGroupId()
                            + ":" + parent.getArtifactId()
                            + ":" + parent.getVersion();
                    logWarn(msg + " Using a relaxed hashing -"
                            + " hash the following instead of the pom file: "
                            + parentDataToHash);
                    digest.update(parentDataToHash.getBytes(UTF_8));
                } else {
                    throw new MojoExecutionException(msg
                            + " Please report this situation to "
                            + "https://github.com/avodonosov/hashver-maven-plugin/issues."
                            + " Meanwhile you can mute the error by adding "
                            + parentKey + " to the comma separated list in the "
                            + hashverAncestorPomsForRelaxedHashing
                            + " property or just ignore all parent pom hashing"
                            + "  errors by setting the "
                            + hashverAncestorPomsIgnoreErrors + " property.");
                }
            } else {
                fileContentHash(pomFile, digest);
            }

            parentArtifact = parent.getParentArtifact();
            parent = parent.getParent();
        }
    }

    static boolean csvListMember(/* non-null */String elem,
                                 /* nullable */String csvList)
    {
        if (csvList == null) {
            return false;
        }
        for (String part : csvList.split(",")) {
            if (elem.equals(part)) {
                return true;
            }
        }
        return false;
    }

    private static void dependencyTreeHash(DependencyNode theRootNode,
                                           Map<String, String> ownHashByArtifact,
                                           MessageDigest digest)
    {
        StringWriter writer = new StringWriter();

        DependencyNodeVisitor visitor =
                new MySerializingDependencyNodeVisitor(writer,
                        ownHashByArtifact);
        theRootNode.accept(visitor);

        String tree = writer.toString();

        digest.update(tree.getBytes(UTF_8));
    }

    private static String str(MessageDigest digest) {
        return BASE_64.encodeToString(digest.digest())
                .replaceAll("\\+", "-")
                .replaceAll("/", "_");
    }

    private static MessageDigest newDigest(String extraHashData) {
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGO);
            if (extraHashData != null) {
                digest.update(extraHashData.getBytes(UTF_8));
            }
            return digest;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(
                    "Unexpected: " + DIGEST_ALGO + " is not supported by Java",
                    e);
        }
    }
}
