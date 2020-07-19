package pro.avodonosov.mvnhashver;

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
import java.nio.charset.StandardCharsets;
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

// TODO: Investigate the "Downloading " message for reactor modules
//       of maven-wagon project during the "hashver" mojo execution
//       (seems happening only for the dependency-managed modules):
//       >
//       >    Downloading: https://repo.maven.apache.org/maven2/org/apache/maven/wagon/wagon-http-shared/4y2Na53WvxoBMoDdnku6ZIN61UM.P87Jh9SiFF__5rM-TAGdYaFzv6U/wagon-http-shared-4y2Na53WvxoBMoDdnku6ZIN61UM.P87Jh9SiFF__5rM-TAGdYaFzv6U.jar
//       >
@Mojo(name = "hashver", aggregator = true)
public class HashVerMojo extends AbstractMojo {

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
                hashVers.put(
                        hashVerKey(prj),
                        prjHash(mavenSession, prj,
                                dependencyGraphBuilder,
                                ownHashByArtifact,
                                extraHashData));
            } catch (DependencyGraphBuilderException e) {
                throw new MojoExecutionException(
                        "prjVersion() failed for " + prj.getName(),
                        e);
            }
        }

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
    }

    private void logInfo(String msg) {
        getLog().info(msg);
    }

    private String hashVerKey(MavenProject prj) {
        return includeGroupId
                ? prj.getGroupId() + "." + prj.getArtifactId() + ".version"
                : prj.getArtifactId() + ".version";
    }

    private void storeHashVers(Map<String, String> hashVers)
            throws IOException
    {
        storeHashVerProps(hashVers, "target/hashversions.properties");
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
                new OutputStreamWriter(new FileOutputStream(file),
                                        StandardCharsets.UTF_8));
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

    private String ownHash(MavenProject module,
                           // nullable
                           String extraHashData)
            throws IOException
    {
        File basedir = module.getBasedir();

        MessageDigest digest = newDigest(extraHashData);
        fileHash(new File(basedir, "pom.xml"), digest);
        File srcDir = new File(basedir, "src");
        if (srcDir.exists()) {
            // TODO: do we need a hash if the src dir doesn't exist
            // TODO: non-standard layout?
            directoryHash(srcDir, digest);
        }
        return str(digest);
    }

    private void directoryHash(File dir, MessageDigest digest)
            throws IOException
    {
        logInfo("hashing directory: " + dir.getPath());
        // TODO: digest file names relative to module root,
        //       to make the hash independent on work directory / machine
        // TODO: os independence: make the hash independent of file.separator
        // TODO: os independence: sort the directory children with case
        //       sensitivity
        //       (should be reliable on Windows too - even if Windows doesn't
        //       distinguish char case in file names, it can't randomize
        //       character case when returning the names - it will always
        //       return it as user it, I think).
        digest.update(dir.getName().getBytes(StandardCharsets.UTF_8));

        File[] children = dir.listFiles();
        if (children == null) {
            throw new IOException(dir.getPath() + " is not a directory");
        }

        Arrays.sort(children);
        for (File child : children) {
            if (child.isDirectory()) {
                directoryHash(child, digest);
            } else  {
                assert child.isFile();
                fileHash(child, digest);
            }
        }
    }

    private static void fileHash(File f, MessageDigest digest)
            throws IOException
    {
        digest.update(f.getName().getBytes(StandardCharsets.UTF_8));
        try (InputStream in = new FileInputStream(f)) {
            // TODO: use a single shared buf to avoid constant allocation and gc
            byte[] buf = new byte[10240];
            int len;
            while ((len = in.read(buf)) != -1) {
                // Checking hashing CPU cost - run the moje one time normally
                // and one time with this property set. The time difference
                // is the CPU cost. In my experiment with maven-wagon
                // there were no noticeable difference.
                if (System.getProperty("hashverDigestSkip") == null) {
                    digest.update(buf, 0, len);
                }
            }
        }
    }

    static String prjHash(MavenSession session,
                          MavenProject prj,
                          DependencyGraphBuilder dependencyGraphBuilder,
                          Map<String, String> ownHashByArtifact,
                          // nullable
                          String extraHashData)
            throws DependencyGraphBuilderException
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
        return ownHash + "." + dependencyTreeHash(rootNode,
                                                  ownHashByArtifact,
                                                  extraHashData);
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
     * Similar DependencyNode.toNodeString(), only use hash version
     * instead of the artifact version. (This function is only for
     * artifacts in the reactor).
     * 
     * The goal is to prevent  hashversion to depend on itself - 
     * prevent *.version property expression
     * value to sneak into the  dependency tree we hash - otherwise new
     * hashversion will affect the dependency tree and thus lead
     * to different hashversion of the artifact.
     * 
     * Unlike DependencyNode.toNodeString() we don't include
     * various "premanaged" properties - they are not important to
     * the hashversion goal (only the final versions are important), 
     * and they can also include the *.version property expression.
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

    private static String dependencyTreeHash(DependencyNode theRootNode,
                                             Map<String, String> ownHashByArtifact,
                                             // nullable
                                             String extraHashData)
    {
        StringWriter writer = new StringWriter();

        DependencyNodeVisitor visitor =
                new MySerializingDependencyNodeVisitor(writer,
                        ownHashByArtifact);
        theRootNode.accept(visitor);

        String tree = writer.toString();

        MessageDigest digest = newDigest(extraHashData);
        digest.update(tree.getBytes(StandardCharsets.UTF_8));
        return str(digest);
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
                digest.update(extraHashData.getBytes(StandardCharsets.UTF_8));
            }
            return digest;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(
                    "Unexpected: " + DIGEST_ALGO + " is not supported by Java",
                    e);
        }
    }
}
