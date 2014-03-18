Taverna Component Validator
===========================

**A Simple Tool for checking whether a Taverna Component satisfies a Component Profile.**

Produces a printed report of how many constraints in the profile are satisfied by the component.

Building
--------
Use [Apache Maven](http://maven.apache.org/download.cgi) 3.0 or later:

	mvn clean package

Running
-------
	java -jar target/component-validator-0.0.1-SNAPSHOT-jar-with-dependencies.jar the-component.t2flow the-profile.xml

Note that `the-component.t2flow` and `the-profile.xml` may be URLs. (They are resolved with respect to the `file:` URL for the current working directory by default.)
