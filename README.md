Explores idea of immutability applied to module versioning.

For every module in a maven project generates a version which is a
hash code of the module sources and its dependency tree.

The goal is to avoid rebuild of modules which are not changed.

    mvn pro.avodonosov:hashver-maven-plugin:1.0-SNAPSHOT:hashver
    
Produces file hashversions.properties.

    