For every module in a maven project generates a version which is a
hash code of the module sources and its dependency tree (in the spirit
of the Nix package manager). A build extension can skip modules
if artifact for the same version already exists.

The goal is to avoid rebuild of modules which are not changed. Without
splitting into multiple repositories, manual version management
and the instability of the SNAPSHOT versions.
 
Mostly oriented to speedup CI server builds of multi-module projects.

![Maven Central](https://img.shields.io/maven-central/v/pro.avodonosov/hashver-maven-plugin)

# Usage

## Pure Informational
Run this in the root directory of a multi-module maven project:

        mvn pro.avodonosov:hashver-maven-plugin:1.6:hashver
    
This produces file target/hashversions.properties with hashversions for 
all modules in the project. The hashversions are in the form
{module own hash}.{dependency tree hash}. If you change something in the code
you can observe how hashversions are changed accordingly.

## Avoid Unnecessary Rebuilds

The functionality described above is already sufficient in some sense:
with every build generate a hashver file, and when publishing the artifacts
record in some storage their hashversions. On the next build generate hashvers
again, consult the storage and list the modules whose new hashversions are
absent in the storage.

Then build only those modules, using `mvn -pl <the modules> -am`.

However, for this you will need to bother about the storage for hashversions,
create supporting scripts. And also, such build will rebuild unchanged
dependencies of changed modules. For example, if `subModuleX` is changed
in a project with the following structure:

    - moduleA
      - subModuleX
      - subModuleY
    - moduleB
    - moduleC

the `mvn -pl :moduleA,:subModuleX -am` will also rebuild unchnaged
`subModuleY` because it is required for `moduleA` build.

Therefore, it is more optimal to actually use hashversions as artifact
versions. That way no separate storage is needed, all information is stored
in the artifact repository. And unchanged dependencies of changed modules
are not rebuilt but simply fetched from the repository.

### Preparation steps.

1. Give every module an explicit version using property [artifactId].version,
   like `<version>${mymodule.version}</version>`. Use this property
   expression everywhere you refer the module version (as a dependency
   of other modules, etc).
1. Create file versions.properties in the root directory of the project,
   which specifies normal (non hash) values for all the module version
   properties.
   (You can copy the target/hashversions.properties we generated earlier and
   change all hash versions to normal versions)
1. Add the extension provided by the hashver-maven-plugin to the 
   .mvn/extensions.xml. In usual builds this extension reads the
   version.properties and defines all those properties as system properties.
   In "hashversions mode" it will read the target/hashversions.properties
   instead, and will skip build of modules whose artifacts exist already.
1. Include flatten-maven-plugin so that the final pom's published with your
   artifacts have the version property expressions resolved.

See how all this done for maven-wagon project as an example:
https://github.com/avodonosov/maven-wagon/commit/6a7647db0759839c3f83b1a575321050169db708

Now the build will work as before, using the "normal" versions we specified
in the versions.properties.

Note, maven prints warnings like
```text
    [WARNING] 'version' contains an expression but should be a constant.  ...
```
they can be ignored - the flatten-maven-plugin takes care of the problem
motivating this warning.

### Skip Unaffected Modules

When we want to utilize the hashversions functionality, first run the mojo
to produce the target/hashversions.properties, and then use
`-DhashverMode` in your next maven command.

```shell script
    mvn pro.avodonosov:hashver-maven-plugin:1.5:hashver
    mvn package -DhashverMode
```

The mojo cannot be run in the same maven invocation as other goals,
it has to be run separately as shown above, due to a kind
of "chicken and egg" problem. The system properties for version
expressions, to be interpolated into the project tree and be
in effect for the other goals, have to be defined very early,
right after maven session starts,
and at this early point maven is unable to produce dependency graph
we need to compute hashversions. If we compute hashversions after
the project graph is built, it's too late to apply them.

In the hashver build mode we can consider the target/hashversions.properties
the main build result, because only part of the project artifacts
are produced on the build environment (the affected ones)
and the rest are only referred in the hashversions.properties.
Your build publishing and running scripts should take this into account,
for example publish the produced artifacts and the hashversions.properties,
and when rolling out this build to a server take the hashversions.properteis
and fetch all the artifacts according to it.

# Assumptions
We assume all the module sources are located in the src/ directory.
For every module we only hash the pom.xml, the src/ directory and the optional
extraHashData parameter.

# Reference

## The "hashver" mojo

```shell script
    mvn pro.avodonosov:hashver-maven-plugin:1.5:hashver \
          [-DextraHashData=someBuildProperty] \
          [-DincludeGroupId]
```

- extraHashData - Any value you want to include into the hash calculation.
  It may reflect some build properties other than sources.
- includeGroupId - The property names in the target/hashversions.properties
  will include group ID. For example, org.apache.maven.wagon.wagon-http.version
  instead of simply wagon-http.version.

## The build extension

The following properties are supported. Some of them can be passed
through either system properties (sys) or project properties in pom.xml (prj),
others only through system properties, because they are checked
before the pom.xml is read.
 
- hashverMode (sys) - If set, the extension works as if 
  -DskipExistingArtifacts -DsysPropFiles=target/hashversions.properties
  were specified.
- sysPropFiles (sys) - A comma separated list of property files to read
  into system properties. If file name is prefixed with opt: the
  file is optional, otherwise it's required. Relative file names are 
  treated realtive to the project root directory.
  Example: 
  ```shell script
  -DsysPropFiles=/a.properties,opt:b.properties,src/c.properties
  ```
  The files are loaded in the order they are specified, so if the same
  property is specified in several files, the last file wins.
  
  Default value: versions.properties  
- skipExistingArtifacts (sys, prj) - When specified tries to find an artifact
  for every project module, and if the artifact exists - removes the module from
  maven session, thus skipping its build. 
- existenceCheckMethods (sys, prj) - How the artifact existence check
  is performed. A comma separated list, with the following values supported:
  - resolve - The default. Invokes standard maven artifact resolution
    process. The downside of this method is that it performs artifact download,
    even when not needed for the build (not a dependency of any changed module).
    The advantage is the most native integration with maven - it supports
    all protocols, all repository properties specified in all levels
    (proxies, passwords, etc). 
  - local - Only check the local repository.
  - httpHead - Try performing HTTP HEAD for the artifact URL built against
    base URL of every enabled repo. Does not support proxies and authentication.
    
  Example
  ```shell script
      -DexistenceCheckMethods=local,httpHead
  ```

# Design considerations
When only dependencies have changed, but the module own sources are not changed,
strictly speaking, the module only needs to be re-tested, compilation could
be skipped (unless a dependency instruments code or affects compilation otherwise).
But we don't want to risk correctness by hunting this minor speedup, especially
that Java compiler is very fast and most of the build time is usually spent on
tests. So we just fully rebuild the module.

# Discussion
Email thread with title
"versioning by hashes to speedup multi-module build (a'la nix package manager)"
on the Maven User List.
https://lists.apache.org/thread.html/r0a4d687d9a6c315d0f90db59d7e6e7da5d71c18e94d6439c7a548dc2%40%3Cusers.maven.apache.org%3E

# Maven older than 3.3.1

The .mvn/extensions.xml is only supported since maven 3.3.1 (released in 2015).

For older maven place the hashver maven plugin jar file to ${maven.home}/lib/ext.

Unfortunately, declaring the extension in the pom.xml
doesn't work in our case, because our extension reads the property files
to system properties in AbstractMavenLifecycleParticipant.afterSessionStart,
which is not called when the extension is declared in the pom.xml.

https://maven.apache.org/examples/maven-3-lifecycle-extensions.html#use-your-extension-in-your-build-s

# See Also
- Nix and Guix package managers. They version artifacts with hashcode of all
  build inputs.
- [gitflow-incremental-builder](https://github.com/vackosar/gitflow-incremental-builder)
  That's another maven extension for skipping unchanged modules. It detects
  changes comparing the current git branch to a reference branch
  (e.g. origin/develop).
- Gradle and Bazel build caches. Those newer build tools support build caches
  out of box. The hashver plugin is less granular than them - those tools
  cache on the level of individual build steps (compile, resource generation,
  packaging, etc), while we only cache full artifacts. On the other hand,
  the hashver plugin can optimize traffic - unnecessary artifacts are not
  downloaded, while the build caches download everything (I think).
- Gradle Enterprise - a commercial service offering a build cache not only
  for Gradle, but also for Maven. 

# Issues
Report security issues at: https://hackerone.com/central-security-project/reports/new 
Other issues on github.

# License
GNU AGPL v3.
