package pro.avodonosov.mvnhashver;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import javax.inject.Inject;

import static pro.avodonosov.mvnhashver.Logging.LOG_PREFIX;

// Work in progress....
@Mojo(name = "audit")
public class AuditMojo extends AbstractMojo {

    @Parameter(defaultValue = "false", property = "includeGroupId")
    boolean includeGroupId;

    @Inject
    MavenSession mavenSession;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
//        Map<String, Artifact> m = mavenSession.getCurrentProject().getManagedVersionMap();

//        for (String k : m.keySet()) {
//            getLog().info(LOG_PREFIX + k + " : " + m.get(k));
//        }

        MavenProject prj = mavenSession.getCurrentProject();
        Model m = prj.getOriginalModel();
        if (!versionPropExpr(m).equals(m.getVersion())) {
            logError(prj.getArtifactId() + ": prj.version: " + m.getVersion()
                    + " : " + m.getLocation("version"));
        }
        Parent parent = m.getParent();
        if (parent != null
                && !versionPropExpr(parent).equals(parent.getVersion()))
        {
            logError(prj.getArtifactId() + ": prj.parent.version: "
                    + parent.getVersion()
                    + " : " + parent.getLocation("version"));
        }
        for (Dependency d : m.getDependencies()) {
            getLog().info(LOG_PREFIX + d.getArtifactId()
                    + ":" + d.getVersion()
                    + ":" + d.getLocation(d.getArtifactId()));


        }

    }

    void logError(String msg) {
        getLog().error(LOG_PREFIX + msg);
    }

    String versionPropExpr(Model m) {
        return "${" + versionProp(m) + "}";
    }

    String versionProp(Model m) {
        return includeGroupId
                ? m.getGroupId() + "." + m.getArtifactId() + ".version"
                : m.getArtifactId() + ".version";
    }

    String versionPropExpr(Parent p) {
        return "${" + versionProp(p) + "}";
    }

    String versionProp(Parent p) {
        return includeGroupId
                ? p.getGroupId() + "." + p.getArtifactId() + ".version"
                : p.getArtifactId() + ".version";
    }
}
