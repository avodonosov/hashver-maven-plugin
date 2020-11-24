package pro.avodonosov.mvnhashver;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

@Mojo(name = "listProjects", aggregator = true)
public class ListProjectsMojo extends HashVerMojo {

    @Parameter(property = "dbDir", required = true)
    String dbDirPath;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        File dbDir = new File(dbDirPath);
        if (!dbDir.isDirectory()) {
            throw new MojoExecutionException(
                    "Directory does not exist: " + dbDir.getAbsolutePath());
        }

        File dbAdditionsDir = new File(new File("target"),
                                 "hashver-db-additions");
        if (!dbAdditionsDir.exists() && !dbAdditionsDir.mkdirs()) {
            throw new MojoExecutionException(
                    "Error creating " + dbAdditionsDir.getAbsolutePath());
        }

        try {
            cleanDir(dbAdditionsDir);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Error cleaning " + dbAdditionsDir, e);
        }

        Map<String, String> hashVers = computeHashVers(mavenSession,
                                                       includeGroupId,
                                                       extraHashData);

        ArrayList<String> projects = new ArrayList<>();
        for (MavenProject prj : mavenSession.getProjects()) {
            String hashVer = hashVers.get(hashVerKey(prj, includeGroupId));
            if (!dbContains(dbDir, prj, hashVer)) {
                projects.add(":" + prj.getArtifactId());
                saveDbAddition(dbAdditionsDir, prj, hashVer);
            }
        }

        logInfo("projects-to-build: " + String.join(",", projects));
    }

    private static final byte[] DB_FILE_CONTENT = "1".getBytes(UTF_8);

    static void saveDbAddition(File dir, MavenProject prj, String hashVer)
            throws MojoFailureException
    {
        File f = prjDbFile(dir, prj, hashVer);

        File parent = f.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new MojoFailureException("Error creating directory: "
                    + parent.getAbsolutePath());
        }

        try (FileOutputStream stream = new FileOutputStream(f)) {
            stream.write(DB_FILE_CONTENT);
        } catch (IOException e) {
            throw new MojoFailureException(
                    "Error saving file: " + f.getAbsolutePath());
        }
    }

    static boolean dbContains(File dir, MavenProject prj, String hashVer) {
        return prjDbFile(dir, prj, hashVer).exists();
    }

    static File prjDbFile(File dbDir, MavenProject prj, String hashVer) {
        String artifact = prj.getArtifactId();
        int dotPos = hashVer.indexOf('.');
        if (dotPos < 0) {
            throw new IllegalArgumentException(
                    "hashver is expected to have dot inside: " + hashVer);
        }
        String verGroup = hashVer.substring(0, 2)
                + hashVer.substring(dotPos, dotPos + 3);
        File groupDir = new File(dbDir, artifact + "-" + verGroup);
        return new File(groupDir, hashVer);
    }

    static void cleanDir(File dir) throws IOException {
        File[] children = dir.listFiles();
        if(children != null) {
            for(File child: children) {
                if(child.isDirectory()) {
                    cleanDir(child);
                }
                if (!child.delete()) {
                    throw new IOException(
                            "Error deleting " + child.getAbsolutePath());
                }
            }
        }
    }
}
