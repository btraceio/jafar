# jafar
Experimental, incomplete JFR parser

Very much a work in progress. 
The goal is to be able to parse JFR files and extract the event data in programmatic way with the least effort possible.

## Requirements
Java 21 (mostly just because I wanted to try the pattern matching)

Git LFS is used to store the JFR recordings, so you will need to have it installed to clone the repository.
Install it following the instructions at https://docs.github.com/en/repositories/working-with-files/managing-large-files/installing-git-large-file-storage

## Tl;DR
Allow quickly wiring JFR with interface based handlers using bytecode generation.
I was nerdsniped by [@nitsanw](https://github.com/nitsanw) and quickly thrown together this more or less a PoC.

The parser is pretty fast, actually. You can try the demo app which will extract all the `jdk.ExecutionSample` events,
count the number of samples and calculate the sum of the associated thread ids (useful, right?). On Mac M1 and ~600MiB
JFR this takes around 1 second as compared to cca. 7 seconds using JMC parser. The JDK `jfr` tool will run out of memory,
but to be fair it is trying to print the full content of each event.

### Building
First, retrieve the binary resources via `./get_resources.sh`
Then, build the project with `./gradlew shadowJar`

Now, you can run the demo app with:
```shell
# The Jafar parser
java -jar demo/build/libs/demo-all.jar [jafar|jmc|jfr] path_to_jfr.jfr
```

## Usage
The main idea is to define a handling interface which corresponds to a JFR event type. The linking is done via `@JfrType` 
annotation. For convenience, there is a `JfrEvent` interface which can be extended to define the event handling interface.

The interface methods should correspond to the fields of the JFR event. The method names should be the same as the field names.
If the field name is not a valid Java identifier, the method will be linked with the field via `@JfrField` annotation.
The interface can have methods excluded from linking with the JFR types - by annotating such methods with `@JfrIgnore`.

```java

@JfrType("custom.MyEvent")
public interface MyEvent extends JfrEvent {
  String myfield();
}

try (JafarParser parser = JafarParser.open("path_to_jfr.jfr")) {}
    // registering a handler will return a cookie which can be used to deregister the same handler
    var cookie = parser.handle(MyEvent.class, event -> {
        System.out.println(event.startTime());
        System.out.println(event.eventThread().javaName());
        System.out.println(event.myfield());
    });
    parser.handle(MyEvent.class, event -> {
        // do something else
    });
    parser.run();
    
    cookie.destroy(parser);
    // this time only the second handler will be called
    parser.run();
}

```

This short program will parse the recording and call the `handle` method for each `custom.MyEvent` event.
The number of handlers per type is not limited, they all will be executed sequentially.
With the handlers known beforehand, the parser can safely skip all unreachable events and types, massively saving on the parsing time.

As an optimization for batch processing applications where the JFR files share the same type structure,
it is possible to use a global parser context and be reusing the generated handler code

```java
@JfrType("custom.MyEvent")
public interface MyEvent extends JfrEvent {
  String myfield();
}

ParsingContext parsingContext = ParsingContext.create();

String path;
while ((path = getNextPath()) != null) {
    try (JafarParser parser = JafarParser.open(path), parsingContext) {}
        // registering a handler will return a cookie which can be used to deregister the same handler
        var cookie = parser.handle(MyEvent.class, event -> {
            System.out.println(event.startTime());
            System.out.println(event.eventThread().javaName());
            System.out.println(event.myfield());
        });
        parser.handle(MyEvent.class, event -> {
            // do something else
        });
        parser.run();
        
        cookie.destroy(parser);
        // this time only the second handler will be called
        parser.run();
    }
}
```

### Generate Jafar Type Interfaces during the build
There is an in-progress Gradle plugin for generating the Jafar type interfaces based on either the JVM runtime JFR metadata
or the metadata extracted from a JFR file.

```gradle
plugins {
    id 'io.btrace.jafar-gradle-plugin' version '0.0.1-SNAPSHOT'
}

repositories {
    mavenCentral()
    mavenLocal()
    // use sonatype snapshots - that's where the plugin artifact lives for now
    maven {
        url "https://oss.sonatype.org/content/repositories/snapshots/"
    }
}

// This will be called implicitly before project compilation.
// Can also be invoked by hand to bootstrap the development
generateJafarTypes {
    inputFile = file('/tmp/my.jfr') // extract the metadata from this JFR file; otherwise use the JVM runtime metadata
    outputDir = project.file('src/main/java') // generate the files under this particular source directory; if not specified 'buld/generated/sources/jafar/src/main' will be used
    overwrite = false // specify whether to overwrite the existing files 
    eventTypeFilter { // process only certain event types (and transitive closure of types they depend on)
        it == 'jdk.ExecutionSample'
    }
    targetPackage = 'io.jafar.demo.types' // generate the types in this package
    
}
```