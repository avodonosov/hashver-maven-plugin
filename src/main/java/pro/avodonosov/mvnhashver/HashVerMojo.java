package pro.avodonosov.mvnhashver;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.DependencyNodeVisitor;
import org.apache.maven.shared.dependency.graph.traversal.SerializingDependencyNodeVisitor;

import javax.inject.Inject;
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
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Mojo(name = "hashver", aggregator = true)
public class HashVerMojo extends AbstractMojo {

    public static final String DIGEST_ALGO = "SHA-1";

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
                        ownHash(prj));
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
                        prj.getGroupId() + "." + prj.getArtifactId() + ".hashver",
                        prjVersion(mavenSession, prj,
                                dependencyGraphBuilder, ownHashByArtifact));
            } catch (DependencyGraphBuilderException e) {
                throw new MojoExecutionException(
                        "prjVersion() failed for " + prj.getName(),
                        e);
            }
        }

        getLog().info("HashVers computed: " + hashVers.size());
        ArrayList<String> keys = new ArrayList<>(hashVers.keySet());
        Collections.sort(keys);
        for (String prjKey : keys) {
            getLog().info(prjKey + "=" + hashVers.get(prjKey));
        }

        try {
            storeHashVers(hashVers);
        } catch (IOException e) {
            throw new MojoExecutionException("Error saving hashVers", e);
        }
    }

    private void storeHashVers(Map<String, String> hashVers)
            throws IOException
    {
        storeHashVerProps(hashVers, "hashversions.properties");
        storeMvnEx(hashVers, "mvnex.sh");
    }

    private void storeMvnEx(Map<String, String> hashVers, String file)
            throws IOException
    {
        ArrayList<String> keys = new ArrayList<>(hashVers.keySet());
        keys.sort(String::compareTo);
        try (Writer out = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8))
        {
            out.write("#!/bin/sh\n");
            out.write("mvn");
            for (String key : keys) {
                out.write(" ");
                out.write("-D");
                out.write(key);
                out.write("=");
                out.write(hashVers.get(key));
            }
            out.write(" \"$@\"");
        }
    }

    private void storeHashVerProps(Map<String, String> hashVers, String file)
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
        try (OutputStream out = new FileOutputStream(file)) {
            props.store(out, null);
        }
    }

    private static String ownHash(MavenProject module)
            throws IOException
    {
        File basedir = module.getBasedir();

        MessageDigest digest = newDigest();
        fileHash(new File(basedir, "pom.xml"), digest);
        File srcDir = new File(basedir, "src");
        if (srcDir.exists()) {
            // TODO: do we need a hash if the src dir doesn't exist
            // TODO: non-standard layout?
            directoryHash(srcDir, digest);
        }
        return str(digest);
    }

    private static void directoryHash(File dir, MessageDigest digest)
            throws IOException
    {
        System.out.println("directoryHash: " + dir.getPath());
        digest.update(dir.getName().getBytes(StandardCharsets.UTF_8));

        File[] children = dir.listFiles();
        if (children == null) {
            throw new IOException(dir.getPath() + " is not a directory");
        }

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
                digest.update(buf, 0, len);
            }
        }
    }

    static String prjVersion(MavenSession session,
                             MavenProject prj,
                             DependencyGraphBuilder dependencyGraphBuilder,
                             Map<String, String> ownHashByArtifact)
            throws DependencyGraphBuilderException
    {
        ProjectBuildingRequest buildingRequest =
                new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());

        buildingRequest.setProject(prj);


        DependencyNode rootNode = dependencyGraphBuilder.buildDependencyGraph(
                buildingRequest,
                null,
                session.getProjects());

        String ownHash = ownHashByArtifact.get(ArtifactUtils.key(prj.getArtifact()));
        if (ownHash == null) {
            throw new RuntimeException(
                    "Can find own hash for module " + prj.getName());
        }
        return ownHash + "." + dependencyTreeHash(rootNode, ownHashByArtifact);
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
                        return ownHash + " " + node.toNodeString();
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

    private static String dependencyTreeHash(DependencyNode theRootNode,
                                             Map<String, String> ownHashByArtifact)
    {
        StringWriter writer = new StringWriter();

        DependencyNodeVisitor visitor =
                new MySerializingDependencyNodeVisitor(writer,
                        ownHashByArtifact);
        theRootNode.accept(visitor);

        String tree = writer.toString();

        MessageDigest digest = newDigest();
        digest.update(tree.getBytes(StandardCharsets.UTF_8));
        return str(digest);
    }

    private static String str(MessageDigest digest) {
        return BASE_64.encodeToString(digest.digest())
                .replaceAll("\\+", "-")
                .replaceAll("/", "_");
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(DIGEST_ALGO);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(
                    "Unexpected: " + DIGEST_ALGO + " is not supported by Java",
                    e);
        }
    }
}
