To install the customized jar into the maven repo, open a command window here and execute this command:

mvn install:install-file -Dfile=../dist/vnu.jar -DpomFile=pom.xml


Be sure to edit pom.xml if you want to bump the version or change other metadata.

PR 2019-01-31