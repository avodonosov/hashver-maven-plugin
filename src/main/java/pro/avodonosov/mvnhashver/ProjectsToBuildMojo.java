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
import static pro.avodonosov.mvnhashver.Utils.cleanDir;
import static pro.avodonosov.mvnhashver.Utils.saveToFile;

/**
 * <p>Given a database of previously successfully built
 * hashversioned modules, produces a list of modules
 * whose current versions are absent in this database
 * (modules affected by changes since the successful builds).
 *
 * <p>The produced module list is saved to target/hashver-projects-to-build
 * in the format suitable for the -pl (--projects) maven option,
 * with intention to build only them:
 *
 * <pre>
 *     mvn install -pl "$(cat target/hashver-projects-to-build)" -am
 *</pre>
 *
 * <p>The database of previously successfully build modules is a directory
 * specified by property dbDir.
 *
 * <p>The mojo also produces content to be copied into the db directory
 * if the build of those modules succeeds:
 *
 * <pre>
 *    cp -r targer/hashver-db-additions/*  the-db-directory/
 *</pre>
 */
@Mojo(name = "projects-to-build", aggregator = true)
public class ProjectsToBuildMojo extends HashVerMojo {

    @Parameter(property = "dbDir", required = true)
    String dbDirPath;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        File dbDir = new File(dbDirPath);
        if (!dbDir.isDirectory()) {
            throw new MojoExecutionException(
                    "Directory does not exist: " + dbDir.getAbsolutePath());
        }

        File targetDir = new File("target");
        File dbAdditionsDir = new File(targetDir,"hashver-db-additions");
        ensureDirExists(dbAdditionsDir);

        try {
            cleanDir(dbAdditionsDir);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Error cleaning " + dbAdditionsDir, e);
        }

        Map<String, String> hashVers = super.executeImpl(mavenSession,
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

        String projectsCsv = String.join(",", projects);
        logInfo("hashver-projects-to-build: " + projectsCsv);
        File affectedProjectsFile = new File(targetDir, "hashver-projects-to-build");
        logInfo("saving to " + affectedProjectsFile.getAbsolutePath());
        try {
            saveToFile(affectedProjectsFile, projectsCsv);
        } catch (IOException e) {
            throw new MojoExecutionException("Error saving 'projects to build' file", e);
        }
    }

    private static final byte[] DB_FILE_CONTENT = "1".getBytes(UTF_8);

    static void saveDbAddition(File dir, MavenProject prj, String hashVer)
            throws MojoExecutionException
    {
        File f = prjDbFile(dir, prj, hashVer);

        File parent = f.getParentFile();
        ensureDirExists(parent);

        try {
            saveToFile(f, DB_FILE_CONTENT);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Error saving file: " + f.getAbsolutePath());
        }
    }

    static void ensureDirExists(File dir) throws MojoExecutionException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new MojoExecutionException(
                    "Error creating directory: " + dir.getAbsolutePath());
        }
    }

    static boolean dbContains(File dir, MavenProject prj, String hashVer) {
        return prjDbFile(dir, prj, hashVer).exists();
    }

    static File prjDbFile(File dbDir, MavenProject prj, String hashVer) {
        // As usually when many files are stored on file system,
        // do not store them in one plain directory, otherwise
        // file system performance may degrade significantly when working
        // with this directory (depends on file system).
        // For example, see how .git/objects directory is organized.
        //
        // We use the following structure:
        //
        //     dbDir/artifactId-O.D/artifactId-hashversion
        //
        // where O and D are first digits of the artifact own hash and
        // dependency tree hash.
        //
        // TODO: take includeGroupId into account here?

        String artifact = prj.getArtifactId();
        int dotPos = hashVer.indexOf('.');
        if (dotPos < 0) {
            throw new IllegalArgumentException(
                    "hashver is expected to have dot inside: " + hashVer);
        }
        String verGroup = hashVer.substring(0, 1)
                + hashVer.substring(dotPos, dotPos + 2);
        File groupDir = new File(dbDir, artifact + "-" + verGroup);
        return new File(groupDir, hashVer);
    }
}
